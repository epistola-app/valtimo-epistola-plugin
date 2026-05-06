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
  -> sets epistolaJobPath = epistola:job:{tenantId}/{requestId}
-> [Message Catch Event: "EpistolaDocumentGenerated"]
  -> receives epistolaStatus, epistolaDocumentId, epistolaErrorMessage
-> [Exclusive Gateway: epistolaStatus == "COMPLETED"?]
  -> yes: [Service Task: download-document]
  -> no:  [failure handling]
```

The BPMN does not need to know how completion is delivered. The plugin's result
collector owns that infrastructure and correlates the message when Epistola
reports a terminal result.

## Process Variables

| Variable               | Set By                     | Description                                                     |
| ---------------------- | -------------------------- | --------------------------------------------------------------- |
| `epistolaJobPath`      | `generate-document` action | Composite job identifier: `epistola:job:{tenantId}/{requestId}` |
| `epistolaStatus`       | Correlation service        | Job status: `COMPLETED`, `FAILED`, or `CANCELLED`               |
| `epistolaDocumentId`   | Correlation service        | Document ID when completed                                      |
| `epistolaErrorMessage` | Correlation service        | Error message when failed                                       |

The `generate-document` action also stores the raw request ID in the configured
`resultProcessVariable`, for example `epistolaRequestId`.

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
