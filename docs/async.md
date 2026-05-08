# Async Document Generation

> For the detailed client-side flow (collector lifecycle, routing keys,
> multi-instance behavior, backpressure, and BPMN subscription timing), see
> [result-collector.md](result-collector.md).

## The Problem

Document generation via Epistola is asynchronous: Valtimo submits a request,
gets a `requestId`, and the document becomes available later. A timer loop in
every BPMN process works functionally, but it creates one timer job per waiting
process:

```
[generate-document] -> [timer: 30s] -> [check-job-status] -> [gateway]
                                                           | no
                                                           v
                                                        [timer]
```

That pattern does not scale well. With many concurrent documents, the process
engine spends work repeatedly waking process instances just to ask whether a job
has finished.

## Recommended BPMN Pattern

Use a Message Intermediate Catch Event. The process submits the generation
request, waits for the Epistola completion message, then downloads the document
or handles the failure.

```
[Service Task: generate-document]
  -> sets epistolaJobPath and the configured resultProcessVariable
     (default name epistolaResult, value {requestId, status: "PENDING", ...})
-> [Message Catch Event: "EpistolaDocumentGenerated"]
  -> the result-collector has updated the rich object on the process instance
     before the message wakes the catch event
-> [Exclusive Gateway: epistolaResult.status == "COMPLETED"?]
  -> yes: [Service Task: download-document]
  -> no:  [failure handling]
```

The BPMN does not need to know how completion is delivered. The plugin's result
collector owns that infrastructure and correlates the message when Epistola
reports a terminal result.

## Process Variables

| Variable                                          | Set By                                 | Description                                                                                                                                                                                                                                                                                                                                                                         |
| ------------------------------------------------- | -------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `epistolaJobPath`                                 | `generate-document` action             | Composite job identifier: `epistola:job:{tenantId}/{requestId}` — internal correlation key.                                                                                                                                                                                                                                                                                         |
| `epistolaTenantId`                                | `generate-document` action             | Tenant id of the Epistola configuration that handled this request (handy for forms that build tenant-scoped URLs without parsing the composite jobPath).                                                                                                                                                                                                                            |
| `<resultProcessVariable>` (e.g. `epistolaResult`) | `generate-document` action + collector | **Single source of truth for the result.** Rich `Map` shape: `{requestId, status, documentId, errorMessage}`. Initial value at submit time is `status: "PENDING"` plus the requestId; the collector updates the same variable in-place with terminal data (`COMPLETED` / `FAILED` / `CANCELLED`). Read in BPMN with `${epistolaResult.status}` etc. (JUEL dot-notation on a `Map`). |

## Two patterns: catch event or variable

You don't have to use a catch event. Two patterns are supported, and they can
coexist in the same process:

### Catch-event pattern (block until completion)

Use when the process needs to wait for the result before continuing.

```
[Service Task: generate-document]   (resultProcessVariable: "epistolaResult")
-> [Message Catch Event: "EpistolaDocumentGenerated"]
-> [Exclusive Gateway: epistolaResult.status == "COMPLETED"?]
   -> yes: [Service Task: download-document]
   -> no:  [User Task: Corrigeer Documentgegevens]
```

The catch event blocks until the collector correlates the message; the
gateway downstream reads `${epistolaResult.status}`, `${epistolaResult.documentId}`,
or `${epistolaResult.errorMessage}` from the rich object that the collector
has already updated on the process-instance scope.

### Variable pattern (fire and forget; read later)

Use when the process doesn't need to block — for example, a Formio component
later in the process reads `${epistolaResult.documentId}` to render the PDF, or
a downstream gateway switches on `${epistolaResult.status == 'COMPLETED'}` only
once it actually reaches that gateway.

```
[Service Task: generate-document]   (resultProcessVariable: "epistolaResult")
-> [User Task: Other work...]
   ...
-> [User Task with Formio component reading ${epistolaResult.documentId}]
```

The plugin's result collector updates `epistolaResult` in place when the result
lands, regardless of where the process is. If the process has already ended,
the update is a no-op.

If the process happens to reach the user task **before** Epistola has finished,
the rich object is still set (with `status: "PENDING"`); the consumer should
handle that state explicitly. There is no catch-event-style block — the user
chose this pattern precisely to avoid it.

### Picking a pattern

- Block-on-completion → catch-event pattern.
- Async, read-later, no blocking → variable pattern.
- Both at once → keep the catch event for the gateway split AND read
  `epistolaResult` from a downstream Formio component. They don't conflict.

The validator (`GET /api/v1/plugin/epistola/admin/validations`) only fires for
the catch-event pattern. The variable pattern has no race exposure and isn't
validated.

## Message Catch Event

Configure the BPMN message catch event with:

- **Message name**: `EpistolaDocumentGenerated`

No listener or script task is needed. `generate-document` sets `epistolaJobPath`
automatically, and the correlation service uses that value to wake the matching
execution.

## Current Architecture

