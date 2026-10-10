# ADR 0006 — Where the letter composer keeps its configuration

- **Status:** Proposed
- **Date:** 2026-09-24
- **Deciders:** Epistola plugin maintainers
- **Related:** `epistola-document-preview`, `PreviewService`, `FormioFormGenerator`, `RetryFormService`, `docs/document-preview.md`, `docs/building-blocks-analysis.md`, `docs/async.md`

## Context and problem statement

A recurring customer ask: let an employee pick a letter from a list, adjust a few values, preview
the result and send it. A typical process offers around 50 letters, and the same Epistola template
is often used by several processes.

Today that is built as one very large Form.io form. Per letter it holds an input field per
adjustable value, an `epistola-document-preview` pointing at that letter's `generate-document`
process link, a hand-written `overrideMapping` on that preview, and conditional logic to show the
block only when that letter is selected. The `overrideMapping` largely repeats what the field keys
already say — `pv:motivation` already means "this is saved to `$pv.motivation`" — so the form grows
with letters × adjustable values, and every template or case-schema change ripples through it.

The **letter composer** replaces that with one component, configured once:

- which templates are selectable;
- one **baseline mapping** for all of them, plus an optional override fragment per template;
- **inputs generated from the template's data contract** for whatever the mapping does not fill.

Three things had to be settled before building it.

**Where does that configuration live?** The plugin must not introduce its own database tables; it
has to use storage Valtimo already owns.

**What happens to what the employee types?** Sometimes it is letter-only text, sometimes it is case
data that must be written back. Generated inputs cannot decide this by themselves.

**How does it reach a process?** Generation is asynchronous (submit → result collector →
correlation, see `docs/async.md`), and the process around it usually has more to do: deliver,
archive, record. After the form, a customer may even want a separate process started with the
entered data.

Two constraints from the way this plugin is put together shaped the outcome. First, a data mapping
is written per **process link**, evaluated in that service task's context, so it can read that
process's variables; it is not a property of the template. Second, a mapping may be opaque —
`{"aanvrager": $doc.someObject}` or a computed expression — so which value an employee may change
cannot be derived from the mapping text.

## Decision drivers

- No plugin-owned persistence: no new tables, no migrations, no retention of our own.
- Configuration should be config-as-code **and** editable by a functional admin, like the rest of
  Valtimo's case configuration.
- Adding the 51st letter must not mean touching form fields, previews and override mappings one by
  one.
- What the employee previewed must be what is generated.
- Work with advanced mappings, not only with explicit field-by-field ones.
- Stay on Valtimo's stable, public surface (see "Valtimo compatibility" in `CLAUDE.md`).

## Decision outcome

### The configuration lives in the Form.io component, inside the form definition

The composer's settings — the template list, the baseline mapping, the per-template override, the
per-input presentation and the write-back map — are stored in the component's own JSON, and so in
the Valtimo form definition that contains it.

Valtimo form definitions are already scoped to a case definition, versioned with it, deployable
from the classpath as config-as-code, and editable in the form builder. That satisfies the "no new
tables" constraint without inventing a second place for authors to look, and it keeps the letter
list with the form that offers it.

### The composer computes the data; generation only renders it

The component's value is one JSON object, stored in a process variable:

```json
{
  "letters": [
    {
      "catalogId": "municipality-demo",
      "templateId": "besluit-bezwaar",
      "data": { "…": "mapping result, with the employee's input applied" },
      "inputs": { "…": "only what the employee actually typed" }
    }
  ]
}
```

The service task therefore never reads the form's configuration. It renders what it is handed. Three
things follow from that: the preview and the real letter use the same data by construction; the
process variable is a complete record of what was sent; and a later retry can re-render it exactly.

The trade-off is that `data` is a snapshot. If the case changes between the form and generation, the
letter still carries what the employee approved. For this flow that is the wanted behaviour, but it
is a real difference from today's links, which map at generation time.

### Write-back is a map from case path to expression, evaluated after the letter is composed

An input the employee types is, by default, part of that letter and nothing else. Some values
belong in the case as well — a corrected phone number is worth keeping — so a composer may declare
where they go:

```json
"writeBack": {
  "doc:/aanvrager/telefoon": "$inputs.telefoon",
  "doc:/aanvrager/adres": "$inputs.straat & \" \" & $inputs.huisnummer",
  "pv:motivatie": "$inputs.motivatie"
}
```

**The destination is the key and an expression is the value**, not the other way round. That
direction is deliberate:

