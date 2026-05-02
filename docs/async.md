# Async Document Generation

## The Problem

Document generation via Epistola is asynchronous: you submit a request, get a `requestId`, and the document becomes available later. A naive approach is to add a timer loop inside each BPMN process that polls for completion:

```
[generate-document] → [timer: 30s] → [check-job-status] → [gateway: done?]
                                                               ├─ No → loop back to timer
                                                               └─ Yes → [download-document]
```

This doesn't scale. Each waiting process creates its own timer job in the engine, leading to O(n) timer firings per interval. With hundreds of concurrent generation jobs, this puts unnecessary load on the process engine.

## Recommended BPMN Pattern

Use a **Message Intermediate Catch Event** instead of a timer loop:

```
[Service Task: generate-document]
  → sets process variable: epistolaJobPath (epistola:job:{tenantId}/{requestId})
→ [Message Catch Event: "EpistolaDocumentGenerated"]
  → receives: epistolaStatus, epistolaDocumentId, epistolaErrorMessage
→ [Exclusive Gateway: epistolaStatus == "COMPLETED"?]
  ├─ Yes → [Service Task: download-document]
  └─ No  → [Error handling]
```

The process simply waits for a message. It doesn't know or care how that message gets delivered — whether by polling, webhook callback, or a future event stream. This separation of concerns keeps the BPMN clean and the notification mechanism swappable.

### Process Variables

| Variable               | Set By                     | Description                                                     |
| ---------------------- | -------------------------- | --------------------------------------------------------------- |
| `epistolaJobPath`      | `generate-document` action | Composite job identifier: `epistola:job:{tenantId}/{requestId}` |
| `epistolaStatus`       | Correlation service        | Job status: `COMPLETED`, `FAILED`, or `CANCELLED`               |
| `epistolaDocumentId`   | Correlation service        | Document ID when completed (null otherwise)                     |
| `epistolaErrorMessage` | Correlation service        | Error message when failed (null otherwise)                      |

The `generate-document` action also stores the raw request ID in a user-configured process variable (e.g., `epistolaRequestId`) via the `resultProcessVariable` parameter.

### Message Catch Event Configuration

In your BPMN model, add a Message Intermediate Catch Event with:

- **Message Name**: `EpistolaDocumentGenerated`

No additional configuration is needed. The `epistolaJobPath` variable is set automatically by the `generate-document` action.

## Architecture

```
BPMN Process:
  [generate-document] → [Message Catch: "EpistolaDocumentGenerated"] → [download-document]

Infrastructure:
  EpistolaCompletionEventConsumer (interface)
    └─ PollingCompletionEventConsumer (initial: batch poller)
    └─ (future: event API consumer, webhook consumer, etc.)
          │
          ▼
  EpistolaMessageCorrelationService
          │
          ▼
  RuntimeService.createMessageCorrelation() → wakes up waiting process
```

### EpistolaMessageCorrelationService

Both the poller and the callback endpoint delegate to this shared service for message correlation. This ensures consistent variable naming and correlation behavior:

```java
correlationService.correlateCompletion(tenantId, requestId, status, documentId, errorMessage);
```

The service builds the composite `epistolaJobPath` from `tenantId` and `requestId` for correlation. Internally, it uses `correlateAllWithResult()` which correlates all matching process instances (safe and idempotent).

### EpistolaCompletionEventConsumer

A simple lifecycle interface for consuming completion events:

```java
public interface EpistolaCompletionEventConsumer {
    void start();
    void stop();
}
```

Implementations decide how to discover completed jobs and when to trigger message correlation.

### EpistolaResultCollectorRunner (current implementation)

A single Spring bean that runs one `ResultCollector` per active Epistola plugin configuration. Each collector calls `POST /tenants/{tenantId}/generation/collect` and streams completed/failed results back as NDJSON, with sequence-based acknowledgment and partition-aware routing (see contract v0.3+ docs).

Per result, the runner:

1. Builds the composite job path `epistola:job:{tenantId}/{requestId}`.
2. Calls `EpistolaMessageCorrelationService.correlateCompletion(...)`, which uses `processInstanceVariableEquals` on `epistolaJobPath` to deliver the BPMN message to the matching execution.
3. Returns success so the collector advances its sequence cursor and never re-delivers the same result.

A scheduled reconcile loop (default every 60s) compares the running collectors against `pluginService.findPluginConfigurations(EpistolaPlugin.class, ...)` and starts/stops/restarts collectors as configurations are added, removed, or have their `baseUrl`/`apiKey`/`tenantId` changed. This mirrors the "enumerate plugin configs every cycle" pattern from the previous polling consumer, but now drives long-running streaming clients instead of per-request status polls.

## Multi-Tenant Routing

The runner naturally handles multi-tenant deployments: one `ResultCollector` per `(baseUrl, apiKey, tenantId)` configuration tuple. Each `generate-document` invocation looks up the right collector via the plugin's properties and asks it for a `routingKey` that targets that collector's currently-assigned partitions, so the result is guaranteed to land back here. If this Valtimo node dies, the suite reassigns its partitions to surviving consumers and they pick up the orphaned results.

## Configuration

```yaml
epistola:
  result-collector:
    enabled: true # Set to false to disable the collector entirely (default: true)
    batch-size: 100 # Max results per /generation/collect call (default: 100)
    min-interval-ms: 1000 # Lower bound on poll interval when busy (default: 1000)
    max-interval-ms: 30000 # Upper bound on poll interval when idle (default: 30000)
    reconcile-interval-ms: 60000 # How often to check for config drift (default: 60000)
```

## Future Evolution

The contract also defines self-signed-JWT and OAuth bearer auth as an alternative to `X-API-Key`. That migration (plus the corresponding consumer onboarding flow) is tracked as a separate change.
