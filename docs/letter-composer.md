# Letter composer

One form component that offers a list of letters. The employee picks one, fills in whatever the
case could not supply, sees a live preview, and a single generate task renders whichever letter was
chosen.

The point is what it replaces. Offering ten letters used to mean ten blocks of input fields, ten
preview components with a hand-written override mapping each, show/hide logic per letter, and ten
service tasks in the BPMN. With the composer, **a form offering fifty letters is the same size as
one offering three**, and adding a letter is one row in the component's settings.

Where its configuration lives, and why, is [ADR 0006](adr/0006-letter-composer-configuration.md).

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

**Which fields the employee is asked for is read from the mapping's outcome, never from its text.**
A mapping may be opaque (`{"aanvrager": $doc.someObject}`) or computed, so which template field an
expression feeds cannot be known by reading it. Evaluating it against the real case answers the
question directly: whatever the template requires and the mapping did not produce is what the
employee supplies. Fields the mapping _did_ fill are deliberately not offered — correcting case
data belongs in a case form, not in one letter.

## Configuring the component

| Setting                              | Meaning                                                                                                                       |
| ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------- |
| **Property name** (`key`)            | Where the chosen letter is stored. Use a `pv:` key, e.g. `pv:epistolaLetter`, so the generate task can read it with `$pv`.    |
| **Epistola plugin configuration id** | Which configured connection (tenant, credentials) renders the letters.                                                        |
| **Catalog**                          | The catalog every offered template lives in.                                                                                  |
| **Letters on offer**                 | One row per letter: template id, the label the employee sees, and optionally an extra mapping fragment for that letter alone. |
| **Baseline mapping**                 | One JSONata mapping over `$doc`/`$pv` for every offered letter. Whatever it does not fill is asked of the employee.           |
| **Also ask for optional fields**     | Off by default: only fields the template marks required are asked for.                                                        |

A per-letter fragment is merged over the baseline, so the baseline says what every letter of this
case type needs and the fragment only what makes this one different.

## Wiring the process

One service task generates whatever was chosen, using **action configuration v2**, where the
catalog and template are expressions:

```json
{
  "actionConfigVersion": 2,
  "catalogId": "$pv.epistolaLetter.catalogId",
  "templateId": "$pv.epistolaLetter.templateId",
  "dataMapping": "$pv.epistolaLetter.data",
  "outputFormat": "\"PDF\"",
  "filename": "$pv.epistolaLetter.templateId & \".pdf\"",
  "resultProcessVariable": "epistolaResult"
}
```

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

The **Bezwaarprocedure** case ships a demo process, `objection-letter-composer`:

- `form/kies-brief.form.json` — the composer, offering two letters over one baseline mapping.
- `bpmn/objection-letter-composer.bpmn` — choose → generate → wait.
- `process-link/objection-letter-composer.process-link.json` — the v2 generate link.

Choosing **Ontvangstbevestiging bezwaarschrift** asks for nothing: the case fills its whole
contract. Choosing **Besluit op bezwaar** asks for the three decision fields the case does not
know. `LetterComposerE2ETest` walks exactly that, against the real bundled contracts.

## Known gaps

- **The browser assembles `data`.** A crafted submission could carry values for fields that were
  never offered. The employee can already write the letter's text, so this is a governance limit
  rather than an escalation, but a form that must not allow it should keep using a hand-built form
  and a `generate-document` link per letter.
- **No write-back to the case.** Input stays with the letter. Fields that belong in the case
  should be collected in a case form (where the preview picks them up from the field keys — see
  [document-preview.md](document-preview.md)).
- **Form flows are not supported yet**: the configuration is read from a task's _form_ link.
- **One letter per task.** Offering several at once needs the selection to be a list, and the
  process to loop or fan out.
- Objects nested inside an array item are still flattened by the form generator.