- **One writer per destination.** Keying by the input instead would let two of them target the same
  case path with no defined order; keying by the destination makes that unrepresentable.
- **It mirrors the baseline mapping.** That reads `template field ← JSONata over the case`; this
  reads `case path ← JSONata over the letter`. One language, one mental model, and the same
  authoring widget.
- **It is not limited to copying.** A destination can be computed from several inputs, or from a
  constant, which a per-input target cannot express.
- **It is keyed by something stable.** Contract field paths differ per template, so a per-input map
  only works where letters share a contract. Case paths are shared across every letter offered.
- **The keys are resolver keys**, so `ValueResolverService.validateValues` can check them against
  the case definition while the form is being authored, rather than failing at runtime.

The expression sees `$inputs` (only what a human typed), `$data` (everything the letter renders
with), and `$doc`/`$pv` for context — the same context the baseline mapping has. **An expression
that yields nothing writes nothing**, which is what makes "only write what was actually supplied"
the default instead of clobbering good case data with nulls.

#### The map is evaluated when the letter is composed, and the result travels with it

The expressions are evaluated at the same moment the data snapshot is taken, and what comes out
rides on the letter variable alongside it:

```json
{
  "schemaVersion": 1,
  "templateId": "…",
  "catalogId": "…",
  "data": { "…": "what the letter renders with" },
  "inputs": { "…": "only what the employee typed" },
  "writeBack": { "doc:/aanvrager/telefoon": "0612345678" }
}
```

Not the map itself, and not a lookup performed later. Generation is asynchronous, so the task that
applies the write may run minutes or hours after the form was submitted — long enough for the form
definition to have been redeployed in between. Reading the map at apply time would then write using
a configuration the employee never saw. Evaluating at compose time gives write-back the same
snapshot guarantee `data` already has: **what was approved is what is written.**

It also leaves the applying task with nothing to configure. It reads a map of destination to value
and hands it straight to the API, because the shape is already the argument
`ValueResolverService.handleValues` takes:

```java
valueResolverService.handleValues(processInstanceId, execution, letter.writeBack());
```

The composer computes and the process applies — the same division generation already follows. The
field is additive for `schemaVersion` purposes: a plugin old enough to ignore it has no
applying task either, so nothing misbehaves silently.

#### Amended once it was built (2026-10-02)

Three things in the sections above did not survive implementation. They are corrected here rather
than rewritten away, because the reasoning that led to them is the useful part.

**The write-back map is read from the form, not from the letter.** The sections below describe the
resolved values riding on the letter variable and the generate action handing them "straight to the
API". That cannot be done safely: the letter is assembled in the browser — which is what makes the
previewed letter the generated one — so its keys arrive from the browser too, and a crafted
submission could name any case path. Writing to an arbitrary `doc:` path is a silent edit to the
case, which is a different matter from a letter rendered with odd data. So the **destinations and
the expressions both come from the stored composer**, found through the case the process runs on,
and the letter contributes only the data the expressions read. The snapshot guarantee the map was
meant to provide is kept a different way: the rules are evaluated at generate time against the
letter that was approved, and a form redeployed in between changes the rules, not the data.

**Finding the composer needs no identifiers from the letter.** An earlier draft of this amendment
had the letter carry a component key and activity id so the generate action could find its
configuration. It does not need them: the execution's process instance carries the case document as
its business key, which gives the case definition, and therefore every form on it and every composer
and rule those declare. Broader than one composer, and safe _because_ it is broader — nothing the
browser sends decides which configuration applies.

**Applying it at form submission is not possible, which is worth stating because it is the obvious
idea.** Valtimo decides a submitted field is a resolver value from its _top-level_ key, so a
`doc:`-keyed field writes to the case on submit and that is how every ordinary form behaves — the
composer behaving differently is a real inconsistency. But a composer cannot get such a field onto
the form: its own field is already keyed `pv:`, so the letter reaches the process variable the
generate task reads, and a Form.io component cannot add a top-level sibling beside itself. That is
the same obstacle this ADR already records under "projecting the generated inputs up into the parent
form", which was written about per-letter input keys and applies just as much here.

**And one assumption that was wrong in the other direction:** `pv:` destinations are _not_ limited
to a process-scoped context. Valtimo's own process-variable resolver finds the instance by business
key when all it has is a document id, so a `pv:` destination works wherever a `doc:` one does.

#### Amended again, and measured (2026-10-10)

