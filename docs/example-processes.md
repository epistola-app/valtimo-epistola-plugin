# Example processes (`example` case)

The test-app's `example` case (`test-app/backend/src/main/resources/config/case/example/1.0.0/`)
ships a set of small, startable processes that each demonstrate one Epistola
generation/correlation pattern — including a few **deliberate anti-patterns** kept as runnable
reproductions. They all reuse the `example-template` and a `single-document`-style start form.

For the correlation mechanics behind these, see [async.md](async.md).

| Process (`processDefinitionKey`) | Pattern                                                                                                                | Correlates? | Notes                                                                                                                                                                                                                         |
| -------------------------------- | ---------------------------------------------------------------------------------------------------------------------- | ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `single-document`                | Round message catch event (the canonical minimal flow)                                                                 | ✅          | Auto-wired. Start here.                                                                                                                                                                                                       |
| `single-document-async`          | `generate-document` with `camunda:asyncAfter` before a round catch event                                               | ✅          | Exercises the **self-heal** path for the async-boundary race. Validator flags the boundary as advisory.                                                                                                                       |
| `single-document-receive-task`   | **Receive task** wait + interrupting `PT30M` boundary timer (timeout → `EpistolaGenerationTimeout`)                    | ✅          | Auto-wired (no `epistolaWaitFor` mapping). **This is the correct "wait with a timeout" pattern.**                                                                                                                             |
| `parallel-documents`             | Parallel gateway → two branches, each its own catch event + **distinct** `resultProcessVariable` (`resultA`/`resultB`) | ✅          | Per-branch correlation isolation.                                                                                                                                                                                             |
| `three-branch-one-linked`        | Exclusive split, only one branch has a `generate-document` link, merge → one catch event                               | ✅          | Happy path: a single generate feeding one catch event is unambiguous.                                                                                                                                                         |
| `letter-by-type-fixed`           | Exclusive split → different template per branch → merge → one catch event, **shared** `resultProcessVariable`          | ✅          | The correct way to do per-input template choice with one downstream wait.                                                                                                                                                     |
| `letter-by-type`                 | ⚠️ Same as above but **different** result variables (`resultA`/`resultB`) per branch                                   | ❌ stalls   | **Anti-pattern.** `AMBIGUOUS_CATCH_EVENT`: the branch whose variable wasn't pinned gets no token. Fix = `letter-by-type-fixed`.                                                                                               |
| `single-document-event-gateway`  | ⚠️ **Event-based gateway** racing the message vs. a `PT30M` timer                                                      | ❌ never    | **Anti-pattern.** Event gateways can't correlate (subscription on a transient child execution). The timer branch always wins. Use `single-document-receive-task` instead. See [ADR 0002](adr/0002-wait-state-correlation.md). |

**Rules of thumb the examples encode:**

- Wait on a **round catch event** or a **receive task** — both auto-wire. Use a receive task when you
  need an interrupting boundary timer (timeout).
- **Never** use an event-based gateway to wait for Epistola.
- When several `generate-document` branches **merge into one wait**, give them the **same**
  `resultProcessVariable`. When they wait on **separate** waits (parallel), give each its **own**
  result variable.