```
BPMN process
  [generate-document] -> [Message Catch: EpistolaDocumentGenerated]
          |
          v
Epistola API generation request
          |
          v
EpistolaResultCollectorRunner
  one ResultCollector per active plugin configuration
          |
          v
EpistolaMessageCorrelationService
          |
          v
RuntimeService message correlation
```

`EpistolaResultCollectorRunner` is a Spring singleton. It starts one contract
`ResultCollector` for each active Epistola plugin configuration. A collector
calls `POST /tenants/{tenantId}/generation/collect` and receives completed or
failed generation results as NDJSON with sequence acknowledgment.

For each result, the runner:

1. Builds `epistola:job:{tenantId}/{requestId}`.
2. Calls `EpistolaMessageCorrelationService.correlateCompletion(...)`.
3. Acknowledges the result only after correlation succeeds.

The runner reconciles collectors when Valtimo plugin configurations are
deployed or deleted and on the scheduled reconcile interval. Configuration
changes from the UI are therefore picked up without restarting the JVM.

## Multi-Node Routing

Each `generate-document` invocation asks the runner for a routing key for its
plugin configuration. When the local collector has already received its
partition assignment, the request is routed back to this Valtimo node. If the
collector is not ready yet, the request is submitted without a routing key and
Epistola uses its default routing.

If a Valtimo node stops, Epistola reassigns its result partitions to surviving
collectors. The remaining nodes continue collecting and correlating results.

## Configuration

```yaml
epistola:
  result-collector:
    enabled: true # collect async generation results automatically
    batch-size: 100 # max results per /generation/collect call
    min-interval-ms: 1000 # lower bound when results are flowing
    max-interval-ms: 30000 # upper bound when idle
    reconcile-interval-ms: 60000 # check plugin configuration drift
    kick-interval-ms: 3000 # wake an idle collector after submit
    backoff-multiplier: 3.0 # idle backoff multiplier
```

The `check-job-status` action still exists for explicit status checks, but the
recommended process model is generate -> message catch -> download.

## Race-safety: keep the boundary synchronous

The result-collector runs on its own thread and polls Epistola continuously.
When a result lands, it calls `runtimeService.createMessageCorrelation(...)
.processInstanceVariableEquals(epistolaJobPath, ...).correlateAllWithResult()`.
For that to find the waiting BPMN execution, **both** the `epistolaJobPath`
variable and the `EpistolaDocumentGenerated` subscription must already be
committed to the database.

The plugin sets the variable inside the `generate-document` action; the engine
creates the subscription when it advances to the catch event. Both happen in
the same Operaton command context — i.e. the same transaction that commits at
the end of the engine step. So as long as the boundary between the service
task and the catch event is **synchronous**, the variable, the subscription,
and the engine commit happen as one atomic operation, and the collector cannot
observe one without the other.

If you set `camunda:asyncAfter="true"` on the service task or
`camunda:asyncBefore="true"` on the catch event, Operaton splits that into two
transactions: the first commits with the variable set but **without** the
subscription, then a job-executor scan (default ~30s) starts a second
transaction that creates the subscription. During that window, a result
landing in Epistola triggers a correlation that finds the variable but no
subscription — `correlateAll` matches 0 executions, the result is acked, the
cursor advances, and the catch event waits forever.

**Don't put async boundaries between `generate-document` and the
`EpistolaDocumentGenerated` catch event.** The plugin enforces this at runtime:
`EpistolaProcessDefinitionValidator` scans every deployed process definition,
logs a WARN per violation, and surfaces them via `GET
/api/v1/plugin/epistola/admin/validations`. The admin page shows a banner
listing the offending activities.

If a stuck catch event slips through anyway, recover it manually from the
admin page's Pending Jobs tab — see [Recovering a stuck catch event](#recovering-a-stuck-catch-event).

## Recovering a stuck catch event

Open the Epistola admin page (`/epistola`), click the configuration card, and
switch to the **Pending Jobs** tab. Each row is a process instance whose catch
event is still subscribed to `EpistolaDocumentGenerated`. Click **Reconcile**
on the affected row:

1. The plugin reads the `epistolaJobPath` variable on the execution.
2. It calls Epistola's `GET /jobs/{requestId}` for the current status.
3. If the job is in a terminal state (`COMPLETED` / `FAILED` / `CANCELLED`),
   it runs the same correlation the result-collector would have run, so the
   process advances past the catch event with the right `epistolaStatus`,
   `epistolaDocumentId`, and `epistolaErrorMessage` variables set.
4. If the job is still `PENDING` / `IN_PROGRESS`, the button reports "Epistola:
   <status>. Try again in a moment." — there is nothing to correlate yet.

The endpoint is also reachable directly:

```
POST /api/v1/plugin/epistola/admin/pending/{executionId}/reconcile
```

Requires `EpistolaAdministration:MANAGE`. Returns 200 on success, 409 when the
job is still in flight, 400 / 404-equivalent on a malformed or unknown
execution id.
