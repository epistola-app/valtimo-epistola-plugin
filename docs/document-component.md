# Document Component

The `epistola-document` Formio component is the post-generation UX for an Epistola PDF inside a user task form: render the PDF inline, expose a download button, or both. It pulls the actual document over the authorized `/documents/download` endpoint, so callers cannot forge access to PDFs that don't belong to the current task.

> If you want a **dry-run preview** of a document that has not been generated yet (driven by form-field overrides), use [`epistola-document-preview`](document-preview.md) instead.

**Related documentation:**

- [Authorization](authorization.md) — how the backend enforces task-bound access on `/documents/download`
- [Document Preview](document-preview.md) — the live, before-generation preview component
- [Async Document Generation](async.md) — how `epistolaDocumentId` lands in process variables

## When to use it

Add it to the form of any user task that follows a successful `generate-document` action and whose review/approval/download UX needs the resulting PDF. The component shows nothing until the PDF id has been written back to a process variable on the same process instance.

## Configuration

```json
{
  "type": "epistola-document",
  "key": "document",
  "label": "Bevestigingsbrief",
  "display": "both",
  "documentIdVariable": "epistolaDocumentId",
  "tenantIdVariable": "epistolaTenantId",
  "filename": "bevestigingsbrief.pdf"
}
```

| Property             | Required | Default                | Description                                                                                                                                                                                      |
| -------------------- | -------- | ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `display`            | no       | `"both"`               | `"inline"` renders the PDF in an embedded panel only. `"button"` renders a click-to-save download button only. `"both"` renders the inline panel with a download icon in the header.             |
| `documentIdVariable` | no       | `"epistolaDocumentId"` | Name of the **process variable** holding the Epistola PDF id (UUIDv7). Must match the variable name written by the upstream `generate-document` action — by default that's `epistolaDocumentId`. |
| `tenantIdVariable`   | no       | `"epistolaTenantId"`   | Name of the **process variable** holding the Epistola tenant id (slug). Must match the variable name written by the upstream `generate-document` action — by default that's `epistolaTenantId`.  |
| `filename`           | no       | `"document.pdf"`       | Filename used for the download `Content-Disposition`. Cosmetic — affects what the user's browser saves the file as.                                                                              |
| `label`              | no       | `"Document"`           | Header text shown above the panel and on the button.                                                                                                                                             |

The component **does not** take a raw `documentId` or `tenantId` value. The backend resolves both by reading the named process variables off the caller's task. This is by design — it makes the endpoint forge-proof (see [Authorization](authorization.md#documentsdownload)).

## How it works

### Runtime flow

```
User opens a task form containing <epistola-document>
  ↓
EpistolaTaskContextInterceptor sniffs the task-open URL
  GET /api/v2/process-link/task/{taskId}
  → publishes taskId on EpistolaTaskContextService
  ↓
Component reads:
  - taskId         ← EpistolaTaskContextService
  - caseDocumentId ← FormIoStateService.documentId
  ↓
GET /api/v1/plugin/epistola/documents/download
    ?taskId=…
    &caseDocumentId=…
    &documentIdVariable=epistolaDocumentId
    &tenantIdVariable=epistolaTenantId
    &disposition=inline   (or "attachment" for the download button)
    &filename=…
  ↓
Backend (EpistolaGenerationResource):
  1. requireTaskBoundTo(taskId, processInstanceId, caseDocumentId)
       → OperatonTask:VIEW + same-process-instance + business-key match
  2. runtimeService.getVariable(processInstanceId, documentIdVariable)
  3. runtimeService.getVariable(processInstanceId, tenantIdVariable)
  4. epistolaService.downloadDocument(tenantId, documentId)
  ↓
PDF blob → either inline <object> render or anchor.click() download
```

The interceptor is registered globally via the plugin module and is the only way the component knows the active `taskInstanceId` — Angular Elements bootstrap their own injector tree, so direct DI lookup of Valtimo's task component is not possible from inside a custom Formio component.

### Display modes side-by-side

| Mode     | Inline `<object>` PDF | Download button | Refresh button | Typical use case                                                     |
| -------- | --------------------- | --------------- | -------------- | -------------------------------------------------------------------- |
| `inline` | yes                   | no              | yes            | Read-only review forms where the user shouldn't be able to download. |
| `button` | no                    | yes             | no             | Confirmation/wrap-up steps where the inline PDF would distract.      |
| `both`   | yes                   | yes (icon)      | yes            | Standard "review-then-decide" task forms. **Default.**               |

### Design-mode behaviour

In the Formio builder there is no `caseDocumentId`, so the component renders a configuration summary instead of trying to fetch a non-existent document — `display`, `documentIdVariable`, and `tenantIdVariable` are shown as labelled values. This means form authors can drop the component on the canvas without seeing an error toast.

### Failure modes the user sees

| Scenario                                                       | UI                                                                          |
| -------------------------------------------------------------- | --------------------------------------------------------------------------- |
| `epistolaDocumentId` not yet set on the process instance       | "Document is nog niet gegenereerd."                                         |
| Epistola server returns 404 for the stored id (stale variable) | "Document is nog niet gegenereerd." (404 → 404 translation)                 |
| Component is mounted outside a user task (no `taskId`)         | "Document is alleen beschikbaar binnen een taak."                           |
| Network/server error                                           | "Document kon niet geladen worden." (inline) / "Download mislukt." (button) |

## Migration from earlier versions

Pre-0.8 the post-generation UX was split across two components:

| Pre-0.8 component         | Replaced by                                                |
| ------------------------- | ---------------------------------------------------------- |
| `epistola-preview-button` | `epistola-document` with `display: "button"` (or `"both"`) |
| `epistola-download`       | `epistola-document` with `display: "button"`               |

The 0.8 endpoint reshape also matters: the old `GET /api/v1/plugin/epistola/documents/{documentId}/download` (path-param) is gone. The new shape is `GET /api/v1/plugin/epistola/documents/download?taskId=…&caseDocumentId=…&documentIdVariable=…&tenantIdVariable=…`. If you have integrations that called the old URL directly (outside the Formio component), they have to be updated.

## Architecture

### Frontend

| Class                            | File                                               | Role                                                                  |
| -------------------------------- | -------------------------------------------------- | --------------------------------------------------------------------- |
| `EpistolaDocumentComponent`      | `epistola-document/epistola-document.component.ts` | Angular component — one of three display modes                        |
| `epistola-document.formio.ts`    | `epistola-document/epistola-document.formio.ts`    | Formio registration + `editForm`                                      |
| `EpistolaTaskContextService`     | `services/epistola-task-context.service.ts`        | `BehaviorSubject<string \| null>` holding the active `taskInstanceId` |
| `EpistolaTaskContextInterceptor` | `services/epistola-task-context.interceptor.ts`    | Sniffs `/api/v2/process-link/task/{taskId}`, populates the service    |
| `EpistolaPluginService`          | `services/epistola-plugin.service.ts`              | `downloadDocumentBlob(...)` — the single backend entry point          |

### Backend

| Class                        | File                                       | Role                                                                   |
| ---------------------------- | ------------------------------------------ | ---------------------------------------------------------------------- |
| `EpistolaGenerationResource` | `web/rest/EpistolaGenerationResource.java` | `GET /documents/download` — `requireTaskBoundTo` + variable lookup     |
| `EpistolaService`            | `service/EpistolaServiceImpl.java`         | `downloadDocument(tenantId, documentId)` — calls Epistola contract API |
