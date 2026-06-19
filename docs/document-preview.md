# Document Preview

The `epistola-document-preview` Formio component shows a live PDF preview of a document that will be generated later in the process. It supports two modes: auto-discover and configured.

> If you want to render or download a PDF that has **already been generated** (driven by process variables on the caller's task), use [`epistola-document`](document-component.md) instead.

**Related documentation:**

- [Document Component](document-component.md) — the after-generation render/download component
- [Data Mapping](data-mapping.md) — how case/process data flows into Epistola templates
- [Use Cases](use-cases.md) — demo scenarios including the bezwaarprocedure

## Modes

### Auto-discover mode

When no process link is configured, the component discovers all `generate-document` process links from running process instances and shows a source dropdown. This is the original behavior — useful when a process is already past the generation point and you want to preview any available document.

### Configured mode

When a process link and override mapping are configured, the component targets a specific `generate-document` activity and feeds form field values into the template as input overrides. This enables previewing a document **while the user is still filling in the form**.

## Configuration

In the Formio builder, click the `epistola-document-preview` component to open its settings. The editForm shows:

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

Controls how the preview reacts while the form is being filled in:

- **Auto-refresh preview as the form is filled in** (checkbox, default **on**) — when on, the preview
  refreshes automatically as the form changes. It does **not** refresh on every keystroke: changes are
  debounced and the preview flushes when a field loses focus (blur). It also only re-renders when the
  mapped data actually changed, so typing in fields the mapping doesn't read no longer triggers a
  refresh. Turn it **off** to refresh only via the **Refresh** button (the preview still paints once
  when the form opens).
- **Auto-refresh debounce (ms)** (number, default **1500**) — how long to wait after the last change
  before refreshing. Higher values feel calmer; lower values feel more responsive. Shown only when
  auto-refresh is on. Invalid/negative values fall back to 1500.

The **Refresh** button in the preview header always works regardless of these settings.

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
Sets value on Angular component
  ↓
POST /api/v1/plugin/epistola/preview
  {
    documentId, processDefinitionKey, sourceActivityId,
    inputOverrides: { doc: {...}, pv: {...} }
  }
  ↓
Backend: PreviewService.generatePreview()
  - resolves process link → gets catalogId, templateId, dataMapping
  - creates OverlayMap(inputOverrides.doc, lazyDocumentContent) for $doc
  - creates process-variable context that checks inputOverrides.pv first
  - evaluates JSONata mapping with overridden inputs
  - calls Epistola preview API → returns PDF
  ↓
PDF rendered in <object> tag
```

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

| Class            | File                                  | Role                                                  |
| ---------------- | ------------------------------------- | ----------------------------------------------------- |
| `PreviewService` | `service/preview/PreviewService.java` | Orchestrates preview generation with input overrides  |
| `OverlayMap`     | `service/preview/OverlayMap.java`     | Layered Map — checks overlay first, delegates to base |
| `PreviewRequest` | `web/rest/dto/PreviewRequest.java`    | Request DTO with `inputOverrides` field               |

### Frontend

| Component                              | File                                  | Role                                                                                                               |
| -------------------------------------- | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `EpistolaDocumentPreviewComponent`     | `epistola-document-preview/`          | Angular component — auto-discover and configured modes                                                             |
| `EpistolaProcessLinkSelectorComponent` | `process-link-selector/`              | Dropdown of generate-document process links                                                                        |
| `EpistolaOverrideBuilderComponent`     | `override-builder/`                   | Simple table + advanced JSONata editor (`$form`) for the override mapping                                          |
| Preview Formio registration            | `epistola-document-preview.formio.ts` | Extended Formio class with debounced change + blur listeners, value dedup, the auto-refresh toggle, and `editForm` |
| Override builder Formio registration   | `override-builder.formio.ts`          | Extended Formio class that extracts form fields from `options.editForm`                                            |
