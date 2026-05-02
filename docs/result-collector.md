# Result Collector — client-side flow

Detailed walk-through of how the Valtimo plugin streams generation results from
Epistola via the v0.3+ `POST /tenants/{tenantId}/generation/collect` endpoint and
correlates them back to waiting BPMN processes.

For the high-level architecture and BPMN authoring guidance, see
[async.md](async.md). This document focuses on what happens _inside_ the plugin
when the JVM starts, when a job is submitted, and when a result arrives.

## Components

| Class                               | Role                                                                                       |
| ----------------------------------- | ------------------------------------------------------------------------------------------ |
| `EpistolaPluginAutoConfiguration`   | Wires the singletons.                                                                      |
| `EpistolaApiClientFactory`          | Builds Spring `RestClient`s with `X-API-Key` + `ClientIdentity` interceptor.               |
| `EpistolaResultCollectorRunner`     | The single managed bean — owns all running collectors and the reconcile loop.              |
| `ResultCollector` (contract helper) | One per active plugin configuration; runs the actual NDJSON poll loop on a virtual thread. |
| `EpistolaPlugin#generateDocument`   | Plugin action invoked by BPMN service tasks; submits the generation.                       |
| `EpistolaMessageCorrelationService` | Translates a result into a BPMN message correlation.                                       |

## Bean wiring

`EpistolaPluginAutoConfiguration` is a `@AutoConfiguration` gated on
`epistola.enabled=true` (default). It creates one
`EpistolaResultCollectorRunner` bean as a Spring singleton. The runner is also
passed into `EpistolaPluginFactory`, which means every `EpistolaPlugin` instance
Valtimo materializes for an action invocation gets a reference to the same
runner.

## Startup (single instance)

```
@PostConstruct start()
  └─ if !epistola.result-collector.enabled → return
  └─ reconcile()                                   ← also triggered by:
                                                       • @Scheduled every 60s (safety net)
                                                       • PluginsDeployedEvent (create/update)
                                                       • PluginConfigurationDeletedEvent (delete)

reconcile()                                         ← synchronized (safe across triggers)
  ├─ active = pluginService.findPluginConfigurations(EpistolaPlugin.class, _ → true)
  │           // {pluginConfigurationId → EpistolaPlugin instance with baseUrl/apiKey/tenantId}
  ├─ for configId in collectors.keySet() − active.keySet():
  │     stopCollector(configId)                    ← config was deleted in the UI
  └─ for (configId, plugin) in active:
        existing = collectors.get(configId)
        if existing == null:           startCollector(configId, plugin)
        else if signatureChanged():    stopCollector(configId); startCollector(configId, plugin)
        else:                          (already running, no-op)
```

`startCollector(...)`:

1. `apiClientFactory.createRestClient(baseUrl, apiKey)` — produces a `RestClient`
   pre-wired with the `X-API-Key` header and the `ClientIdentity` interceptor
   (`User-Agent: epistola-contract/<ver> valtimo-epistola-plugin/<ver>` and
   `X-EP-Node-Id`).
2. Build a `ResultCollector` via the contract helper's builder. Pass our
   `handleResult` callback as the per-result handler. Disable the contract's
   built-in JVM shutdown hook — the runner manages lifecycle itself.
3. `Thread.ofVirtual().start(collector::start)` — that virtual thread now sits
   inside the contract helper's poll loop forever (until we tell it to stop).

Result: one virtual thread per `(baseUrl, apiKey, tenantId)` tuple, each parked
inside `ResultCollector.start()`.

## Inside the contract's `ResultCollector.start()`

The contract helper, not our code, but it's important to know what's happening:

