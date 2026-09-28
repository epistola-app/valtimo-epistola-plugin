# Letter composer

One form component that offers a list of letters. The employee picks one, fills in whatever the
case could not supply, sees a live preview, and a single generate task renders whichever letter was
chosen.

The point is what it replaces. Offering ten letters used to mean ten blocks of input fields, ten
preview components with a hand-written override mapping each, show/hide logic per letter, and ten
service tasks in the BPMN. With the composer, **a form offering fifty letters is the same size as
one offering three**, and adding a letter is one row in the component's settings.

Where its configuration lives, and why, is [ADR 0006](adr/0006-letter-composer-configuration.md).

> **Alpha.** The composer works end to end and is covered by tests, but the shapes it stores may
> still change between releases. What it stores carries a `schemaVersion` so a change is safe to
> make — an older plugin refuses what a newer one wrote instead of misreading it (see
> [What it stores](#what-it-stores)) — but a case built on it now may need revisiting. The palette
> entry and the component's settings say so too, where an author will actually see it.

## It is a module of its own

Nothing else in the plugin depends on the composer, so it is kept together and switchable:

|            |                                                                                                                                                   |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| Backend    | `app.epistola.valtimo.composer` (+ `.web`), wired by `EpistolaComposerConfiguration`                                                              |
| Frontend   | `lib/composer/`, registered through one entry point (`registerEpistolaComposerComponents`) and calling one service (`EpistolaComposerApiService`) |
| Off switch | `epistola.composer.enabled=false` — no beans, no endpoints                                                                                        |

What it borrows, it borrows narrowly: the Epistola API, the JSONata mapping service, the Form.io
form generator, and the start-form gate it shares with the document preview. Its generation step is
an action on the plugin class, because Valtimo scans that class for actions, but the behaviour
lives in the composer's own `ComposedLetter`.

## How it works

```
employee picks a letter
      ↓
POST /composer/prepare  { taskId, templateId }
      ↓  backend, all derived from the task:
      ↓    · the composer's configuration, read from the form behind this task
      ↓    · the baseline mapping evaluated against this case ($doc, $pv)
      ↓    · the template's contract, from Epistola
      ↓
{ data, form }   form = the contract fields the mapping left empty
      ↓
employee types    →  laid over `data`, empty inputs pruned
      ↓
POST /composer/preview { taskId, templateId, data }   → PDF
      ↓
submit → pv:epistolaLetter = { templateId, catalogId, data, inputs }
      ↓
generate task (action config v2) renders $pv.epistolaLetter.data
```

**A letter with a lot to fill in is stepped through.** Above six inputs the generated form is shown
as a wizard, with clickable breadcrumbs so a step is one click away. Steps are named by what the
contract already calls things: a group becomes a step with its name, a group that is a lot on its
own is split into numbered parts of it (`Aanvrager 1/2`), a step holding one field takes that
field's name, and only a step of several unnamed fields falls back to `Stap n`. Below the threshold
nothing changes. This happens in the browser (`composer-sections.ts`), not in the generator: it is
presentation only — same fields, same keys, same submission — so the generated form stays canonical
and the step titles can be translated.

**Which fields the employee is asked for is read from the mapping's outcome, never from its text.**
A mapping may be opaque (`{"aanvrager": $doc.someObject}`) or computed, so which template field an
expression feeds cannot be known by reading it. Evaluating it against the real case answers the
question directly: whatever the template requires and the mapping did not produce is what the
employee supplies. Fields the mapping _did_ fill are deliberately not offered — correcting case
data belongs in a case form, not in one letter.

## Configuring the component

| Setting                          | Meaning                                                                                                                                                                                                                                          |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Property name** (`key`)        | Where the chosen letter is stored. Use a `pv:` key, e.g. `pv:epistolaLetter`, so the generate task can read it with `$pv`.                                                                                                                       |
| **Which letters, from where**    | Pick the Epistola connection and catalog, then tick the letters to offer and name each one as the employee should see it. The three cascade: changing the connection clears the catalog and the ticks, because those ids mean nothing elsewhere. |
| **Baseline mapping**             | One JSONata mapping over `$doc`/`$pv` for every offered letter. Whatever it does not fill is asked of the employee.                                                                                                                              |
| **Also ask for optional fields** | Off by default: only fields the template marks required are asked for.                                                                                                                                                                           |
| **Process to start**             | Normally left empty — see [Where it is used](#where-it-is-used). Fill it in only when the backend says two processes offer the same letter.                                                                                                      |

Stored, that half looks like this — a form written by hand may also carry the three keys directly
on the component:

```json
"letterSet": {
  "pluginConfigurationId": "…",
  "catalogId": "municipality-demo",
  "templates": [{ "templateId": "besluit-bezwaar", "label": "Besluit op bezwaar" }]
}
```

A per-letter `dataMapping` fragment is merged over the baseline, so the baseline says what every
letter of this case type needs and the fragment only what makes this one different.

### Where it is used

Nothing says so. The same configuration works on a **user task form** and on the **start form** of
a process running on an open dossier, at the same time, and which one it is follows from where it
is opened:

- A task form fills the composer's hidden `epistola:taskId` carrier server-side, so a task id is
  present and the composer calls `/composer/prepare`, authorized on `OperatonTask:VIEW`.
- A start form fills no such carrier, so the composer calls `/composer/prepare/start` for the
  dossier on screen, authorized on `OperatonExecution:CREATE` plus `JsonSchemaDocument:VIEW`.

A task id always wins: a task form must not fall through to the start endpoint, which would read
another form's configuration and have no process instance for `$pv`.

**Which process a start form starts is worked out server-side.** A start form belongs to exactly
one process, but Valtimo hands a Form.io component nothing about the link that rendered it, so the
browser cannot know. The backend instead looks for the start forms carrying a composer with this
component's key that offers the chosen letter, narrows them to the processes the caller may
actually start on this case, and uses the single survivor. None → the same "no composer" answer a
form without one gets; more than one → it asks the author to fill in **Process to start**. Either
way the surviving definition goes through the same two gates as a named one, so discovery buys
convenience and changes no permission.

## What it stores

Three structures, in three places, with three lifecycles. Two of them outlive the code that wrote
them, so both carry `schemaVersion` — read tolerantly downwards (absent means 1, so nothing
deployed before the field existed is invalidated) and refused upwards, loudly, with a message
naming both versions. The rule is written once, in `ComposerSchema`, and mirrored in
`composer-schema.ts`.

**1. The component's settings**, in the form definition — versioned with the case definition,
deployable as config-as-code, editable in the builder:

```json
{
  "type": "epistola-letter-composer",
  "key": "pv:epistolaLetter",
  "schemaVersion": 1,
  "prefill": false,
  "askOptionalFields": false,
  "dataMapping": "<baseline JSONata>",
  "letterSet": {
    "pluginConfigurationId": "…",
    "catalogId": "municipality-demo",
    "templates": [{ "templateId": "besluit", "label": "…", "dataMapping": "<fragment>" }]
  }
}
```

`schemaVersion` and `prefill` are written back on every save, because Form.io drops schema equal to
the registered default and losing either is silent.

**A catalog is a property of the letter, not of the set.** `letterSet.catalogId` is the default; a
letter may carry its own `catalogId`, which is what lets one picker offer letters from more than
one catalog. A letter with neither is dropped with a warning — which catalog a letter comes from
decides what is rendered, and there is nothing to guess. The settings widget writes only the
default today; a hand-written form can already do both. (One thing still to decide before a second
catalog is common: a letter is named on the wire by its bare `templateId`, and template ids are
catalog-scoped.)

A form written before the widget existed carries `pluginConfigurationId`, `catalogId` and
`templates` directly on the component instead of under `letterSet`; both shapes are read.

**2. The chosen letter**, on the process variable the component's `pv:` key names:

```json
{ "schemaVersion": 1, "templateId": "…", "catalogId": "…", "data": { … }, "inputs": { … } }
```

`data` is everything the letter renders with; `inputs` is only what a human typed, kept so it stays
visible what was changed by hand — and it is what write-back will read. This is the structure with
the longest reach: a process instance can wait months for its generate task, so the plugin that
reads it may not be the one that wrote it.

**3. The generate action's configuration**, on the process link — `letterVariable`, `filename`,
`correlationId`, `resultProcessVariable`. Plain flat properties, covered by the plugin's existing
action-configuration versioning.

## Wiring the process

One service task generates whatever was chosen, with the **`epistola-generate-composed-document`**
action:

```json
{
  "letterVariable": "epistolaLetter",
  "resultProcessVariable": "epistolaResult"
}
```

That is all it needs: the composer already resolved the catalog, the template and the data while
the employee was looking at the preview, so this action has no template to pick and no mapping to
write. It is deliberately **not** a mode of `generate-document`: that action's configurator is
built around choosing a template and mapping to it, and the admin page verifies those ids really
exist — neither means anything here.

Waiting for the result is unchanged — see [async.md](async.md).

## What the component stores

```json
{
  "templateId": "besluit-bezwaar",
  "catalogId": "municipality-demo",
  "data": { "…": "the mapping's result with the employee's input laid over it" },
  "inputs": { "…": "only what the employee typed" }
}
```

`data` is what the letter is rendered with, in the preview and at generation, so **what was
previewed is what gets generated**. It is a snapshot: if the case changes between the form and
generation, the letter still carries what the employee approved. `inputs` is kept separately so it
stays visible what was changed by hand.

## A composer is never prefilled

The component's key is a `pv:` one, so the chosen letter becomes a process variable when the form
is submitted. Reading it back is another matter, and the component carries `prefill: false` for it:
Valtimo resolves a `pv:` key against the case's process instances when it prefills a form, and once
a dossier has run the process twice — two instances holding `epistolaLetter` — it cannot pick one
and fails the **whole form** with a 500. There is nothing to prefill either; the letter is what the
employee is about to choose.

Form.io drops component schema that equals the registered default, so the flag is re-added on save
the same way the hidden carriers are. `BundledComposerFormTest` pins it for every bundled form.

## Authorization

Both endpoints require `OperatonTask:VIEW` on the supplied task and derive everything else from it:
the process instance, the case document, and the form whose composer configuration applies. The
browser names a template; **a template the task's form does not offer is refused**. See
[authorization.md](authorization.md).

## Dropdowns, dates and other input shapes

Generated inputs follow the template's data contract:

| In the contract                    | Input                                                                                                                     |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| `enum` / `const`                   | a select of exactly those values                                                                                          |
| `title`                            | the field's label (beats a humanized property name)                                                                       |
| `description`                      | the tooltip                                                                                                               |
| `default`                          | prefills an empty field                                                                                                   |
| `"format": "email"`                | an email component                                                                                                        |
| `"format": "date"` / `"date-time"` | a text field with an explicit placeholder — Formio's date picker emits a full timestamp, which `"format": "date"` rejects |
| array of scalars                   | one repeating input                                                                                                       |
| array of objects                   | a datagrid                                                                                                                |

So the first place to fix "this should be a dropdown" is the template's contract in Epistola, where
every integration benefits — not just Valtimo.

## Demo

The **Correspondentie** case ships the demo, `correspondentie-letter-composer`:

- `form/kies-brief.form.json` — the composer, offering two letters over one baseline mapping.
- `bpmn/correspondentie-letter-composer.bpmn` — choose → generate → wait.
- `process-link/correspondentie-letter-composer.process-link.json` — the v2 generate link.

Choosing **Ontvangstbevestiging bezwaarschrift** asks for nothing: the case fills its whole
contract. Choosing **Besluit op bezwaar** asks for the three decision fields the case does not
know, and previews the letter once they are filled in.

**Bevestiging omgevingsvergunning** is the third letter on purpose: a permit confirmation offered
from a bezwaar case, which has none of its data. The baseline mapping fills nothing of its twelve
required fields, so it is the one that crosses the threshold and is stepped through — `Property`,
`Applicant` and `Activities`, all three named by its own contract. Use it to see sectioning; the
other two stay one short form, which is the point.

The same case also ships `correspondentie-ad-hoc-letter`: the composer on a start form, started
from the dossier's Start menu, generating without a task.

`LetterComposerE2ETest` walks the task-based demo against the real bundled contracts; the Playwright
suites (`e2e/tests/letter-composer.spec.ts` and `letter-composer-adhoc.spec.ts`) walk both in a
browser.

**Why its own case, rather than a second process on the Bezwaarprocedure case:** a case with two
startable processes makes Valtimo show a process picker before the start form, and in this version
its tiles are anchors with `href="#"` — clicking one reloads the app instead of opening the form,
which leaves _both_ processes unstartable. One startable process per demo case avoids that
entirely.

## Known gaps

- **The browser assembles `data`.** A crafted submission could carry values for fields that were
  never offered. The employee can already write the letter's text, so this is a governance limit
  rather than an escalation, but a form that must not allow it should keep using a hand-built form
  and a `generate-document` link per letter.
- **No write-back to the case.** Input stays with the letter. Fields that belong in the case
  should be collected in a case form (where the preview picks them up from the field keys — see
  [document-preview.md](document-preview.md)).
- **Form flows are not supported yet**: the configuration is read from a task's _form_ link.
- **Labels come from the contract.** A field with no `title` is labelled by humanizing its property
  name, so an English property name shows an English label in a Dutch form. The fix belongs in the
  template's data contract, where every integration benefits.
- **A per-template mapping fragment is not authorable in the settings widget.** The backend merges
  one and a hand-written form can set it; the widget captures template and label only.
- **One letter per task or per start.** Offering several at once needs the selection to be a list,
  and the process to loop or fan out.
- Objects nested inside an array item are still flattened by the form generator.
