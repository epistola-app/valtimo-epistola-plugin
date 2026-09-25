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
per-input presentation and each input's target — are stored in the component's own JSON, and so in
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

### An input's target decides both the write-back and the preview semantics

Generated inputs are declared first; what happens to each one is declared after, as one setting per
input:

| Input target              | Written back on completion                | How the preview applies it                                   |
| ------------------------- | ----------------------------------------- | ------------------------------------------------------------ |
| `doc:/aanvrager/telefoon` | Case document, via `ValueResolverService` | **Before** the mapping (`inputOverrides`)                    |
| `pv:motivatie`            | Process variable, same route              | **Before** the mapping (`inputOverrides`)                    |
| _(none)_                  | Not written back                          | **After** the mapping, onto the template field (`overrides`) |

Both override paths already exist on `PreviewRequest`, and the write uses `ValueResolverService`,
the same service Valtimo's own form handling and `ValtimoFormFlow.completeTask` use. So a value that
belongs to the case flows through the mapping exactly as it will after saving, and a value that is
letter-only never touches the case.

This also answers "which fields may the employee edit" without reading the mapping: the composer
asks for the contract fields the mapping does not fill, and the author decides per input whether it
also lands in the case.

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
