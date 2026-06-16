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
Server-side form prefill fills the hidden carrier field
  (properties.sourceKey: "epistola:taskId" → EpistolaTaskValueResolverFactory)
  → form data carries the active taskId, in every task-open flow
  ↓
Component reads:
  - taskId         ← prefilled form (readPrefilledTaskId)
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

Angular Elements bootstrap their own injector tree, so a custom Formio component cannot look up Valtimo's task component via DI, and Valtimo exposes no service carrying the task id to a form at runtime. The component therefore learns the active `taskInstanceId` from **server-side form prefill**, which works in every task-open flow — see [Task-id carrier](#task-id-carrier) and [Authorization → Frontend implications](authorization.md#frontend-implications).

### Task-id carrier

No extra form setup is needed: each Epistola task component ships a hidden **carrier child** in its own schema (defined once in `prefilled-task-id.ts` as `PREFILLED_TASK_ID_CARRIER`), so dropping the component is enough — there is no separate field for the author to add. The carrier looks like:

```json
{
  "type": "hidden",
  "key": "epistolaTaskId",
  "input": true,
  "persistent": false,
  "properties": { "sourceKey": "epistola:taskId" }
}
```

Valtimo's server-side prefill recurses into the component's nested `components` and fills this child's `defaultValue` with the current task id (via the `epistola:` value resolver) — in both the direct task-open flow and the task-list/case-detail flow. The Formio wrapper reads it back with `readPrefilledTaskId` (which deep-scans the prefilled form definition) and forwards it to the Angular component. `persistent: false` keeps the value out of the submission, so the task id never lands in the case document.

Forms authored **before** the carrier was embedded don't have it. The admin page's **Forms** tab (transitional, removed in 1.0.0) lists those forms and offers a per-form / "repair all" button that injects the carrier (`EpistolaFormCarrierRepairService`). Repair sticks for form-management-authored forms; classpath-deployed forms are reconciled to their source on the next boot (shown as "read-only"), so for those add the carrier to the source — re-drop the component, which ships the embedded carrier. If the carrier is absent at runtime, the component fails closed.

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

| Class                         | File                                               | Role                                                         |
| ----------------------------- | -------------------------------------------------- | ------------------------------------------------------------ |
| `EpistolaDocumentComponent`   | `epistola-document/epistola-document.component.ts` | Angular component — one of three display modes               |
| `epistola-document.formio.ts` | `epistola-document/epistola-document.formio.ts`    | Formio registration + `editForm` + embedded task-id carrier  |
| `readPrefilledTaskId`         | `services/prefilled-task-id.ts`                    | Reads the task id from the server-prefilled carrier field    |
| `EpistolaPluginService`       | `services/epistola-plugin.service.ts`              | `downloadDocumentBlob(...)` — the single backend entry point |

### Backend

| Class                        | File                                       | Role                                                                   |
| ---------------------------- | ------------------------------------------ | ---------------------------------------------------------------------- |
| `EpistolaGenerationResource` | `web/rest/EpistolaGenerationResource.java` | `GET /documents/download` — `requireTaskBoundTo` + variable lookup     |
| `EpistolaService`            | `service/EpistolaServiceImpl.java`         | `downloadDocument(tenantId, documentId)` — calls Epistola contract API |
