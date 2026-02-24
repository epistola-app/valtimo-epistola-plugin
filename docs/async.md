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

| Variable | Set By | Description |
|----------|--------|-------------|
| `epistolaJobPath` | `generate-document` action | Composite job identifier: `epistola:job:{tenantId}/{requestId}` |
| `epistolaStatus` | Correlation service | Job status: `COMPLETED`, `FAILED`, or `CANCELLED` |
| `epistolaDocumentId` | Correlation service | Document ID when completed (null otherwise) |
| `epistolaErrorMessage` | Correlation service | Error message when failed (null otherwise) |

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

### PollingCompletionEventConsumer (Initial Implementation)

A `@Scheduled` service that polls Epistola for job completion:

1. Queries Operaton for all executions waiting on message `"EpistolaDocumentGenerated"`
2. Reads the `epistolaJobPath` variable from each execution and parses out `tenantId` and `requestId`
3. Groups by `tenantId` and loads the matching plugin configuration per tenant
4. Calls `getJobStatus()` per waiting job, delivers BPMN messages for completed/failed/cancelled jobs

This centralizes polling into one scheduled task instead of N timer loops in the engine.

## Multi-Tenant Routing

The poller needs to know which Epistola instance to query for each waiting process. The `generate-document` action stores a single composite `epistolaJobPath` variable (format: `epistola:job:{tenantId}/{requestId}`) that encodes both the tenant and request ID atomically.

The polling flow:
1. Query all waiting executions
2. Read `epistolaJobPath` from each execution, parse into `tenantId` + `requestId`
3. Load all Epistola plugin configurations (via `PluginService`)
4. Match by `tenantId` to find the correct API credentials
5. Call `getJobStatus()` with the matched plugin's `baseUrl` and `apiKey`

This supports multiple Epistola plugin configurations in the same Valtimo instance, each connecting to a different Epistola tenant.

## Configuration

```yaml
epistola:
  poller:
    enabled: true       # Set to false to disable polling (default: true)
    interval: 30000     # Milliseconds between poll cycles (default: 30000)
```

The poller is enabled by default. Disable it when using webhooks or a future event API for completion notifications.

## Callback Webhook (Alternative)

The callback endpoint at `POST /api/v1/plugin/epistola/callback/generation-complete` can also trigger message correlation. Configure Epistola to send a webhook when generation completes:

```json
{
  "tenantId": "...",
  "requestId": "...",
  "status": "COMPLETED",
  "documentId": "...",
  "errorMessage": null,
  "correlationId": "..."
}
```

Both the poller and the callback use the same `EpistolaMessageCorrelationService`, so they can coexist safely. The callback provides near-instant notification, while the poller acts as a safety net for missed callbacks.

## Future Evolution

When Epistola's event API becomes available:

1. Implement a new `EpistolaCompletionEventConsumer` (e.g., `EventApiCompletionEventConsumer`) that subscribes to the event stream
2. Register it as a Spring bean — it replaces the poller via `@ConditionalOnMissingBean`
3. Optionally keep the poller as a fallback with `epistola.poller.enabled=true`
4. **No BPMN changes needed** — same message name, same process variables

The key insight is that the BPMN process is decoupled from the notification mechanism. Swapping from polling to event-driven consumption is purely an infrastructure change.
