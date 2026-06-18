# ADR 0002 — Which BPMN wait constructs Epistola correlation supports

- **Status:** Accepted
- **Date:** 2026-06-18
- **Deciders:** Epistola plugin maintainers
- **Related:** `EpistolaCatchEventParseListener`, `EpistolaCatchEventStartListener`, `EpistolaCatchEventLinkResolver`, `EpistolaProcessDefinitionValidator`, `EpistolaMessageCorrelationService`, `docs/async.md`, ADR 0001

## Context and problem statement

A process waits for Epistola to finish generating a document by parking on a BPMN wait that
subscribes to the `EpistolaDocumentGenerated` message. The result-collector wakes the exact
waiting branch by a **targeted** query:

```
runtimeService.createExecutionQuery()
    .messageEventSubscriptionName("EpistolaDocumentGenerated")
    .variableValueEquals("epistolaWaitFor", jobPath)   // the per-job routing token
```

then `messageEventReceived(message, executionId)`. This deliberately avoids a broadcast
`correlateAll` so that, in parallel/multi-instance processes, a completion wakes **only** the
branch that submitted it. The hard invariant this creates:

> **The `epistolaWaitFor` token and the message subscription must live on the _same_ execution.**

Originally only the **round intermediate message catch event** was auto-wired: a parse listener
attached a start listener that, on catch-event entry, pinned the token execution-local on the
subscribing execution — co-located by construction.

Two real situations exposed the limits of "catch event only":

1. A customer process waited on a **receive task** (the task-shaped equivalent, e.g. so it could
   carry an interrupting boundary timer). Receive tasks were not auto-wired, so unless the author
   hand-added a `camunda:inputParameter epistolaWaitFor`, the task got no token and the process
   stalled — silently (it didn't even appear in admin before the Pending-Jobs change in this branch).
2. An attempt to bound the wait with an **event-based gateway** (message vs. timer) never correlated.

## Decision drivers

- **Zero author config** for the common, correct patterns — authors should not hand-pin tokens.
- **Preserve the co-location invariant** — never weaken it (it is what keeps parallel correlation correct).
- **Avoid the riskier alternative** of pinning the token at generate-time (see below).
- **Fail loudly, not silently** — if a topology can't correlate, the validator should say so.

## Decision outcome

**1. Auto-wire receive tasks exactly like round catch events.** The parse listener also hooks
`parseReceiveTask`, attaching the same start listener. On receive-task entry the token is pinned
execution-local on the receive task's own execution — the same execution that creates the
subscription — so the co-location invariant holds identically. The reachability walk
(`findReachableEpistolaWait`) and link resolver recognize a receive task referencing
`EpistolaDocumentGenerated` as a valid wait; self-heal and the validator's async/ambiguity checks
cover it too. No `epistolaWaitFor` mapping is needed.

**2. Event-based gateways are unsupported for Epistola waits.** An event-based gateway places its
message subscription on a **transient child execution**, while a token pinned upstream (e.g. a
`generate-document` output mapping) lands on the **parent** execution — and the catch event behind
the gateway is not entered until the message already arrives, so its own listener runs too late.
There is **no execution onto which the token can be pinned, in time, that is co-located with the
subscription**. The collector therefore never matches, and the wait stalls. This is a hard
limitation, not a bug to fix at the BPMN level. The supported way to "wait with a timeout" is a
**receive task (or round catch event) with an interrupting boundary timer** — a single wait state
on its own execution that auto-wires normally. The plugin ships `single-document-event-gateway` as
a runnable **anti-pattern** and `single-document-receive-task` as the working equivalent.

## Alternatives considered

- **Pin the token at `generate-document` time (execution-local on the generate execution).** This
  would also make event gateways and ambiguous exclusive merges "just work" without author config.
  **Rejected** (for now): it assumes the generate execution is the same execution that later holds
  the subscription. That holds for flat topologies but **not** when the engine reorganizes the
  execution tree between the service task and the wait (embedded subprocess, boundary events,
  multi-instance, some async boundaries) — the token can land on a different execution than the
  subscription, breaking the co-location invariant in exactly the parallel/scoped cases that are
  hardest to get right. Auto-wiring at the **wait's own entry** keeps co-location by construction
  and is strictly safer. If event-gateway support is ever required, this is the lever — but only
  behind a test matrix that covers scoped topologies.

- **Broadcast / parent-scope correlation** (match the token at process-instance scope). **Rejected:**
  it breaks per-branch isolation in parallel/multi-instance processes — the whole reason the token
  exists.

- **Keep receive tasks manual** (require a `camunda:inputParameter`). **Rejected:** it's a silent
  foot-gun; the customer hit exactly this. Auto-wiring is the symmetric, lower-surprise behavior.

## Consequences

- Receive-task waits work with no configuration, including the receive-task-with-interrupting-timer
  timeout pattern.
- The **ambiguity rule is unchanged** and applies to receive tasks too: a single wait fed by
  branches with **different** `resultProcessVariable`s can only be wired to one — give converging
  branches the **same** result variable (exclusive merge) or their own wait per branch (parallel).
  The validator flags the divergent case (`AMBIGUOUS_CATCH_EVENT`).
- Event-based gateways remain unsupported and are documented/tested as such, so the failure is
  understood rather than rediscovered.
- The start listener is attached to every receive task in the application; it is a fast no-op for
  non-Epistola receive tasks (the resolver returns no result variable for them), consistent with how
  it already treats every message catch event.
