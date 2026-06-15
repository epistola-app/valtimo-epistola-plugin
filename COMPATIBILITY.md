# Valtimo Compatibility

Which Valtimo platform versions each release of `valtimo-epistola-plugin` works with.

Two things are tracked, and they are not the same:

- **Tested against** — the exact Valtimo version a plugin release pins and is built/tested on (the single `valtimo` key in `gradle/libs.versions.toml`, with the frontend `@valtimo/*` packages matched). This is a fact, derivable from the release tag.
- **Compatible range** — the range of Valtimo versions a plugin release is _expected_ to work with, including older (backward) and newer (forward) versions. This is a judgement based on the Valtimo APIs the plugin actually uses and Valtimo's semantic-versioning guarantees — it is **not** derivable from the pin alone. A release is often forward-compatible with newer 13.x minors it was never built against, and may stay backward-compatible with somewhat older ones.

> Forward compatibility is best-effort. "Compatible range" means "no known incompatibility and the plugin only uses APIs stable across that range" — not "verified on every version in the range." When in doubt for a specific Valtimo version, run the `update-valtimo` skill, which reads the changelog between versions and reports impact, or build the test-app against that version.

## Matrix

| Plugin version       | Tested against (Valtimo) | Compatible range (expected) | Notes                                                                                                                                            |
| -------------------- | ------------------------ | --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| current (unreleased) | `13.32.0.RELEASE`        | `>=13.21.0, <14`            | 13.22→13.32 changelog-reviewed: all additive, no floor-raising change, compiled/tested clean. Floor kept at 13.21 (`peerDependencies ^13.21.0`). |
| 0.3.0 – 0.9.4        | `13.21.0.RELEASE`        | `>=13.21.0, <14`            | Targets the 13.x line; forward-compatible within 13.x barring breaking changes flagged by `update-valtimo`.                                      |
| 0.1.x – 0.2.x        | `13.4.1.RELEASE`         | `>=13.4.1, <13.21.0`        | Pre-13.21 baseline; superseded by 0.3.0.                                                                                                         |

Backend versions use the `X.Y.Z.RELEASE` form; the frontend `@valtimo/*` packages use the matching `X.Y.Z`.

## Engine-integration dependency (correlation)

The plugin is otherwise built on Valtimo/Operaton **public** APIs. The one exception is the
auto-wiring of `EpistolaDocumentGenerated` catch-event correlation, which uses Camunda's **sanctioned
process-engine extension SPI**: a `ProcessEnginePlugin` (`org.operaton.bpm.engine.impl.cfg`) that
registers a `BpmnParseListener` (`org.operaton.bpm.engine.impl.bpmn.parser`) which calls
`ActivityImpl#addListener` to attach one public `ExecutionListener` to each catch event. These live in
`impl` packages but are the documented, long-stable extension points (`EpistolaProcessEnginePlugin`,
`EpistolaCatchEventParseListener`). The plugin deliberately avoids the more volatile internals — no PVM
graph traversal and no activity-behavior wrapping; all correlation logic (token resolution via the
public BPMN model + `ProcessLinkService`, completion, self-heal) is public-API only. If a future
Operaton changes this SPI, only those two small classes are affected; the declarative override
(`camunda:inputParameter epistolaWaitFor = ${<resultVar>.jobPath}`) keeps correlation working without
the auto-wiring.

If a future Operaton breaks the SPI, set `epistola.catch-event-auto-wiring.enabled=false` (default
`true`) to drop the `ProcessEnginePlugin` + `BpmnParseListener` beans entirely and fall back to the
declarative `epistolaWaitFor` mapping — no need to disable the whole plugin. This is a sub-flag of the
global `epistola.enabled` gate.

## Form-prefill dependency (task-context delivery)

The Formio components (preview, download, retry-form) obtain the active user task id via a plugin
`ValueResolverFactory` (`com.ritense.valueresolver.ValueResolverFactory`, prefix `epistola-task:`)
that runs during Valtimo's server-side form prefill. This relies on two stable behaviours present
across the supported range: the public value-resolver SPI, and `PrefillFormService` passing the
`OperatonTask` as the resolver's `VariableScope` when prefilling a task form (so the resolver can read
the task id/executionId/taskDefinitionKey). Both the per-task and bulk process-link endpoints prefill
through the same path, so this works regardless of how the task was opened. If a future Valtimo changes
the prefill scope, only `EpistolaTaskValueResolverFactory` is affected; the components still fall back
to the `EpistolaTaskContextInterceptor` for the direct task-open flow.

## How to update this file

This matrix is **maintained by hand** — the "compatible range" column is a deliberate judgement and cannot be generated from the version pin.

- The `update-valtimo` skill updates the matrix as part of a Valtimo bump: it adds/extends a row for the new tested-against version and revisits the compatible range in light of the changelog it just reviewed.
- When making the plugin work across a wider range (see the backward/forward-compatibility guidance in `CLAUDE.md`), widen the "compatible range" here to record it.
- If a Valtimo release introduces a confirmed incompatibility, narrow the affected range and note it.
