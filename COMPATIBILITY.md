# Valtimo Compatibility

Which Valtimo platform versions each release of `valtimo-epistola-plugin` works with.

Two things are tracked, and they are not the same:

- **Tested against** — the exact Valtimo version a plugin release pins and is built/tested on (the single `valtimo` key in `gradle/libs.versions.toml`, with the frontend `@valtimo/*` packages matched). This is a fact, derivable from the release tag.
- **Compatible range** — the range of Valtimo versions a plugin release is _expected_ to work with, including older (backward) and newer (forward) versions. This is a judgement based on the Valtimo APIs the plugin actually uses and Valtimo's semantic-versioning guarantees — it is **not** derivable from the pin alone. A release is often forward-compatible with newer 13.x minors it was never built against, and may stay backward-compatible with somewhat older ones.

> Forward compatibility is best-effort. "Compatible range" means "no known incompatibility and the plugin only uses APIs stable across that range" — not "verified on every version in the range." When in doubt for a specific Valtimo version, run the `update-valtimo` skill, which reads the changelog between versions and reports impact, or build the test-app against that version.

## Matrix

| Plugin version | Tested against (Valtimo) | Compatible range (expected) | Notes                                                                                                                                                                                                                                                                               |
| -------------- | ------------------------ | --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Unreleased     | `13.47.0.RELEASE`        | `>=13.21.0, <14`            | 13.45→13.47 changelog-reviewed. 13.47 ships `json-schema-validator` 1.x via `external-plugin`; the plugin now shades its 2.x copy, so it no longer constrains the host's version. Spring Boot/Angular/Form.io pins unchanged.                                                       |
| 0.20.0         | `13.44.0.RELEASE`        | `>=13.21.0, <13.47.0`       | 13.43→13.44 changelog-reviewed. One undocumented interface change: `ValueResolverFactory` gained `preProcessValuesForNewDocument` in 13.43 (implemented as a pass-through; adds a method, so older Valtimo still loads the class). Form.io/Angular pins unchanged across the range. |
| 0.12.0         | `13.32.0.RELEASE`        | `>=13.21.0, <14`            | 13.22→13.32 changelog-reviewed: all additive, no floor-raising change, compiled/tested clean. Floor kept at 13.21 (`peerDependencies ^13.21.0`).                                                                                                                                    |
| 0.3.0 – 0.9.4  | `13.21.0.RELEASE`        | `>=13.21.0, <14`            | Targets the 13.x line; forward-compatible within 13.x barring breaking changes flagged by `update-valtimo`.                                                                                                                                                                         |
| 0.1.x – 0.2.x  | `13.4.1.RELEASE`         | `>=13.4.1, <13.21.0`        | Pre-13.21 baseline; superseded by 0.3.0.                                                                                                                                                                                                                                            |

**Known incompatibility: 0.18.0 – 0.20.0 do not start on Valtimo ≥ 13.47.0.** Those releases put
`com.networknt:json-schema-validator` 2.x on the host classpath, while Valtimo 13.47's
`external-plugin` module needs 1.x (`NoClassDefFoundError: com/networknt/schema/ValidationMessage`).
Upgrade the plugin; releases after 0.20.0 shade the validator. This supersedes the `<14` upper bound
on the 0.12.0 row for 0.18.0 and 0.19.x.

Backend versions use the `X.Y.Z.RELEASE` form; the frontend `@valtimo/*` packages use the matching `X.Y.Z`.

The supported frontend range assumes Angular 19 and the Form.io versions used throughout this Valtimo range: `@formio/angular@7.0.0` and `formiojs@4.19.5`. These are exact peer versions because separate Form.io installations create separate component registries. CI builds against the current pin and runs `pnpm singletons:check`; the 13.21 floor is maintained by limiting Epistola's compatibility adapter to the public APIs available at that version.

**Form.io coupling to watch on a major bump.** Every `@valtimo/components` release across the supported range (13.21 → 13.44) depends on exactly `formiojs@4.19.5`, so there is only one Form.io version in play. Two behaviours of that version are load-bearing for the task-id carrier (see [docs/formio-components.md](docs/formio-components.md)):

