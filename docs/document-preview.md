# Document Preview

The `epistola-document-preview` Formio component shows a live PDF preview of a document that will be generated later in the process. It supports two modes: auto-discover and configured.

**Related documentation:**

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

A table-based builder for mapping form fields to template input variables. Each row has:

| Column     | Description                                                                 |
| ---------- | --------------------------------------------------------------------------- |
| Scope      | `doc` (document content) or `pv` (process variable)                         |
| Input Path | The path in the input variable to override (e.g., `motivation`)             |
| Form Field | Dropdown of form fields by label — stores a `form:<componentKey>` reference |

Toggle **Advanced** mode to edit the mapping as raw JSON.

#### Example: objection decision preview

The assess-objection form has two fields (`pv:decision`, `pv:motivation`) and a preview component configured as:

```json
{
  "type": "epistola-document-preview",
  "key": "preview",
  "label": "Voorbeeld Besluitbrief",
  "processDefinitionKey": "objection-handling",
  "sourceActivityId": "generate-decision-gegrond",
  "overrideMapping": {
    "pv": {
      "motivation": "form:pv:motivation",
      "decision": "form:pv:decision"
    }
  }
}
```

When the user types in the Motivatie field, the preview regenerates after ~1.5 seconds with the new value.

## How it works

### Override mapping format

Values use a `form:` prefix to reference Formio components:

```
"form:<componentKey>"
```

The `form:` prefix identifies this as a form field reference. At runtime, the component key (e.g., `pv:motivation`) is used to read the current value from the form's data.

### Runtime flow

```
Form field changes
  ↓
Formio wrapper (root.on('change')) — debounced 1500ms
  ↓
computeInputOverrides(overrideMapping, formData)
  - strips form: prefix → gets component key
  - reads formData[componentKey] → gets current value
  - expands dot-notation paths into nested objects
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
- Override mapping entries (scope, path, form field reference)

## Architecture

### Backend

| Class            | File                                  | Role                                                  |
| ---------------- | ------------------------------------- | ----------------------------------------------------- |
| `PreviewService` | `service/preview/PreviewService.java` | Orchestrates preview generation with input overrides  |
| `OverlayMap`     | `service/preview/OverlayMap.java`     | Layered Map — checks overlay first, delegates to base |
| `PreviewRequest` | `web/rest/dto/PreviewRequest.java`    | Request DTO with `inputOverrides` field               |

### Frontend

| Component                              | File                                  | Role                                                                    |
| -------------------------------------- | ------------------------------------- | ----------------------------------------------------------------------- |
| `EpistolaDocumentPreviewComponent`     | `epistola-document-preview/`          | Angular component — auto-discover and configured modes                  |
| `EpistolaProcessLinkSelectorComponent` | `process-link-selector/`              | Dropdown of generate-document process links                             |
| `EpistolaOverrideBuilderComponent`     | `override-builder/`                   | Table builder + advanced JSON for override mapping                      |
| Preview Formio registration            | `epistola-document-preview.formio.ts` | Extended Formio class with `root.on('change')` listener and `editForm`  |
| Override builder Formio registration   | `override-builder.formio.ts`          | Extended Formio class that extracts form fields from `options.editForm` |
