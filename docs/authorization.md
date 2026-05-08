# Authorization

This guide describes how the Epistola plugin authorizes access to its REST endpoints, what defaults ship out of the box, and how to override them per environment.

**Audience:** operators, security reviewers, and integrators.

## Three-layer model

The plugin separates authorization into three concerns:

```
HTTP layer (Spring Security)
  ├─ ROLE_ADMIN — configurator endpoints
  └─ authenticated — everything else
        ↓
Controller PBAC layer (Valtimo AuthorizationService)
  ├─ User-task endpoints — OperatonTask:VIEW + same-process + same-case binding
  └─ Admin endpoints    — EpistolaAdministration:MANAGE
```

The HTTP layer is a coarse gate. The PBAC layer is where the real authorization decisions happen — the gate filters out anonymous traffic so the controllers can focus on user-vs-resource decisions.

> **BPMN actions are out of scope.** `@PluginAction` methods (`generate-document`, `check-job-status`, `download-document`) execute in the BPMN engine's transactional context with the engine identity, not a user identity. Process-level authorization on the BPMN process itself governs those.

## Endpoint matrix

| Endpoint                                                   | HTTP gate     | PBAC check                                                                          |
| ---------------------------------------------------------- | ------------- | ----------------------------------------------------------------------------------- |
| `POST /api/v1/plugin/epistola/preview`                     | authenticated | `OperatonTask:VIEW` on `taskId` + same-process + same-case binding                  |
| `GET /api/v1/plugin/epistola/retry-form`                   | authenticated | `OperatonTask:VIEW` on `taskId` + same-process + same-case binding                  |
| `GET /api/v1/plugin/epistola/documents/download`           | authenticated | `OperatonTask:VIEW` on `taskId` + same-case binding (process-id resolved from task) |
| `GET /api/v1/plugin/epistola/admin/health`                 | authenticated | `EpistolaAdministration:MANAGE`                                                     |
| `GET /api/v1/plugin/epistola/admin/versions`               | authenticated | `EpistolaAdministration:MANAGE`                                                     |
| `GET /api/v1/plugin/epistola/admin/usage`                  | authenticated | `EpistolaAdministration:MANAGE`                                                     |
| `GET /api/v1/plugin/epistola/admin/pending`                | authenticated | `EpistolaAdministration:MANAGE`                                                     |
| `GET /api/v1/plugin/epistola/admin/export/{processLinkId}` | authenticated | `EpistolaAdministration:MANAGE`                                                     |
| `/api/v1/plugin/epistola/configurations/**`                | `ROLE_ADMIN`  | —                                                                                   |
| `/api/v1/plugin/epistola/process-variables`                | `ROLE_ADMIN`  | —                                                                                   |
| `/api/v1/plugin/epistola/variable-suggestions`             | `ROLE_ADMIN`  | —                                                                                   |
| `/api/v1/plugin/epistola/expression-functions`             | `ROLE_ADMIN`  | —                                                                                   |
| `/api/v1/plugin/epistola/validate-jsonata`                 | `ROLE_ADMIN`  | —                                                                                   |
| `/api/v1/plugin/epistola/evaluate-mapping`                 | `ROLE_ADMIN`  | —                                                                                   |

Configuration:

- HTTP gate: `app.epistola.valtimo.config.EpistolaHttpSecurityConfigurer` (`@Order(270)`)
- Controller checks: `EpistolaGenerationResource.requireTaskBoundTo(...)` and `EpistolaAdminResource.requirePermission(...)`

## User-task endpoints

`/preview`, `/retry-form`, and `/documents/download` are designed to be called from inside a user task form. They share a single helper, `requireTaskBoundTo(taskId, processInstanceId, documentId)`, which enforces all of:

1. The current principal has `OperatonTask:VIEW` permission on `taskId`.
2. `task.processInstanceId == request.processInstanceId` (where supplied).
3. `task.processInstance.businessKey == request.documentId`.

Any mismatch throws `AccessDeniedException` → HTTP 403.