The amendment above reasoned its way to "applying it at form submission is not possible". That
holds, and is now **measured** rather than asserted — but it is narrower than it sounds, and one
route it did not consider is open. Recorded here in full so nobody re-derives it a third time; this
session did it a second time by not reading this far.

**What the heading two sections up still claims is wrong.** "The map is evaluated when the letter is
composed, and the result travels with it" was never corrected by the 2026-10-02 amendment, which
addressed where destinations come from and left the timing claim standing. The rules are evaluated
**at generate time, from the stored form**. `docs/letter-composer.md` said the same thing and has
been corrected.

**The form-field route is dead, and here is the evidence.** A hidden child of the composer, keyed
`doc:/besluit/spikeNative` and left `persistent` (Form.io's default), was added to the deployed
demo form and a real submission was driven through the browser:

| Check                                           | Outcome                                    |
| ----------------------------------------------- | ------------------------------------------ |
| Deployed form carries the prefixed child        | yes — confirmed through the management API |
| Field instantiated anywhere in the DOM          | **no**                                     |
| Value present in the case document after submit | **no** — `besluit` absent entirely         |

Form.io does not instantiate a custom component's `components` children. The prefilled task-id and
document-id carriers work _despite_ this: Valtimo fills them by walking the form-definition JSON and
the component reads them back out of `root.form`, never from a rendered component — which is also
why they are `persistent: false`. So `persistent` was never the obstacle, and two ideas fall
together: letting Valtimo write the composer's inputs natively by naming them with destinations, and
triggering the plugin's own resolver with a hidden `epistola:writeBack` carrier.

**`ValueResolverFactory` does have a write side** — `handleValues(UUID, Map)`,
`handleValues(String, VariableScope, Map)`, `preProcessValuesForNewCase`,
`preProcessValuesForNewDocument` — and the plugin already registers the `epistola:` prefix for
reading. It is unusable here for exactly the reason above: Valtimo routes to it from a _submitted
field_, and the composer cannot produce one.

**`ExternalDataSubmittedEvent` does not fire for a composer form.** It is published by
`DefaultFormSubmissionService` and consumed by `objecten-api`, which made it look like the natural
hook. A probe that logged a registration marker and a catch-all listener — so the negative comes
from a probe known to be alive — saw it zero times across a full composer task-form submission.

**What does fire on submit**, from the same probe: `TaskCompletedEvent` (once),
`JsonSchemaDocumentCreatedEvent`, `JsonSchemaDocumentModifiedEvent`,
`JsonSchemaDocumentSnapshotCapturedEvent`, `TaskEvent`, `OperatonTaskEvent`.

**Decided and implemented (2026-10-10): write-back moves to form submission, as a listener.**
`TaskCompletedEvent` lives in the `contract` module, so it is public API, and carries
`getBusinessKey()` — the case document id for a dossier process — plus `getVariables()`, which holds
the letter. Nothing else about write-back would change: the rules, the JSONata evaluation, the
destination guard and the write through `ValueResolverService` all stay exactly as they are. Only
the call site moves out of the generate action.

**Why that is worth doing**, beyond consistency with every other form: the window this ADR worried
about closes (rules cannot change between composing and generating if they are applied at
submission), and a failed write can be reported to the person still looking at the form instead of
being swallowed because the letter has become irreversible. Note too that the protection the current
placement buys is thinner than this ADR assumed — `submitAndRecord` throws when Epistola refuses the
_request_, not when rendering fails, so a letter that fails to render has already written to the
case today.

What it took: `ComposerSubmissionListener` translates the event into a case id and the submitted
variables, and `ComposerWriteBackService.applyFromSubmission` asks the case's own configuration which
variables hold a letter — each composer declares the `pv:` key it writes, so no value is inspected
for a letter-ish shape. The generate action no longer applies anything and no longer holds a
write-back collaborator at all, which is what keeps a retry from re-rendering and re-writing.

Two things the implementation taught that the reasoning did not:

- **Apply once per variable, not per composer.** A case type may hold several composers writing the
  same variable — the demo's task form and its ad-hoc start form both use `pv:epistolaLetter` — and
  a submission carries one value for it. Applying per composer saved the same values twice, which is
  one case version per composer. Caught by reading the log of a real submission, not by a test.
- **A failed write still cannot be reported to the employee.** The event arrives _after_ the task
  has completed, so throwing would report a failure for a submission that succeeded, and a retry
  would complete a second task. The listener therefore contains the failure and logs it, exactly as
  the generate action used to — so that half of the motivation for moving is not delivered, and
  needs a mechanism that is not an exception (#179).

**Still open:** a start form that creates a case completes no task, so that shape is not covered by
this trigger and needs `JsonSchemaDocumentCreatedEvent` or a process-start listener; and a form flow
is untested, though it completes a task and `FormFlowStepCompletedEvent` exists as a fallback.

#### The generate action applies it, after Epistola accepts the letter

A plain Valtimo task form offers no completion hook a plugin can write from, and the composer's
generated inputs are invisible to the one Valtimo already has: they live in a nested Form.io
instance and collapse into the component's single `pv:` value, so the field-level write-back that
handles a `doc:`-keyed form field never sees them.

The write therefore happens in the process — and in the **generate action itself**, not a task of
its own. One composer produces one letter, which one generate task renders, so there is nothing to
coordinate: the action already reads the letter variable, and the resolved values ride on it.

**Acceptance is the commit point.** Generation is asynchronous: the action returns once Epistola
has taken the request and owns it, while the document arrives later at the
`EpistolaDocumentGenerated` catch event. Writing at acceptance rather than at rendering matters
because the catch event is _optional_ — a process may generate without waiting, and then there is
no later point to write at — an error path taken after acceptance may never reach it, and waiting
delays the correction for as long as rendering takes.

**A failed write must not fail the activity.** This is what makes folding it in safe. Once Epistola
has accepted the request the letter is irreversible, so throwing at that point would claim something
did not happen that did — and retrying the activity would re-submit and generate a _duplicate
letter_. So:

- the submission fails → throw, exactly as today. Nothing was sent and nothing is written;
- the submission succeeds and the write fails → record it and continue, on the result variable
  beside the status and error message the action already writes there. A process that cares reads
  it like any other result field; one that does not carries on with a letter that was genuinely
  sent.

The residual is worth stating: **accepted is not rendered.** Epistola can take a request and fail on
it afterwards — bad data, a template error — leaving the case updated for a letter that never went
out. The window is narrow, it is recoverable through the existing retry flow and retry form, and
within it the value written is still a fact about the case. That is a better trade than holding a
correction hostage to a wait the process may never perform.

Rejected alternatives: a **separate apply task** (the author would have to remember it, and a
composer configured with a `writeBack` map and no apply task would lose data _silently_, which is
worse than any failure the folded version has; an earlier draft of this ADR chose it on the grounds
that generate tasks and composers have different cardinalities, which is not true — one composer,
one letter, one generate task); a **Valtimo task-completion listener** (automatic, but it depends on
an extension point whose stability would have to be established, is invisible in the process, and
has an ordering question against Valtimo's own document update); and **projecting the generated
inputs up into the parent form** as real `doc:`/`pv:` fields (the composer would have to inject
siblings at runtime under keys that change per letter, which breaks both the saved form definition
and prefill).

It works unchanged on a start form, where the generate task runs inside the instance the form
started.

#### And it makes the preview more faithful, not less

The preview lays the employee's input over the mapping's result. Once a value also lands in the
case, that is no longer quite what the saved letter will be: after the write, the baseline mapping
would produce that value itself.

The fix falls out of the same map. Apply the write-back expressions to a **copy** of `$doc`/`$pv`,
then run the baseline mapping over that, and the preview answers "what does this letter look like
once this is saved" — automatically, with nothing extra to configure. `PreviewRequest` already
carries both an overlay applied before the mapping (`inputOverrides`) and one applied after it
(`overrides`); the composer has only ever used the second.

> An earlier draft of this ADR put the target on each generated input, and let that target decide
> the preview semantics. It was replaced by the map above: it could not express a computed
> destination, it left two inputs able to fight over one case path, and it keyed configuration by
> contract paths that differ per template.

### Dropdowns are a contract concern first, a component concern second

A `string` that should be a dropdown is resolved in this order:

1. **In the Epistola data contract**, as a JSON Schema `enum`. Every integration benefits, not just
   Valtimo. Epistola already has **code lists** — curated `{code, label}` sets with inline, URL and
   classpath sources — though they currently bind to variant attributes rather than contract fields.
   Extending them to contract fields is the structural fix and is Epistola-side work.
2. **In the component configuration**, as a per-input override: widget, label, options (fixed or
   read from a case path), required, help text. This covers lists the template cannot know because
   they are case- or process-specific.
3. **A custom Form.io form** for that template, replacing the generated inputs entirely, for
   anything the first two cannot express.

### The process is joined through a JSON process variable, in one of two shapes

Verified against Valtimo 13.44 and the pinned 13.47: `ValtimoFormFlow.startSupportingProcess(formFlowInstanceId, params)`
resolves `doc:`/`pv:` targets through `ValueResolverService` and then calls
`ProcessDocumentService.startProcessForDocument(...)` for a `processDefinitionKey` on the same case
document. Starting another process with the entered data is therefore a Valtimo-native step and
needs no plugin machinery.

Both shapes are supported and documented:

- **In the same process.** A generic generate task reads the composer output and the existing
  catch-event correlation waits for the result. Simple, and the case waits for its letter.
- **A letter process per chosen letter,** started from the form flow's completion with the composer
  JSON. Each letter gets its own lifecycle — generate, wait, deliver, record — without blocking the
  main process. This is the shape to use when several letters are chosen at once.

### Reuse across processes is packaged as a building block, later

Valtimo building blocks (≥ 13.21) package BPMN, forms, a document schema and process links under
`config/building-block/<key>/<version>/`, and a case calls one through a call activity with an
input/output mapping wizard. That is exactly the shape of the letter lifecycle including the async
wait, and `docs/building-blocks-analysis.md` already explored it for the retry flow.

It is deliberately **not** in the first version: the composer must first prove itself in one case.
The design keeps it open by not assuming the composer lives in the case's own form or process.

## Alternatives considered

- **A second plugin configuration ("Epistola letter set").** Valtimo already stores plugin
  configurations, so it breaks no constraint, and it would be shared by id across forms and
  processes. Rejected: plugin configurations describe _connections_ (base URL, API key, tenant), are
  not versioned with a case definition, and the letter set would become a JSON blob in a generic
  property UI.
- **In the `generate-document` link's action properties, or a new plugin action on a generic
  service task.** Existing storage with a good admin UI. Rejected: it puts form concerns into
  BPMN-level configuration, and it forces the form to point at a service task, the coupling the
  preview already suffers from (`processDefinitionKey` + `sourceActivityId`).
- **Objects API through object-management.** Shared, editable, versioned. Rejected: extra
  infrastructure that not every customer runs.
- **Scaffolding a form per letter.** A tool that generates a starting point which authors refine.
  Rejected as the primary route: everything it produces is a copy, so it drifts from templates and
  mappings, and a template used in five processes means five copies. It stays interesting as
  authoring assistance on top of the composer.
- **Deriving editability from the mapping text.** Attractive, and impossible in general: an opaque
  or computed mapping hides which template field an input reaches. Superseded by "ask for what the
  mapping does not fill".
- **A form flow with a step per letter.** Native Valtimo, but it needs a step and a routing
  condition per letter, which is per-letter plumbing again, and it presents as a wizard.
- **Rendering a nested letter form at runtime through Form.io's nested form component.** Not
  available: it loads its child from a Form.io server, and Valtimo's prefill and submission only
  walk a form's own components (`FormIoFormDefinition.getInputFields`).

## Consequences

- Adding a letter is a configuration change in one component, not a form surgery: no extra fields,
  preview or override mapping.
- The same template in another process gets its own baseline mapping there, which is right, because
  the mapping belongs to the process context.
- **The browser now names the template**, where previously the server derived it from a process
  link. The preview endpoint must therefore check that the requested template is one the task's own
  form offers, by reading the form definition for that task. Without that check, any task viewer
  could render any template in the tenant.
- The form JSON grows with the configuration (template list, mappings). It stays small compared to
  50 letters of fields, but it is no longer trivially diff-readable.
- `FormioFormGenerator` must grow up before it can carry real contracts: enums, primitive arrays,
  objects inside arrays, `nullable`. Today it renders those as text fields.
- Nested-form validity must be propagated to the outer form; the retry form does not do this today,
  so `disableOnInvalid` ignores required fields inside it.
- The dropdown story depends partly on Epistola: contract-level `enum`/code lists are the structural
  fix, and until they exist the per-input override carries that weight.
- Existing processes with one `generate-document` link per letter keep working unchanged. The
  composer is additive.
- Write-back is a case write configured in a form definition. That is no new authority — a form
  author can already put a `doc:`-keyed field on a form — and the map is read server-side, so the
  browser cannot introduce or redirect one. It does mean a composer's settings deserve the same
  review a case form's field keys get.