- `Component.getModifiedSchema` — persists only schema that _differs_ from the registered default, which is why `withPrefilledTaskIdCarrier` has to re-add the carrier after the filter. It is declared in Form.io's public typings and is overridden, not monkey-patched, so the coupling is scoped to the plugin's own three components. The override deliberately has **no fallback**: if a future Form.io drops or changes the method, `task-id-carrier.spec.ts` fails on the bump. Failing there is preferable to degrading silently, which would persist every component's full default schema and freeze those defaults at authoring time.
- The builder uniquifies component keys across a form (`epistolaTaskId` → `epistolaTaskId2`), so the carrier is matched on its `hidden` type plus source key, never on its key.

Re-check both when raising the Form.io version, not just when raising Valtimo — the pin is transitive through `@valtimo/components`.

## Epistola Suite compatibility (catalog wire schema)

Separate from Valtimo, the plugin also has a compatibility surface with **Epistola Suite**: the
bundled classpath catalogs it imports carry a catalog **wire `schemaVersion`**, and the suite
gates imports against its own `[baseline, current]` window. A catalog below the suite's baseline
is rejected with RFC-9457 `400 catalog-schema-too-old`.

| Plugin build | Contract client (`client-spring3-restclient`) | Bundled catalog wire schema | Compatible Epistola Suite        |
| ------------ | --------------------------------------------- | --------------------------- | -------------------------------- |
| 0.20.0       | `1.3.1`                                       | `4`                         | `>= 1.0.0`                       |
| 0.12.0       | `0.8.0`                                       | `4`                         | `>= 0.26.0`                      |
| ≤ 0.11.x     | `0.6.0`                                       | `2`                         | `<= 0.25.x` (broken on ≥ 0.26.0) |

Notes:

- The suite's image automation can bump `epistola-suite` and the host (`demo-backend`)
  **independently**, so a suite catalog-wire baseline bump is a **breaking change for already-released
  plugin versions** — the catalog is compiled into the application image and cannot be re-exported by
  an operator; the fix is to ship a plugin/host build whose bundled catalogs target the new baseline.
- There is **no automated v2→v4 catalog migration** (the suite's migration chain is empty:
  `baseline == current == 4`); re-authoring the bundled catalogs is the only path.
- The floor is guarded in CI by `BundledCatalogSchemaVersionTest` (test-app) and a ZIP-level
  assertion in `EpistolaCatalogSyncServiceTest` — both pinned to the targeted wire schema, so a
  future suite baseline bump fails the build loudly instead of in production. See GitHub issue #71.
- The contract client must also keep **reading older servers**, which is separate from the wire
  schema: a contract release can add a response field that older servers never send. Contract
  `1.3.0` made one (`slug`) required, so its client rejected every response from a server older
  than `1.3.0`; this plugin skips it for `1.3.1`, which makes the field optional. The floor is
  guarded by `oldestSupportedServerTest`, which runs the mock-server integration test against
  contract `0.16.1` — the one Epistola Suite `1.0.0` serves. Raise it with the Suite floor.

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
`ValueResolverFactory` (`com.ritense.valueresolver.ValueResolverFactory`, prefix `epistola:`)
that runs during Valtimo's server-side form prefill. This relies on two stable behaviours present
across the supported range: the public value-resolver SPI, and `PrefillFormService` passing the
`OperatonTask` as the resolver's `VariableScope` when prefilling a task form (so the resolver can read
the task's taskId/executionId/taskDefinitionKey). Both the per-task and bulk process-link endpoints prefill
through the same path, so this works regardless of how the task was opened. If a future Valtimo changes
the prefill scope or the value-resolver SPI, `EpistolaTaskValueResolverFactory` (and the embedded
carrier the components ship) is the single place to adjust.

## How to update this file

This matrix is **maintained by hand** — the "compatible range" column is a deliberate judgement and cannot be generated from the version pin.

- The `update-valtimo` skill updates the matrix as part of a Valtimo bump: it adds/extends a row for the new tested-against version and revisits the compatible range in light of the changelog it just reviewed.
- When making the plugin work across a wider range (see the backward/forward-compatibility guidance in `CLAUDE.md`), widen the "compatible range" here to record it.
- If a Valtimo release introduces a confirmed incompatibility, narrow the affected range and note it.