Why all three? Because PBAC alone is not enough. A user with `OperatonTask:VIEW` on task A could otherwise pass task A's id alongside another case's `documentId` and trick the backend into operating on case B. The same-case binding (via Valtimo's convention of writing the case document UUID as the BPMN process business key) closes that gap.

### `/documents/download`

```
GET /api/v1/plugin/epistola/documents/download
    ?taskId=<operaton-task-uuid>
    &caseDocumentId=<valtimo-case-uuid>
    &documentIdVariable=epistolaDocumentId
    &tenantIdVariable=epistolaTenantId
    &disposition=inline                      (or "attachment")
    &filename=<download-filename>
```

The wire **never carries a raw Epistola PDF id**. Instead, the caller names the process variables on their task that hold the PDF id and tenant id; the controller looks them up via `RuntimeService.getVariable(processInstanceId, …)`. This makes the endpoint forge-proof: a user cannot supply somebody else's `documentId` because they don't write the variable, the upstream `generate-document` action does.

If Epistola returns 404 for the resolved id (typical when the variable holds a stale id from a previous run), the controller translates it to a controller-level 404, so the UI can render "Document is nog niet gegenereerd" instead of a generic 500.

## Admin endpoints

`EpistolaAdministration` is a plugin-defined PBAC resource type:

```
package: app.epistola.valtimo.authorization
class:   EpistolaAdministration         (marker)
action:  MANAGE                         (singleton)
```

Every admin controller method calls:

```java
authorizationService.requirePermission(
    new EntityAuthorizationRequest<>(
        EpistolaAdministration.class,
        EpistolaAdministrationActionProvider.MANAGE));
```

There is no per-entity granularity — `EpistolaAdministration` is a marker resource, so the check is purely "does this principal hold the `MANAGE` action on the resource type".

### Default grant

The plugin ships a default permission changeset that grants `EpistolaAdministration:manage` to `ROLE_ADMIN`:

```json
{
  "changesetId": "epistola-pbac-admin-default-1",
  "permissions": [
    {
      "resourceType": "app.epistola.valtimo.authorization.EpistolaAdministration",
      "actions": ["manage"],
      "roleKey": "ROLE_ADMIN"
    }
  ]
}
```

Source: `backend/plugin/src/main/resources/config/epistola/permission/epistola-admin-default.permission.json`. It's deployed by Valtimo's `PermissionDeployer` on boot (the same deployer that handles your application's own `*.permission.json` files).

### Restricting admin access more tightly

To restrict who can see the admin page (e.g. a dedicated `ROLE_EPISTOLA_OPS` role):

1. **Decide whether `ROLE_ADMIN` should retain access.** Valtimo's `PermissionDeployer` is changeset-based; the exact rules for revoking or replacing an already-deployed permission depend on your Valtimo version. Consult your Valtimo distribution's permission-deployment docs. In many environments the simpler path is to leave the default in place and rely on the fact that `ROLE_ADMIN` is already a privileged role anyway.
2. **Grant `EpistolaAdministration:manage` to your role** in your application's `*.permission.json`:

   ```json
   {
     "changesetId": "myapp-epistola-admin",
     "permissions": [
       {
         "resourceType": "app.epistola.valtimo.authorization.EpistolaAdministration",
         "actions": ["manage"],
         "roleKey": "ROLE_EPISTOLA_OPS"
       }
     ]
   }
   ```

3. **Make sure that role exists in Keycloak** and is mapped to whichever realm/group your operators belong to.

### Replacing the resource type entirely

If the marker resource pattern doesn't fit your authorization model, the bean is `@ConditionalOnMissingBean` — define your own `EpistolaAdministrationActionProvider` and `EpistolaAdministrationSpecificationFactory` beans in your application's auto-configuration and they will replace the plugin defaults at boot.

## Configurator endpoints

The configurator endpoints (`/configurations/**`, `/process-variables`, `/variable-suggestions`, `/expression-functions`, `/validate-jsonata`, `/evaluate-mapping`) are gated at the HTTP layer with `ROLE_ADMIN`. They power the process-link configuration UI in the Valtimo console.

There is no `ProcessLink` PBAC action in Valtimo 13.21, so `ROLE_ADMIN` is the de-facto "process-link author" authority — anyone who can already author process links in Valtimo today necessarily has `ROLE_ADMIN`. Adding a finer-grained PBAC layer here is a future enhancement gated on Valtimo upstream introducing such an action.

## Frontend implications

The `epistola-document` and `epistola-document-preview` Formio components rely on knowing the active `taskInstanceId` so they can construct correctly-bound calls. Because Angular Elements bootstrap their own injector tree, the components cannot reach Valtimo's `TaskDetailContentComponent` via DI. Instead, the plugin module registers a global HTTP interceptor (`EpistolaTaskContextInterceptor`) that watches for Valtimo's task-open call:

```
GET /api/v2/process-link/task/{taskId}
```

When that URL is observed, the interceptor publishes the captured `taskId` on a root-provided `EpistolaTaskContextService`. Components subscribe to that service.

Outside a user task context (Formio builder, design mode), the components fail closed — they show a configuration summary or an inline error rather than attempting an unauthorized call.

See [Document Component](document-component.md) and [Document Preview](document-preview.md) for the component-level details.

## Disabling authorization checks for testing

There is no flag to disable the PBAC checks. To run integration tests against the plugin, mock `AuthorizationService` to grant the required permissions, or run Valtimo's full security stack with a test user that has the required roles. The plugin's own backend test suite uses the contract mock server with a fixed admin principal (see `backend/plugin/src/test/`).

## Verification checklist

After deploying a plugin upgrade or changing your permission JSON:

- [ ] An admin user can open the Epistola admin page (`/epistola/admin`) and see health/usage data.
- [ ] A non-admin user gets a 403 from `/api/v1/plugin/epistola/admin/health` directly.
- [ ] A user with no access to a case cannot download the case's PDF by guessing its `caseDocumentId`.
- [ ] A user with access to task A in case A cannot pass task A's id alongside case B's `caseDocumentId` to the download endpoint.
- [ ] Process-link configuration in the Valtimo console still works for `ROLE_ADMIN` users.
- [ ] Stale `epistolaDocumentId` values produce a "Document is nog niet gegenereerd" message rather than a 500.