```
loop while running:
  collectOnce():
    POST /tenants/{tenantId}/generation/collect
      body: {"acknowledgeUpTo": <local cursor>, "limit": 100}   // null on first call

    response: NDJSON, line-by-line streaming, gzip-decoded:
      {"sequence": N,   "requestId": "…", "status": "COMPLETED", "documentId": "…", …}
      {"sequence": N+1, "status": "FAILED", "error": "…", …}
      …
      {"_meta": true, "hasMore": false, "lastSequence": N+1,
       "partitions": {"total": 16, "mine": [3, 7, 11], "hash": "murmur3"}}

    for each non-meta line: handler(parsed)         ← our lambda → handleResult(...)
    on _meta: partitionAssignment = {…}             ← stored on the collector instance

    cursor = lastSequenceInBatch                    ← local field, sent on next call

  sleep: 0ms if hasMore, 1s if got some, exp-backoff up to 30s if empty
```

The cursor (`lastAcknowledgedSequence`) lives **only in memory**. We never persist
it ourselves; the suite is responsible for redelivering anything we never acked
(see [Crash recovery](#crash-recovery) below).

## Submitting a job

The plugin action runs on whatever thread Operaton uses to execute the service
task — completely separate from any collector virtual thread.

```
EpistolaPlugin#generateDocument(execution, …):
  // resolve template data, filename, variant attributes via JSONata

  baseRoutingKey = correlationId ?? UUID.randomUUID().toString()
  routingKey = resultCollectorRunner.routingKeyFor(baseUrl, apiKey, tenantId, baseRoutingKey)
  // routingKeyFor:
  //   for managed in collectors.values():
  //     if managed.matches(baseUrl, apiKey, tenantId):
  //       return managed.collector.routingKeyToMe(baseRoutingKey)
  //         // contract helper:
  //         // - null if first poll hasn't completed (no partitionAssignment yet)
  //         // - baseRoutingKey itself if it already hashes to one of mine
  //         // - "<myPartition>:<baseRoutingKey>" otherwise
  //   return null  // no matching collector → cold start or misconfiguration

  result = epistolaService.submitGenerationJob(…, routingKey)
              ↓
              POST /tenants/{t}/generation
                body: {…, "routingKey": "3:abc-123"}    // omitted if null
              returns: {requestId, status}

  execution.setVariable(resultProcessVariable, requestId)
  execution.setVariable(JOB_PATH, "epistola:job:" + tenantId + "/" + requestId)
```

The service task then completes; the BPMN engine moves to the next activity,
which (per the recommended pattern in [async.md](async.md)) is a Message
Intermediate Catch Event subscribing on `EpistolaDocumentGenerated`. The
process suspends there.

## Receiving a result and correlating

Some time later, the suite has the result ready. The collector's next
`collectOnce()` returns it as one of the NDJSON lines. The handler runs in the
collector's virtual thread:

```
handleResult(tenantId, result):
  correlationService.correlateCompletion(
    tenantId, result.requestId, result.status, result.documentId, result.error)
      ↓
      jobPath = "epistola:job:" + tenantId + "/" + requestId
      runtimeService.createMessageCorrelation("EpistolaDocumentGenerated")
        .processInstanceVariableEquals(JOB_PATH, jobPath)
        .setVariable(STATUS, …) .setVariable(DOCUMENT_ID, …) .setVariable(ERROR_MESSAGE, …)
        .correlateAllWithResult()
      → BPMN message catch fires, process advances

  ON EXCEPTION: log.warn and return normally.
    Critical: re-throwing would skip the contract helper's ack and replay this
    result on every poll forever. The result already exists on the suite;
    redelivery cannot conjure up a missing waiting execution.
```

The next `collectOnce()` includes `acknowledgeUpTo: <last sequence in that
batch>`, telling the suite "I've handled everything up to here." The suite
advances its per-partition cursor.

## Multi-instance startup

Each Valtimo node runs the same code independently:

```
                                  ┌──────────────────────────┐
node A → POST /generation/collect ↘                          │
node B → POST /generation/collect → │   Epistola suite        │
node C → POST /generation/collect ↗  │   partition assigner    │
                                  │   (e.g. 16 partitions)   │
                                  └──────────────────────────┘
```

The suite identifies each node by `(consumerId = api-key-id, nodeId = X-EP-Node-Id)`
and distributes the partitions across the live nodes. Each `_meta` line tells
the node "you currently own these partition numbers." Future jobs submitted
from node A are routed via `routingKeyToMe(...)` to land on one of A's
partitions, so the result comes back to A.

If a node disappears, the suite reassigns its partitions on the next
rebalance window (60s default, set on the suite). Surviving nodes' next polls
return both their existing partitions' results plus the orphaned ones.

## Shutdown

```
@PreDestroy stop():
  for managed in collectors.values():
    managed.collector.stop()   // sets running=false on the contract helper
    managed.thread.interrupt() // unblocks the in-flight HTTP / sleep
  collectors.clear()
```

Anything received but not yet acked is simply not acked — the suite
redelivers next time anyone (us-after-restart, or a sibling node) polls.

## Crash recovery

The collector cursor is in-memory only. After a JVM crash:

- The partition's cursor on the suite did not advance for any unacked batch.
- On restart, our new collector polls and the suite returns the same results
  (same sequences) it sent before the crash.
- Because `correlateCompletion` is **idempotent** at the BPMN level — the
  message catch only fires once per execution; subsequent correlations match
  zero waiting executions and are no-ops — replay is safe. The handler ignores
  the "no waiting execution" case (logs at debug, acks anyway).

Operaton's runtime state survives in the shared Valtimo database, so even if
the surviving sibling node receives the redelivered result it can still
correlate to the original BPMN execution.

---

# Known issues and edge cases

## 1. Plugin configuration drift across instances

**Behavior today.** Each Valtimo instance reconciles independently on three
triggers:

- A `@Scheduled` tick every `epistola.result-collector.reconcile-interval-ms`
  (60s default) — the safety net.
- A Spring `@EventListener` on `com.ritense.valtimo.contract.event.PluginsDeployedEvent`
  — fired by Valtimo's `PluginService` after every plugin **create** and
  **update**.
- A Spring `@EventListener` on `com.ritense.plugin.events.PluginConfigurationDeletedEvent`
  — fired after every plugin **delete**.

Spring application events are in-process: an event published on instance A
does **not** propagate to instances B and C. Each instance only sees its own
local Valtimo's events. So the event listener gives instant reaction on the
instance that handled the user's UI request; the other instances pick up
the change at their next scheduled tick (still ≤ 60s).

`reconcile()` is `synchronized` because the scheduled tick and the event
listeners can run on different threads simultaneously.

**Add a config in the UI:**

- Each instance picks it up at its own next reconcile (worst case 60s).
- The instances start their collectors at staggered times. The suite sees
  consumers come online one by one and rebalances partitions on each new
  arrival.
- Jobs submitted from an instance _between_ the config save and that instance's
  reconcile have no matching collector → `routingKeyFor` returns null → submit
  goes out without a routing key → the suite falls back to hashing the
  `requestId`. The result still gets delivered, but it might land on whichever
  instance happens to own that hash partition once collectors do come up.

**Remove a config:**

- Each instance picks it up at its next reconcile and stops + interrupts its
  collector. The suite stops seeing polls from that consumer for that
  `(api-key, node-id)` combination.
- Any results already produced for jobs that consumer submitted but never acked
  remain in the suite's `generation_results` table. The suite's idle-timeout
  (60s) and any retention/cleanup policy decides what happens to them.
- BPMN processes that were waiting on those results will hang. (Same as
  today's polling consumer when a config is deleted mid-flight.)

**Change baseUrl/apiKey/tenantId:**

- `signatureChanged()` returns true. Stop and re-start the collector.
- The old collector's in-memory sequence cursor is lost — that's fine, the
  new collector for the new (baseUrl, apiKey, tenantId) talks to a different
  consumer identity in the suite anyway.
- Brief gap (milliseconds) between stop and start where `routingKeyFor`
  returns null. Same null-fallback behavior as add.

**Latency by instance:**

- The instance that handled the UI write reacts within milliseconds (event
  listener) — no 60s wait.
- Other instances in a multi-node deployment still wait up to
  `epistola.result-collector.reconcile-interval-ms` for their next scheduled
  tick, because Spring events are JVM-local. Lower the property if that's
  too long for your environment.

**Rebalance flicker:** when N nodes turn into N+1 or N-1, the suite's
re-assignment changes which partitions each node owns. During that window:

- An in-flight job submitted just before the change had its `routingKey`
  computed against the old partition assignment. If the partition it targeted
  moves to a different node, the result lands on that other node. Correlation
  still works (shared Operaton DB), just with an inter-node hop.
- This is a steady-state design property, not a bug. No action needed.

## 2. Node-id uniqueness

**Today.** `ClientIdentity.builder()` (the contract helper) defaults the
node id to `InetAddress.getLocalHost().hostName` when no nodeId is supplied
to the builder. We don't override that.

**Risk:** two Valtimo instances on the same hostname end up with the same
`X-EP-Node-Id`. The suite considers them one node, never splits partitions
between them, and one instance silently does no work (or both compete on the
same partitions — the contract leaves the exact behavior to the suite).

**Where this is fine:**

- Kubernetes — `HOSTNAME` is set to the unique pod name by default. Multiple
  pods → multiple hostnames → unique node ids. ✓
- Docker — container ID is the hostname. ✓
- One JVM per host. ✓

**Where this breaks:**

- Multiple JVMs on the same bare-metal host (test fleets, dev workstations).
- Multiple JVMs on the same VM where the operator hasn't customised hostnames.

**What to do.** Two options, neither implemented yet:

1. _Operator override._ Surface an `EPISTOLA_NODE_ID` environment variable
   (or `epistola.node-id` Spring property) and pass it through to
   `ClientIdentity.builder().nodeId(…)` when set. Stable, but operator
   responsibility.
2. _Auto-disambiguation._ Append a short random suffix to the hostname if
   no override is set: `${hostname}-${6-hex-chars}`. Always unique, but
   restarts produce a new node id, which costs the suite a rebalance every
   restart and burns a partition reassignment cycle.

Recommended next step: add option 1 (operator override) and a startup log
line printing the resolved node id, so collisions show up as identical lines
in two separate pods' logs. Defer option 2 until we see a real problem.

## 3. Backpressure when correlation gets slow

**Implicit backpressure today.** Inside `ResultCollector.collectOnce()`, the
contract helper invokes our handler **synchronously** in a loop, advancing
`lastSequenceInBatch` only after each handler call returns. The next
`collectOnce()` cannot start until the current one's handler invocations all
finish — they're on the same virtual thread. The next call's
`acknowledgeUpTo` reflects the just-processed sequence, so the suite won't
release new work until we've digested the previous batch.

That's already a working backpressure loop: slow Operaton → slow handler
returns → ack lags → suite holds the queue → no growing in-memory backlog
on our side.

**Limits of this design:**

- _Head-of-line blocking inside a batch._ A single slow correlation
  (e.g. one execution with hundreds of waiting subscriptions, or a database
  contention spike) blocks the rest of the batch. With `batchSize=100` and a
  pathological 5s correlation, the other 99 results wait 5s.
- _No parallelism._ The collector for a given config processes one result at
  a time. With 16 partitions and one collector per config, a single Operaton
  database slowdown affects all partitions for that consumer.

**Tuning knobs:**

- `epistola.result-collector.batch-size` — lower for less head-of-line
  blocking per batch (at the cost of more roundtrips).
- `epistola.result-collector.min-interval-ms` /
  `epistola.result-collector.max-interval-ms` — tighten when results are
  flowing predictably, loosen if Operaton needs breathing room.

**Future work:** parallelize handler invocations within a batch via a
virtual-thread executor and only ack once all results in the batch complete.
The contract helper's current API doesn't make this trivial — it advances
the cursor in its own loop — so this would need either a contract change or
a wrapper that buffers + parallelizes + waits + acks. Not worth doing until
we see real backpressure.

## 4. Result arriving before the BPMN message catch is registered

**The race.** A BPMN service task running `generate-document` is part of a
single Operaton transaction. Roughly:

```
T0   tx begin
T1   service task body runs:
       - submitGenerationJob → POST to suite (synchronous)
                                ↑ at this point the suite has the request
       - set JOB_PATH variable
T2   service task returns
T3   engine moves execution to the next activity (message catch)
T4   message subscription row inserted (still in tx buffer)
T5   tx commit  ← only now is the subscription visible to other connections
```

The collector poll, on a separate connection, can only correlate the result
**after** T5 (because `processInstanceVariableEquals` reads committed state).
But the suite can produce the result any time after T1.

If `T_result_available` < T5, and our collector polls between those moments,
`correlateAllWithResult()` finds zero subscriptions, throws
`MismatchingMessageCorrelationException`, which we catch and treat as "no
waiting execution; ack and move on." **The result is silently dropped, the
BPMN process hangs forever.**

**How likely is this in practice?**

- For real document generation taking 100ms+, the race window is the time
  between T2 and T5 — typically a few milliseconds. The suite produces
  the result far later. Race essentially impossible.
- For mocked generation, a cached template, or a test setup where the suite
  responds in microseconds, the race is observable. The collector's
  `minInterval=1s` makes it less likely (we wouldn't poll for a full second
  after the previous batch), but if the previous batch produced results
  the next poll fires in 0ms, and _that_ poll could see our brand-new
  result before T5.

**Mitigations available today (operator level):**

- Don't structure the BPMN such that the message catch is far from the
  service task. The two should be adjacent (no parallel gateways, no
  intervening user tasks). The recommended pattern in
  [async.md](async.md) does this correctly.
- Avoid `@Async` or
  `TransactionSynchronizationManager`-deferred work in custom plugin actions
  that would push T5 well past T2.

**Mitigations the plugin could add (future work):**

1. _In-memory retry queue._ On `correlated == 0`, hold the result in a
   small per-runner queue with a short deadline (say 5s). A scheduled task
   re-attempts correlation every ~250ms. Still ack the suite immediately
   (the retry is purely local). Limitation: a JVM crash between ack and
   successful retry loses the result. With the suite's at-least-once
   redelivery property _gone_ (because we acked), this is unrecoverable.
2. _Don't ack on no-match._ Re-throw from the handler so the contract
   helper skips the ack. Pro: at-least-once preserved; the suite redelivers
   on the next poll. Con: we'd loop forever on this one batch if the BPMN
   never subscribes (e.g. process killed). Mitigation: combine with a local
   "tried N times, give up" counter — at which point we'd have re-implemented
   half a retry queue anyway.
3. _Explicit subscription pre-registration._ Inside `generateDocument`,
   programmatically create a message subscription for `EpistolaDocumentGenerated`
   keyed on `JOB_PATH` _before_ calling `submitGenerationJob`. Operaton
   exposes this via the runtime API. The subscription becomes visible at
   T5 either way, but we're guaranteed it exists in the same transaction
   as the submit. Doesn't solve the T2-vs-T5 race; only widens the
   tolerance for the BPMN structure.

**Recommendation.** Live with the limitation in v0.3. Document the BPMN
constraint (message catch immediately after service task, no async
work in between). Build option 1 or 2 if real users hit it.

**Could we just make `generateDocument` synchronous?** Yes — the existing
`check-job-status` plugin action does sync polling, and you can build a
purely synchronous flow with it. That side-steps the race entirely. The
trade-off is that the BPMN engine's worker thread blocks on Epistola for
the whole generation time, which for slow generations defeats the point of
async. Worth recommending for short, deterministic generations where the
race actually matters.

---

# Configuration reference

```yaml
epistola:
  enabled: true # master switch (default true)
  result-collector:
    enabled: true # disable to suppress all collectors
    batch-size: 100 # results per /collect call (1-10000)
    min-interval-ms: 1000 # floor on poll interval when busy
    max-interval-ms: 30000 # ceiling on poll interval when idle
    reconcile-interval-ms: 60000 # how often to detect plugin-config drift
```

For environment-variable form, replace `.` with `_`, hyphens with `_`, and
upper-case: `EPISTOLA_RESULT_COLLECTOR_BATCH_SIZE=200`.
