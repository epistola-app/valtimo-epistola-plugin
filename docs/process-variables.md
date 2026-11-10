# Epistola Process Variables

Every process variable the Epistola plugin reads or writes, what it's for, how the pieces relate, and
whether it's still needed. For the async/correlation flow specifically, see
[async.md](async.md); this is the complete reference across generate → correlate → download, plus the
retry and polling patterns.

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
| `errorMessage`       | failure text (set on `FAILED`)                                   | failure-handling gateways / retry form           | ✅     |
| `jobPath`            | composite `epistola:job:{tenant}/{requestId}`                    | pins the catch-event token via `${<rv>.jobPath}` | ✅     |
| `errorReason`        | why a generation that could not start failed (see below)         | gateways and forms that switch on the reason     | —      |

Read in BPMN with JUEL dot-notation: `${epistolaResult.status}`, `${epistolaResult.documentId}`, etc.

**A generation that cannot start is `FAILED` at once.** When Epistola refuses the request itself —
nothing accepted, no job: a template not in the release, nothing deployed, a range nothing satisfies,
an API key without access — or the case's rich text cannot be converted, retrying changes nothing.
The step then completes with `status: FAILED`, an `errorMessage` naming what was asked for, and an
`errorReason`: `ENVIRONMENT_NOT_FOUND`, `CATALOG_NOT_FOUND`, `NOT_DEPLOYED`, `NOT_RELEASED`,
`RANGE_UNSATISFIED`, `TEMPLATE_NOT_IN_RELEASE`, `VARIANT_NOT_IN_RELEASE`, `NO_MATCHING_VARIANT`,
`UNKNOWN`, `REQUEST_REFUSED` (any other 4xx), `RICH_TEXT_NOT_CONVERTIBLE`, `EXPRESSION_EMPTY` (an
environment or release expression produced no value) or `EXPRESSION_FAILED` (an expression failed,
`$error` included, or produced something unusable). Its `requestId` starts
with `refused-`, because Epistola has no job for it; `check-job-status` reports such a request
`FAILED` without asking. The catch event after the step self-heals on the already-terminal result, so
the process takes the path it has for a failed letter. Timeouts, 5xx, 408 and 429 still fail the step
so the engine retries it ([ADR 0009](adr/0009-environment-and-release-as-expressions.md), addendum).

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

| Variable                   | Set by                                                   | Purpose                                                                                                                                                                                                   | Needed     |
| -------------------------- | -------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- |
| `epistolaTenantId`         | `generate-document`                                      | Tenant slug at process scope. The download endpoint reads it (default `tenantIdVariable`) to call Epistola; also handy for forms. Tenant is also embedded in `jobPath`, but a direct variable is cleaner. | ✅         |
| `epistolaEditedData`       | retry form → `generate-document`                         | JSON of user-edited data from the retry form; consumed and cleared by `generate-document` on a retry pass.                                                                                                | ✅ (retry) |
| `epistolaSourceActivityId` | BPMN `camunda:inputParameter` on the retry **user task** | Names the source `generate-document` activity so the retry form can resolve the original action's config.                                                                                                 | ✅ (retry) |

> `epistolaSourceActivityId` does for the retry form what `EpistolaCatchEventLinkResolver` does
> automatically for catch events (pair a catch event/task to its `generate-document`). The retry task
> needs it explicitly because there's no auto-pairing hook there.

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
