# Epistola Process Variables

Every process variable the Epistola plugin reads or writes, what it's for, how the pieces relate, and
whether it's still needed. For the async/correlation flow specifically, see
[async.md](async.md); this is the complete reference across generate → correlate → download, plus the
polling pattern.

Two naming conventions appear below:

- **Fixed names** (e.g. `epistolaTenantId`) — constants in `EpistolaProcessVariables`, identical in
  every process.
- **Author-named** (e.g. `<resultProcessVariable>`) — names you choose in the process-link / action
  config; the plugin only knows them through that config.

## 1. The result variable — `<resultProcessVariable>`

The **single source of truth** for a generation. You name it per `generate-document` (e.g.
`epistolaResult`, or `requestId1` in the parallel subsidy demo). `generate-document` writes it
`PENDING` at submit; the result collector updates it **in place** on completion. It is a `Map`:

| Key (`RESULT_KEY_*`) | Meaning                                                          | Read by                                          | Needed |
| -------------------- | ---------------------------------------------------------------- | ------------------------------------------------ | ------ |
| `requestId`          | Epistola request id (UUID)                                       | `check-job-status` re-query; diagnostics         | ✅     |
| `status`             | `PENDING` / `IN_PROGRESS` / `COMPLETED` / `FAILED` / `CANCELLED` | downstream gateways `${rv.status}`               | ✅     |
| `documentId`         | Epistola PDF id (set on `COMPLETED`)                             | `download-document`, the download endpoint       | ✅     |
| `errorMessage`       | failure text (set on `FAILED`)                                   | failure-handling gateways, correction forms      | ✅     |
| `jobPath`            | composite `epistola:job:{tenant}/{requestId}`                    | pins the catch-event token via `${<rv>.jobPath}` | ✅     |

Read in BPMN with JUEL dot-notation: `${epistolaResult.status}`, `${epistolaResult.documentId}`, etc.

**A generation that cannot start raises an incident, and `FAILED` is a letter that did not
render.** When Epistola refuses the request itself — nothing accepted, no job: a template not in the
release, nothing deployed, a range nothing satisfies, an API key without access — or an expression
of the link fails or produces nothing, or the case's rich text cannot be converted, the step throws
and the engine raises an incident whose text says what is missing. Nothing is written to the result
variable. Fix the cause (the configuration, or the case's data) and retry the incident: the step runs
again with what was corrected. `status: FAILED` on the result variable is reserved for a job Epistola
accepted that then failed to render, delivered through the catch event
([ADR 0009](adr/0009-environment-and-release-as-expressions.md), third addendum).

**Mark the generate step `asyncBefore`** (_Asynchronous before_ in the modeler). The incident is
raised by the job that runs the step; without that job the refusal goes back to whoever started the
step — the person completing the previous task, or starting the case — who gets an error while
everything rolls back, and no incident is raised. Valtimo does not add the boundary for you. The admin
page's usage overview flags a generate step without it.

## 2. Correlation plumbing

| Variable                                                                                  | Scope                                                           | Purpose                                                                                                                                                                                                                                                                                                             | Needed |
| ----------------------------------------------------------------------------------------- | --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| `epistolaWaitFor`                                                                         | execution-local, on the `EpistolaDocumentGenerated` catch event | The jobPath this branch waits for. The collector matches it (`messageEventSubscriptionName + variableValueEquals`) to wake **exactly** that branch — topology-independent. Auto-pinned from `${<rv>.jobPath}` by the plugin's catch-event start listener; a process author can override (`camunda:inputParameter`). | ✅     |
| `<jobPath>` locator (variable **name** is the jobPath; value is the result-variable name) | process scope                                                   | The **reverse index**. A completion arrives as `(tenantId, requestId)` → jobPath; this lets the collector resolve _which_ result variable to update (and the process instance, for the no-catch-event "variable pattern").                                                                                          | ✅     |

> These are the two directions of one mapping: `${<rv>.jobPath}` → `epistolaWaitFor` is
> result-variable → jobPath (so the catch event self-identifies); the locator is jobPath →
> result-variable (so the collector resolves back). Both are needed because the two sides start from
> different keys.
>
> The locator is **removed** once a catch-event-pattern branch is woken with a terminal status, so it
> doesn't accumulate (one per generation) on long-lived/high-volume instances. Pure variable-pattern
> processes (no catch event) keep their locator — that pattern is typically low-volume, and removing it
> there would break the async-boundary self-heal that relies on it.

## 3. Standalone helpers

| Variable           | Set by              | Purpose                                                                                                                                                                                                   | Needed |
| ------------------ | ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| `epistolaTenantId` | `generate-document` | Tenant slug at process scope. The download endpoint reads it (default `tenantIdVariable`) to call Epistola; also handy for forms. Tenant is also embedded in `jobPath`, but a direct variable is cleaner. | ✅     |

## 4. Action-configured variables (author-named)

These are names you set in the action config, not fixed constants.

**`check-job-status`** — the polling alternative to the catch-event pattern:

| Property                                                         | Direction | Purpose                                                |
| ---------------------------------------------------------------- | --------- | ------------------------------------------------------ |
| `requestIdVariable`                                              | read      | where the request id lives (often `${<rv>.requestId}`) |
| `statusVariable` / `documentIdVariable` / `errorMessageVariable` | written   | status / document id / error written back              |

For catch-event-pattern processes these are redundant with the rich result map — only relevant if you
poll instead of using the `EpistolaDocumentGenerated` catch event.

**`download-document`**:

| Property             | Direction                                      | Purpose                                          |
| -------------------- | ---------------------------------------------- | ------------------------------------------------ |
| `documentVariable`   | read                                           | the result variable (→ `documentId`) to download |
| `resourceIdVariable` | written (`storageTarget = temporary-resource`) | temporary-resource id                            |
| `contentVariable`    | written (`storageTarget = process-variable`)   | raw PDF bytes                                    |

See [ADR 0001](adr/0001-download-document-content-storage.md) for `storageTarget`.

## Removed in the parallel-correlation redesign

These no longer exist (replaced by §1–§2 above):

- `epistolaJobPath` — the old single, process-scoped correlation key that parallel branches clobbered.
- `epistolaResultVariableName` — the old companion naming the result variable.
- `<activityId>_epistolaJobPath` — an intermediate (internal-API) attempt.

## Notes

- No variable is written-but-unread; the only overlaps are intentional (jobPath appears in the result
  map, as the locator's name, and overlaps `epistolaTenantId`) and each serves a distinct lookup
  direction or a cleaner direct read.
- Message name `EpistolaDocumentGenerated` and prefix `epistola:job:` are constants, not variables.
