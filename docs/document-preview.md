# Document Preview

The `epistola-document-preview` Formio component shows a live PDF preview of a document that will be generated later in the process. It supports two modes: auto-discover and configured.

> If you want to render or download a PDF that has **already been generated** (driven by process variables on the caller's task), use [`epistola-document`](document-component.md) instead.

**Related documentation:**

- [ADR 0004](adr/0004-start-event-preview-authorization.md) — why the start-event preview is a separate endpoint, and how it is authorized

- [Document Component](document-component.md) — the after-generation render/download component
- [Data Mapping](data-mapping.md) — how case/process data flows into Epistola templates
- [Use Cases](use-cases.md) — demo scenarios including the bezwaarprocedure
- [Form Flows](form-flows.md) — using the preview inside a multi-step Valtimo Form Flow

## Modes

Three, and the runtime pair is chosen by the **`previewContext` setting the author picks**, never
inferred from what happens to be available at runtime.

### Task mode (default)

The component sits on a user-task form. It targets a specific `generate-document` activity and feeds
form field values into the template as input overrides, so the document can be previewed **while the
user is still filling in the form**. The backend authorizes `OperatonTask:VIEW` on the task and
derives the process instance and case document from it.

If no task id arrives, the component says so and sends nothing. It does **not** fall back to start
mode — see the box below.

### Start mode

The component sits on a BPMN **start form**, so the letter can be checked before the case is created
at all. There is no task and no process instance, so the request names the process definition
instead and the backend authorizes the caller's permission to start it
(see [Authorization](authorization.md#start-event-preview)).

Two flavours, distinguished only by whether Valtimo opened the form against an existing case:

| Flavour                             | `$doc` resolves to                         | `$pv` resolves to                     |
| ----------------------------------- | ------------------------------------------ | ------------------------------------- |
| New case                            | the `doc` overrides, over **nothing**      | the `pv` overrides, else empty        |
| Start a process on an existing case | the `doc` overrides over the real document | the `pv` overrides, else empty        |
| _(task mode, for comparison)_       | the `doc` overrides over the real document | overrides over live process variables |

`$case` is empty in all three.

> **On a new-case start form an override mapping is effectively required.** There is no case data
> behind the preview, so a mapping that reads `$pv.foo` — or a preview with no mapping at all —
> produces nothing rather than falling back to stored data.

### Design mode

The Formio builder canvas and the component-settings dialog. The component renders a configuration
summary and issues no request.

> ### Why the mode is authored rather than detected
>
> It would be tempting to infer "no task id ⇒ start form". That inference is unsafe in exactly the
> situation it would fire. It would silently swap `OperatonTask:VIEW` on a specific task for a
> process-level gate, and drop `$doc`/`$pv` to the overrides alone — `$pv` binds to an empty map
> rather than throwing, so the result is a **plausible letter with fields quietly missing**.
>
> And the task id is known to go missing: four fixes exist because Formio's serializer dropped the
> hidden carrier from builder-saved forms. A fallback would have turned that loud, correct failure
> into a silent, wrong one.
>
> So each mode reaches exactly one endpoint, with no code path between them. A start-mode preview
> that _does_ find a task id treats itself as misconfigured, says so, and calls neither endpoint.
> (That check is why the task-id carrier is kept in start mode too.)

## Configuration

In the Formio builder, click the `epistola-document-preview` component to open its settings. The editForm shows:

### Where is this form shown? (required)

A radio choosing `previewContext`: **In a user task** (default) or **On a start form**. It decides
which endpoint the component calls and therefore which permission is checked, so it is not a
cosmetic hint — putting a start-form preview on a task form is refused rather than silently
downgraded.

Existing forms have no setting and are treated as task mode, so nothing needs re-authoring.

### Process Link (required)

A dropdown listing all `generate-document` process links across deployed process definitions. Selecting one stores:

- `processDefinitionKey` — identifies the process (e.g., `objection-handling`)
- `sourceActivityId` — identifies the generate-document service task (e.g., `generate-decision-gegrond`)

### Input Overrides (optional)

A mapping from the live form fields onto the `doc`/`pv` inputs the data mapping will read,
authored as a **JSONata expression over `$form`** (the form's component values). The expression
produces a `{ doc, pv }` overlay that is applied before the generate-document data mapping runs,
so the preview mirrors what generation will produce.

The builder has two modes:

- **Simple** — a `Scope / Input Path / Form Field` table (Scope is `doc` or `pv`; Form Field is a
  dropdown by label). The table serializes to JSONata under the hood.
- **Advanced** — the JSONata editor (with `$form` autocomplete), for transforms the table can't
  express (concatenation, conditionals, functions). A richer expression locks the builder into
  advanced mode.

### Auto-refresh (optional, default on)

Auto-refresh is controllable in **two places**: a builder default (set by the form author) and a
runtime toggle in the preview header (flipped by the end-user filling in the form).

**Builder options** (in the component's edit dialog):

- **Auto-refresh preview as the form is filled in** (checkbox, default **on**) — the default state of
  the runtime toggle. When on, the preview refreshes automatically as the form changes. It does
  **not** refresh on every keystroke: changes are debounced and the preview flushes when a field loses
  focus (blur). It also only re-renders when the mapped data actually changed, so typing in fields the
  mapping doesn't read no longer triggers a refresh.
- **Auto-refresh debounce (ms)** (number, default **1500**) — how long to wait after the last change
  before refreshing. Higher values feel calmer; lower values feel more responsive. Shown only when
  auto-refresh is on. Invalid/negative values fall back to 1500.

**Runtime toggle** — an **Auto-refresh** checkbox in the preview header (shown for override-driven
previews). It starts from the builder default and lets the person filling in the form turn
auto-refresh off/on for their session; the choice is preserved across Formio redraws. Turning it on
refreshes immediately. With it off, the preview updates only via the **Refresh** button.

The **Refresh** button in the preview header always works regardless of these settings, and it
forces a recompute from the live form data — so it loads the preview even before the first edit
(e.g. right after opening a form whose fields are pre-filled).

**Initial load.** When the mapped fields are already filled in on open, the preview loads itself
without a manual edit. Valtimo can populate form data asynchronously after the component mounts
(sometimes without a change event), so the wrapper re-attempts the initial paint a few times over
~2s until the data is present.

#### Example: objection decision preview

The assess-objection form has two fields (`pv:decision`, `pv:motivation`) and a preview component configured as:

```json
{
  "type": "epistola-document-preview",
  "key": "preview",
  "label": "Voorbeeld Besluitbrief",
  "processDefinitionKey": "objection-handling",
  "sourceActivityId": "generate-decision-gegrond",
  "overrideMapping": "{ \"pv\": { \"motivation\": $form.`pv:motivation`, \"decision\": $form.`pv:decision` } }"
}
```

Form field keys that aren't bare identifiers (e.g. `pv:motivation`) are backtick-quoted so JSONata
reads them as a single property. When the user edits the Motivatie field, the preview regenerates once
the field loses focus (or after the debounce, default ~1.5s) — and only if the mapped value changed.
Auto-refresh can be turned off so the preview only updates on the **Refresh** button.

Dotted Formio keys are treated as path traversal, matching Formio's own submission-data shape. If a
field key combines a value-resolver prefix with a nested path, only the unsafe segment is quoted.
For `doc:adres.straat`, use:

```jsonata
$form.`doc:adres`.straat
```

Do not quote the whole dotted key as one segment.

> **Legacy format.** Forms authored before this change store `overrideMapping` as an object of
> `"form:<componentKey>"` references (e.g. `{ "pv": { "motivation": "form:pv:motivation" } }`). These
> keep working — the frontend converts them to JSONata on the fly — and persist in the new format the
> next time the form is saved in the builder. The admin page's **Forms** tab lists forms still on the
> legacy format (`GET /admin/forms/legacy-override`).

## How it works

### Override mapping format

The mapping is a JSONata expression evaluated against a single binding, `$form` (the form's
component values), that returns a `{ doc, pv }` object. A `$form.<key>` reference resolves to the
live value of that form component (and is omitted from the overlay when the field is empty/unset).

### Runtime flow

```
Form field changes
  ↓
Formio wrapper (when auto-refresh is on):
  - root.on('change')  — debounced (default 1500ms, configurable)
  - root.element focusout (blur) — flushes immediately
  (when auto-refresh is off, only the initial paint + the Refresh button trigger it)
  ↓
computeInputOverrides(overrideMapping, formData)  [async]
  - evaluates the JSONata expression with $form = formData
    (legacy form:-ref objects are converted to JSONata first)
  - keeps only doc/pv scopes that resolved at least one field
  ↓
Dedup: skip if the computed overrides equal the last pushed value
  ↓
Pushes overrides to the Angular component's dedicated `inputOverrides` input
  (NOT Formio's value — Valtimo's bridge would let Formio reset that to its
   emptyValue on the next redraw, cancelling the preview)
  ↓
Task mode                              Start mode
POST …/preview                         POST …/preview/start
  {                                      {
    taskId,                                processDefinitionKey,
    sourceActivityId,                      sourceActivityId,
    inputOverrides: { doc, pv },           documentId?,   ← existing-case flavour only
    overrides                              inputOverrides: { doc, pv }
  }                                      }
  ↓                                      ↓
process instance + case document       process definition resolved from the key;
derived from the authorized task       no instance, case only if documentId given
  ↓                                      ↓
        PreviewService — one shared render path from here on
  - resolves the process link from the process DEFINITION
    → catalogId, templateId, dataMapping
  - $doc: OverlayMap(inputOverrides.doc, lazyDocumentContent),
          or the overrides alone when there is no document
  - $pv:  checks inputOverrides.pv first, then live process
          variables when an instance exists
  - evaluates the JSONata mapping with the overridden inputs
  - calls Epistola preview API → returns PDF
  ↓
PDF rendered in <object> tag
```

Note the request bodies carry **no case id**. In task mode it is derived from the task; in start
mode there either is no case, or the caller must separately hold read permission on the one it
names.

### OverlayMap — layered resolution

The `OverlayMap` checks overrides first and only delegates to the base map for non-overridden paths:

```
$doc.motivation  →  found in override  →  return override value
$doc.objector    →  not in override    →  fall through to LazyDocumentMap → load from DB
```

For nested access, when both overlay and base have a Map for the same key, a recursive `OverlayMap` is returned. This means the document is only loaded from the database when a non-overridden path is actually accessed.

### Design-time view

In the Formio builder (no runtime context), the component shows a configuration summary instead of an empty preview panel:

- Process definition key and activity ID
- The override mapping JSONata expression (legacy objects shown as their converted JSONata)

## Architecture

### Backend

| Class                 | File                                    | Role                                                                   |
| --------------------- | --------------------------------------- | ---------------------------------------------------------------------- |
| `PreviewService`      | `service/preview/PreviewService.java`   | Orchestrates preview generation with input overrides                   |
| `OverlayMap`          | `service/preview/OverlayMap.java`       | Layered Map — checks overlay first, delegates to base                  |
| `PreviewRequest`      | `web/rest/dto/PreviewRequest.java`      | Task-mode request DTO (`taskId` + overrides)                           |
| `StartPreviewRequest` | `web/rest/dto/StartPreviewRequest.java` | Start-mode request DTO (`processDefinitionKey`, optional `documentId`) |
| `PreviewContext`      | `service/preview/PreviewContext.java`   | Resolved, already-authorized inputs both modes converge on             |

### Frontend

| Component                              | File                                  | Role                                                                                                               |
| -------------------------------------- | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `EpistolaDocumentPreviewComponent`     | `epistola-document-preview/`          | Angular component — auto-discover and configured modes                                                             |
| `EpistolaProcessLinkSelectorComponent` | `process-link-selector/`              | Dropdown of generate-document process links                                                                        |
| `EpistolaOverrideBuilderComponent`     | `override-builder/`                   | Simple table + advanced JSONata editor (`$form`) for the override mapping                                          |
| Preview Formio registration            | `epistola-document-preview.formio.ts` | Extended Formio class with debounced change + blur listeners, value dedup, the auto-refresh toggle, and `editForm` |
| Override builder Formio registration   | `override-builder.formio.ts`          | Extended Formio class that extracts form fields from `options.editForm`                                            |
