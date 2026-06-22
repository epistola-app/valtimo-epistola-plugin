# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **`admin@demo` now holds all five tenant roles, not just `tenant-administrator` (chart `valtimo-demo` 0.4.0 → 0.4.1).** Under the app's deliberately non-inheriting role model, `tenant-administrator` grants settings/users/catalogs/diagnostics/backups/restore but **not** the `*_VIEW` permissions — so an admin-only user got *access denied* opening the tenant home (it lists templates/themes, requiring `TEMPLATE_VIEW`/`THEME_VIEW`). `admin@demo` is meant to be the full-access account (matching the suite's own `admin@demo`/local-admin), so it is now in `/epistola/tenants/demo/{content-viewer,content-author,document-generator,content-publisher,tenant-administrator}` + `/epistola/platform/tenant-manager`. The scoped showcase remains `viewer@/author@/publisher@demo`.
- **Demo Keycloak realm now exercises Epistola's renamed, fine-grained authorization roles via both supported claim shapes (chart `valtimo-demo` 0.3.2 → 0.4.0).** Epistola Suite renamed its tenant roles to `content-viewer` / `content-author` / `document-generator` / `content-publisher` / `tenant-administrator` (+ platform `tenant-manager` / `platform-observer`); the demo realm's old `ep_demo_reader`/`ep_reader`/… groups were vestigial (flat `ep_`-prefixed names with `full.path: false` matched neither the app's hierarchical `/epistola/tenants/{tenant}/{role}` parser nor its flat `ept_/epg_/eps_` parser, so demo roles only ever came from the email-domain fallback resolver). Replaced them with the real hierarchical group tree (`/epistola/{tenants/demo,global,platform}/…`), flipped the `epistola-suite` client's group-membership mapper to `full.path: true`, added matching flat realm roles (`ept_demo_*`, `epg_*`, `eps_*`) plus a realm-role mapper emitting the top-level `roles` claim (so **both** the group and flat-role shapes resolve, and the app unions them), and seeded demo users that prove each path: `viewer@demo` (group → viewer), `author@demo` (groups), `publisher@demo` (**flat roles** → viewer+publisher), `admin@demo` (group → tenant-administrator + platform tenant-manager). The valtimo clients/`valtimo-users` are untouched. **Applying this requires wiping the Keycloak DB** (realm import is `IGNORE_EXISTING`), which clears existing demo accounts.

## [0.11.0] - 2026-06-22

### Added

- **The document-preview "Input Overrides" builder now shows which variables the selected template's mapping actually consumes.** Picking a process link in the preview component's editForm surfaces a read-only "Used by this template" list of every `$doc`/`$pv`/`$case` path the link's `generate-document` data mapping references, and feeds those paths into the Input-Path column autocomplete (a `<datalist>` per scope) and the advanced JSONata editor's completion — so the author can see exactly what is worth overriding instead of guessing against a free-form field. The paths are extracted **statically on the frontend** by walking the mapping's JSONata AST (`extractReferencedPaths`, generalizing the existing `jsonata-converter` variable-path technique), reusing the bundled `jsonata` parser — so it's best-effort guidance, never validation (dynamically built paths simply don't appear, and a parse failure shows nothing). A thin configurator-tier endpoint `GET /api/v1/plugin/epistola/process-link-mapping` (keyed by `processDefinitionKey` + `activityId`, `ROLE_ADMIN` like the other configurator endpoints) returns just the raw `dataMapping` string; the backend does no JSONata parsing. The list refreshes when the selected link changes and is empty (hidden) when no link is selected or none can be resolved. Closes #57.
- **EUPL-1.2 license headers on every source file, applied and gated automatically.** All published backend Java (`backend/plugin/src/**`) and frontend TypeScript (`frontend/plugin/src/**`) sources now carry an `SPDX-License-Identifier: EUPL-1.2` header. The canonical header text lives once in `config/license-header.txt` and is shared by both ecosystems so they never diverge: the backend applies it via a Spotless `licenseHeaderFile` rule (`./gradlew :backend:plugin:spotlessApply`, gated by `spotlessCheck` which `build` already runs), and the frontend via a dependency-free Node script (`pnpm headers` / `pnpm headers:check`, scoped strictly to `frontend/plugin/src`). CI runs `pnpm headers:check` alongside lint/format. The two Valtimo-derived test files keep their original Ritense BV copyright notice.
- **New example processes in the test-app `example` case** demonstrate more correlation topologies. All reuse the `single-document` generate step + start form and are startable from the UI:
  - `single-document-event-gateway` — an **expected-to-fail anti-pattern**: an event-based gateway racing the `EpistolaDocumentGenerated` message against a `PT30M` timer **cannot correlate**. The gateway places the message subscription on a transient **child** execution while the `epistolaWaitFor` token can only live on the **parent** — they never co-locate, so the message branch never fires and only the timer branch wins. There is no BPMN-level fix; the correct "wait with a timeout" is a receive task / catch event with an interrupting boundary timer (`single-document-receive-task`). The limitation is locked in by `EpistolaAutoWiringCorrelationIntegrationTest`.
  - `single-document-receive-task` — waits with a **receive task** instead of a round intermediate catch event, with an interrupting `PT30M` boundary timer (→ `EpistolaGenerationTimeout`). Auto-wired like a catch event (see the receive-task auto-wiring entry below) — no `epistolaWaitFor` mapping needed.
  - `single-document-async` — `generate-document` has `camunda:asyncAfter`, so the generate step and the catch-event subscription commit in separate transactions; proves the plugin's **self-heal** delivers a result that lands in that window (the catch event is auto-wired, no manual mapping). The BPMN validator flags the async boundary as an advisory note (added latency), not a stall.
  - `parallel-documents` — a **parallel gateway** generates two documents concurrently, each on its own round catch event with a **distinct `resultProcessVariable`** (`resultA` / `resultB`); proves per-branch auto-wired correlation wakes exactly the branch that submitted each completion.
  - `letter-by-type` — a deliberate **anti-pattern reproduction**: an exclusive gateway picks a different template per input, the branches **merge**, and a **single** catch event waits. Because two generate-document tasks with **different** result variables (`resultA` / `resultB`) reach one catch event, the auto-wiring can only pin one — the branch whose variable wasn't pinned gets **no `epistolaWaitFor` token**, so its completion never correlates, the process stalls at the wait, and (because `getPendingJobs()` skips token-less executions) it's **invisible in the admin Pending Jobs tab**. The validator flags it as `AMBIGUOUS_CATCH_EVENT`. **Fix:** give both branches the **same** `resultProcessVariable` (correct for an exclusive merge — only one branch runs), or give each branch its own catch event. See [async.md → Ambiguous (merged) catch events](docs/async.md#ambiguous-merged-catch-events).
  - `three-branch-one-linked` — an exclusive gateway with **three** branches where only branch A has a `generate-document` link (B and C are `${null}` no-op tasks), all merging into one shared catch event. Confirms the **happy path**: going through the linked branch correlates fine (a single generate link feeding one catch event is unambiguous). A positive counterpart to `letter-by-type`.
  - `letter-by-type-fixed` — the **corrected** sibling: identical topology, but both branches write the same `resultProcessVariable` (`epistolaResult`), so the shared catch event resolves unambiguously, the token is pinned whichever branch ran, and the process always completes. Not flagged by the validator. Both branches verified to correlate and complete.

  Documented in [async.md → Adding a timeout](docs/async.md#adding-a-timeout-event-gateway--boundary-timer), [Parallel generation](docs/async.md#parallel-generation), and [Race-safety](docs/async.md#race-safety-keep-the-boundary-synchronous).

### Added

- **Isolated unit tests for the `check-job-status` action and the template-browsing controller.** `EpistolaPluginCheckJobStatusTest` covers the type-tolerant request-id extraction (rich-result `Map` vs legacy `String`), null/blank/unsupported-type validation, and the conditional status/document-id/error-message variable writes. `EpistolaTemplateResourceTest` pins that each `EpistolaTemplateResource` endpoint resolves the plugin configuration and delegates to `EpistolaService` with the right connection details and path/query params. Closes two of the documented test-coverage gaps.

### Changed

- **The document preview no longer refreshes on every keystroke.** Previously the `epistola-document-preview` component recomputed and re-rendered the PDF on every Formio `change` event (debounced 1.5s), which felt hectic while filling in a form. It now (1) flushes on field **blur** (a bubbling `focusout` listener on the form root) so edits settle before refreshing, (2) **dedups** — only re-renders when the computed `{ doc, pv }` overrides actually changed, so typing in fields the mapping doesn't read no longer triggers a refresh, and (3) adds an **Auto-refresh** toggle in the builder (default **on**) so authors can switch to refresh-only-via-the-Refresh-button, plus a configurable **Auto-refresh debounce (ms)** field (default 1500). The preview still paints once when the form opens even with auto-refresh off, and the manual **Refresh** button always works. It also **loads on initial open when the required fields are already pre-filled**: because Valtimo can populate form data asynchronously after the component mounts (sometimes with no change event to hook), the wrapper re-attempts the initial paint a few times over ~2s until the data is present, and the **Refresh** button now forces a recompute from the live form data so it works before any change (previously it only worked after the first edit). The computed overrides are delivered to the component through a dedicated `inputOverrides` input rather than Formio's `value`: Valtimo's custom-component bridge mirrors `value` only to the DOM (never to Formio's data model), so Formio reset it to `emptyValue` on the next redraw — which had been silently cancelling the freshly-loaded preview (the old per-keystroke re-push masked this; dedup exposed it). Finally, auto-refresh is controllable **both** as a builder default **and** at runtime: a checkbox in the preview header lets the end-user turn it off/on for their session (seeded from the builder default, and preserved across Formio redraws). Documented in [document-preview.md → Auto-refresh](docs/document-preview.md#auto-refresh-optional-default-on).
- **The Epistola Document component's Formio builder dialog is trimmed to its five relevant options.** Previously, because its registration omitted an `editForm`, the builder fell back to Form.io's full stock text-field dialog (Display/Data/Validation/API/Conditional/Logic/Layout tabs plus Valtimo's injected Value Resolver) — dozens of fields the component never reads, several of which could produce confusing or invalid configurations. It now declares an explicit minimal `editForm` (a flat field list, no tabs — the same pattern `epistola-document-preview` already uses) exposing only **Label**, **Display** (inline/button/both), **Document variable**, and **Filename**. The tenant-id variable is no longer author-configurable: the tenant is process-wide (always `epistolaTenantId`), so it falls back to that default rather than cluttering the dialog with an escape hatch nobody sets. Runtime behaviour, palette visibility, the embedded task-id carrier, and the download authorization path are unchanged; defaults match the component's existing `@Input()` defaults, so a freshly dropped component behaves exactly as before.
- **The frontend library now compiles under TypeScript `strict`, enforced by a CI type-check.** `tsconfig.lib.json` sets `strict: true`, and because ng-packagr's partial-compilation build does not fail on type errors, a dedicated `pnpm typecheck` (`tsc --noEmit --skipLibCheck`) gate now runs in CI between the header check and the build. Fixing the fallout aligned the four configurator components' `@Output() configuration` generic with Valtimo's framework contract (`FunctionConfigurationData` / `PluginConfigurationData` — `EventEmitter` is invariant, so the narrower per-config generic wasn't assignable under strict); emitted values are unchanged, still the typed config. One null-safety guard was added where a nullable form value was emitted.
- **Outbound Epistola HTTP calls now have timeouts and the idempotent reads retry transient failures.** Previously the `RestClient` set no connect or read timeout, so a hung Epistola server could block a BPMN job-executor thread (or the collector's virtual thread) indefinitely, and a transient blip failed the activity outright. Every client now gets a **connect timeout** (`epistola.client.connect-timeout-ms`, default 10s — safe everywhere, including the collector). The short request/response API clients (job status, submit, catalog/template/variant reads) additionally get a **read timeout** (`epistola.client.read-timeout-ms`, default 30s); streaming/large/long operations (the result-collector poll, document download, document preview, catalog import) deliberately get only the connect timeout so a slow render or large transfer is not cut off. The idempotent reads — **job status** and **document download** — retry on a transient failure (connect/read timeout, connection error, or 5xx) up to `epistola.client.max-read-retries` times (default 2, exponential 200ms backoff); 4xx responses are never retried. Verified by `EpistolaServiceImplRetryTest`.
- **Receive tasks are now auto-wired for correlation, exactly like round message catch events.** Previously the plugin only attached its catch-event start listener to round `EpistolaDocumentGenerated` intermediate catch events, so a `bpmn:receiveTask` waiting on the same message got no `epistolaWaitFor` token unless the author added a `camunda:inputParameter` by hand — and without it the process stalled, uncorrelated (and, before the Pending-Jobs change above, invisibly). The parse listener now also hooks `parseReceiveTask`, and the reachability walk + link resolver recognize a receive task referencing `EpistolaDocumentGenerated` as a valid Epistola wait. The token is pinned on the receive task's own execution at entry (co-located with the subscription, so it is robust under parallel/scoped topologies — same property as catch events), and self-heal / the validator's async + ambiguity checks now cover receive-task waits too. **No author config needed**; the `single-document-receive-task` example drops its manual `epistolaWaitFor` mapping accordingly. The same ambiguity rule still applies — a single receive task fed by branches with different result variables needs one shared `resultProcessVariable`. Verified by `EpistolaCatchEventLinkResolverTest` (pairs a generate-document to a reachable receive task) and `EpistolaAutoWiringCorrelationIntegrationTest` (auto-pins the token and correlates with no declarative mapping).
- **The admin Pending Jobs tab now surfaces _stuck_ (unwired) waits instead of hiding them.** Previously `GET /admin/pending` only listed executions that carry an `epistolaWaitFor` correlation token and silently skipped the rest — but a token-less wait is exactly the casualty an operator most needs to see: it can never be correlated (e.g. an ambiguous merged catch event, or auto-wiring disabled), so the process is stuck. Each `PendingJob` now carries a `status`: `WAITING` (has the token — correlatable) or `UNWIRED` (subscription but no token — stuck). Unwired rows show with best-effort `tenantId` (from the standalone `epistolaTenantId` variable, so they still land on the right config card), a null `requestId`, a red "Stuck" tag, and no reconcile button (reconcile cannot recover a wait with no jobPath — the fix is in the process model). Verified end-to-end against a real stuck instance.
- **The BPMN validator's `AMBIGUOUS_CATCH_EVENT` check is now result-variable-aware.** It flags two-or-more `generate-document` tasks converging on one `EpistolaDocumentGenerated` catch event **only when they don't all share one `resultProcessVariable`** — the genuinely broken case, where the auto-wiring can pin only one and the other branch stalls token-less (invisible in admin Pending Jobs). When every converging branch uses the **same** result variable it is no longer flagged: that's the correct fix for an exclusive split that merges (only one branch runs, so the shared variable always resolves). The warning message now names the differing result variables and recommends the same-variable fix (exclusive/merge) or per-branch catch events (parallel). Verified by `EpistolaProcessDefinitionValidatorTest` (different-variables → flagged; shared-variable → silent).
- **The document-preview "Input Overrides" mapping is now a JSONata expression over a new `$form` variable, replacing the bespoke `form:`-ref format.** Authors map live form fields onto the `{ doc, pv }` preview overlay the same way they author the generate-document data mapping — one JSONata model, with `$form` autocomplete in the editor — and can now transform form values (concatenate, conditionals, functions) instead of only forwarding them verbatim. The overlay still mirrors what generation reads, so preview ≡ generation is unchanged; `$form` is bound **only** during the client-side override evaluation (it is never introduced into the generation-time context, where there is no live form), so there are **no backend changes** — `PreviewService` still consumes the same `{ doc, pv }` wire shape. The simple table and advanced editor both produce JSONata. **Backward compatible**: forms still storing the legacy `{ scope: { path: "form:key" } }` object keep rendering (the frontend converts them on the fly via a self-contained, removable shim) and persist in the new format the next time they're saved in the builder — no form-schema migration.
- **The admin "Forms" tab now lists forms still using the legacy override-mapping object format.** A new read-only scan (`GET /admin/forms/legacy-override`, `EpistolaAdministration:MANAGE`) walks every form definition for `epistola-document-preview` components whose `overrideMapping` is an object, so operators can see which forms need a one-time re-save to migrate. The tab's count badge reflects carrier issues + legacy-override forms combined.
- **The JSONata editor's autocomplete is now driven by a single `contextVariables` map instead of per-variable inputs.** The Monaco completion provider derives both the `$`-variable list and `$<name>.` field suggestions generically from one `Record<string, string[]>` (`{ doc, pv, case }` for the data mapping, `{ form }` for the override builder), so adding a context variable no longer needs a new editor input or a hardcoded provider branch.

### Fixed

- **Documentation corrected for accuracy.** The README backend-install snippet now pins the current release (`0.10.0`, was a stale `0.6.0`; the authoritative Maven-metadata link is alongside it). `CLAUDE.md`'s Testing and Test Coverage sections were brought in line with the code: the split controller tests replace the removed `EpistolaPluginResource` reference, the Playwright count is corrected (4, not 5), the genuinely-remaining gaps are restated (`checkJobStatus` isolated test, `EpistolaTemplateResource`), and the full CI gate set is listed (`lint:check`, `typecheck`, `headers:check`, backend E2E).
- **The test-app backend E2E suite now runs in CI, and a pre-existing failure in it is fixed.** A new `Backend E2E (Testcontainers)` CI job runs `./gradlew :test-app:backend:test` (which boots the real Valtimo app against Testcontainers Postgres + Keycloak; Epistola is mocked) — previously CI compiled the test-app but skipped these tests (`-x test`), so they only ran locally. Wiring them in surfaced a pre-existing failure: the value-resolver E2E started a process with no business key, so Valtimo's user-task-create listeners (`CaseAssigneeTaskCreatedListener` / `CaseTaskTeamAutoAssignListener`) hit `UUID.fromString(null)` / "no document found". The test now creates a real `example` case document and starts the process with its id as the business key, matching how real dossier processes run. (The Playwright UI E2E suites need a full running stack and remain a local / planned-nightly step, not part of PR CI.)
- **`@formio/angular` is now a declared peer dependency.** The retry-form component imports `FormioModule` from `@formio/angular`, but it was absent from the published `package.json` and `ng-package.json`, resolving only transitively through `@valtimo/components` — a consumer whose package manager hoisted differently could fail to resolve it. It is now declared in `peerDependencies` (`^7.0.0`) and `allowedNonPeerDependencies`. The stale, unused `@ngx-translate/core` entry was dropped from `allowedNonPeerDependencies`.

## [0.10.0] - 2026-06-16

### Changed

- **The `POST /preview` and `GET /retry-form` endpoints now take only `taskId` (+ the component's `sourceActivityId`); the backend derives the process instance and case document from the authorized task.** Previously the Formio client sent `processInstanceId` + `documentId` (preview also a `processDefinitionKey`) and the backend cross-checked that they belonged to the task — a check that only existed because the client was trusted to supply them. Both values are deterministic from the task (`processInstanceId = task.getProcessInstanceId()`, `documentId = task.getProcessInstance().getBusinessKey()`), so they are now resolved server-side, mirroring the existing `GET /documents/download` pattern. The wire no longer carries forgeable ids and the cross-checks are gone; authorization is unchanged (`OperatonTask:VIEW` on the task remains the gate), and it is not slower (the task lookup already happened). Internal-only endpoints called solely by this plugin's own components, shipped together with the backend — **no host action required**.

- **Internal Formio components are no longer offered in the form builder's component palette.** `epistola-override-builder` and `epistola-process-link-selector` are editForm-only widgets (they render inside the document-preview component's settings), and `epistola-retry-form` is part of the plugin's auto-deployed retry form — none are meant to be dropped onto a form directly. They're now hidden from the palette (`hideFormioComponentFromBuilder` overrides their static `builderInfo` to `false`); they still render wherever already present and inside editForms. The author-facing `epistola-document` and `epistola-document-preview` remain available.

- **Hardened the parallel-correlation auto-wiring and made it respect the disable flags.** Follow-up to the parallel `generate-document` fix, addressing risks found in review:
  - **Disable safety**: when `epistola.enabled=false` the engine SPI is never registered and none of the catch-event/correlation beans exist (they all live inside the conditional auto-configuration) — now covered by `EpistolaCatchEventAutoWiringConfigTest`.
  - **New escape-hatch sub-flag** `epistola.catch-event-auto-wiring.enabled` (default `true`): drops only the `ProcessEnginePlugin` + `BpmnParseListener` beans so correlation falls back to declarative `epistolaWaitFor` mappings, without disabling the whole plugin — recovery path if a future Operaton breaks the SPI.
  - **Ambiguous-pairing validation**: `EpistolaProcessDefinitionValidator` now emits `AMBIGUOUS_CATCH_EVENT` when two or more `generate-document` tasks reach the same `EpistolaDocumentGenerated` catch event (which the auto-wiring can't disambiguate), surfaced on the admin validations tab.
  - **Locator cleanup**: the jobPath-named locator variable is removed once a catch-event-pattern branch is woken with a terminal status, so it no longer accumulates one variable per generation on long-lived/high-volume instances. (Pure variable-pattern processes keep it — self-heal depends on it there.)
  - **CI coverage of the auto path**: new `EpistolaAutoWiringCorrelationIntegrationTest` exercises the real parse-listener → start-listener → resolver → correlation chain against a standalone engine (no Testcontainers), so a regression or SPI break is caught in CI rather than only by the full-app E2E.
  - **Robustness**: the catch-event link resolver no longer caches "not ready" results (process links not yet loaded would otherwise poison a definition until restart), and the catch-event start listener now swallows-and-logs any failure so it can never break an unrelated process's catch-event entry (it is attached to every message catch event in the app).
- **Upgraded Valtimo from `13.21.0` to `13.32.0` (backend + frontend).** Bumped Spring Boot `3.5.12 → 3.5.14` to match the 13.32 dependency BOM (the 13.27 line aligned Hibernate/MySQL/Groovy to the 3.5.14 BOM for HIGH-severity CVEs); Kotlin, Java 21, Gradle 9.2, Node 22 and Angular 19 are all unchanged. The full 13.22→13.32 changelog was reviewed and is additive — no Plugin SDK, PBAC, process-link, or correlation API broke, and the plugin compiled and tested clean against 13.32 with no source changes. The declared **compatible floor stays at 13.21**: only the exact build/test pins moved to 13.32, while the frontend `peerDependencies` keep `^13.21.0` and `COMPATIBILITY.md` keeps `>=13.21.0, <14` — the plugin only uses Valtimo APIs stable across that range, and all backend Valtimo dependencies are `compileOnly` (the host supplies the runtime). The dev test-app additionally pulls in the new `@valtimo/teams` package (introduced with the 13.23 Team concept) that `@valtimo/case`/`@valtimo/task` now depend on; this is a test-harness dependency only and does not affect the published plugin.
- **The BPMN validation admin tab now shows when it last ran, how often it refreshes, and what it covers.** The `/admin/validations` endpoint returns a `BpmnValidationReport` (last-checked timestamp + scan interval + violations) instead of a bare violation list, and the tab renders a "Last checked …" line, an "automatically re-checked roughly every N min" note (N derived from the configured cron schedule), and a caveat that only the **latest** deployed version of each process definition is checked — older versions with running instances may have problems that aren't shown. The validator now records a `lastCheckedAt` timestamp on every scan (null until the first scan completes after startup).
- **The BPMN validator now scans on a wall-clock cron schedule instead of a per-instance fixed delay, so scans stay aligned across cluster nodes.** Previously each node ran on a `fixedDelay` timer anchored to its own startup, so nodes drifted apart. It now uses `@Scheduled(cron = "${epistola.validator.cron:0 */10 * * * *}", zone = "${epistola.validator.zone:UTC}")` — every node fires on the same boundary (e.g. `:00`, `:10`, …), keeping scans within a minute of each other (NTP-synced clocks assumed). To avoid all nodes hitting the engine/DB at the same instant, each node then defers the actual scan by a small random 1–25s jitter, scheduled via the shared Spring `TaskScheduler` (not slept, so no scheduler thread is blocked). A one-shot scan on `ApplicationReadyEvent` still populates a freshly (re)started node immediately rather than waiting for the next boundary. Replaces the old `epistola.validator.interval-ms` / `epistola.validator.initial-delay-ms` properties.
- **The BPMN race-safety validator no longer re-parses unchanged processes on every 10-minute tick.** Results are cached per deployed process-definition version, keyed by the version-specific `ProcessDefinition.getId()` plus a signature of its `generate-document` process links. A deployed version's BPMN is immutable, so a cache entry can only go stale when a new version is deployed (new id) or its generate-document links change — both invalidate the entry and trigger a fresh parse; everything else reuses the cached result.

### Changed

- **The `download-document` action now chooses where it materializes the PDF via a `storageTarget` property** (`temporary-resource`, default, or `process-variable`), implemented as a strategy per target — see [ADR 0001](docs/adr/0001-download-document-content-storage.md). This fixes the original bug where the action stored the PDF inline as a Base64 **string**: Operaton keeps string variables in a `varchar(4000)` column (`ACT_RU_VARIABLE`/`ACT_HI_VARINST.TEXT_`), so any real document's Base64 overflowed it; because the download ran in the same transaction as the message correlation that resumed the process, the failure rolled the correlation back and the `EpistolaDocumentGenerated` catch event never completed (the process stalled). It also fixes the follow-on leak — Valtimo serializes the **entire** variable map into the task-detail response, so inline content shipped the whole private document to the browser on every task open.
  - **`temporary-resource` (default)** stores the PDF in Valtimo's temporary resource storage and writes only the small **resource id** to `resourceIdVariable` — no `varchar` limit, nothing private in the task response, and exactly the input `documenten-api:store-temp-document` consumes to persist the document durably in the Documenten API (concept → definitief). Available only when `temporary-resource-storage` is on the classpath (it is in standard Valtimo); selecting it without that backend fails with a clear error.
  - **`process-variable`** stores the raw bytes inline in `contentVariable` (a `byte[]`, so no `varchar` limit). Supported but not the default — best for small, non-sensitive documents, since the bytes are serialized into the task response and persist in process state.
  - The plugin imposes **no hard dependency** on temporary-resource storage or on the Documenten API; the `documentId` remains persisted in `epistolaResult`, so the bytes can always be re-downloaded from Epistola within its retention window. For pure view-only needs, do not use this action — the Formio download component already streams the PDF via `GET /documents/download`.

### Fixed

- **The document preview, download, and retry-form components now work in every Valtimo task-open flow** — previously they failed with _"… is only available from within a user task"_ when a task was opened via the task-list / case-detail view. Those components need the active `taskInstanceId` to authorize their backend calls (`OperatonTask:VIEW`). They obtained it only from `EpistolaTaskContextInterceptor`, which sniffs the per-task `GET /api/v2/process-link/task/{id}` call — but Valtimo's task-list/case-detail flow bulk-fetches process links (`GET /api/v1/process/{pid}/tasks/process-link`) and renders the form inline, so that call never fires and the task id was never captured. (The direct task-open flow, which the dev test-app uses, _does_ fire it — which is why it worked locally but not at customers.) No task-pinning value (task id, activity id, or execution id) is reachable by a Formio component at runtime in that flow, so the id is now delivered through **server-side form prefill**, which runs in all flows:
  - A plugin `ValueResolverFactory` (`EpistolaTaskValueResolverFactory`, prefix `epistola:`) exposes the current task's `taskId`/`executionId`/`taskDefinitionKey` at prefill time (Valtimo passes the `OperatonTask` itself as the resolver's `VariableScope`).
  - Each Epistola task component (preview, download, retry-form) **embeds** a hidden carrier child in its own Formio schema (`PREFILLED_TASK_ID_CARRIER`, `properties.sourceKey: "epistola:taskId"`, `persistent: false`), so dropping the component is enough — no separate field for the form author to add. Valtimo's prefill (which recurses into nested components) fills the carrier; the Formio wrappers read it back (`readPrefilledTaskId`, a deep scan of the prefilled form) and forward it to the Angular component, which fails closed when it's absent.
  - **Existing forms are surfaced and repairable from the admin page** (transitional, removed in 1.0.0): a new **Forms** tab lists forms whose Epistola components are missing the carrier (forms authored before this change), with a per-form and a "repair all" button that injects the carrier (`EpistolaFormCarrierRepairService` + `/admin/forms/carrier-issues` and `/admin/forms/{id}/repair-carrier`). Repair persists for form-management-authored forms; classpath-deployed forms are reconciled to their source on the next boot (flagged "read-only" in the list) and need the carrier added to their source instead. A startup auto-migration was prototyped but dropped: Valtimo's case-definition deployment reconciles classpath forms _after_ all `ApplicationReadyEvent` listeners, so a startup pass can't reliably order after it.
  - The previous brittle mechanism has been **removed entirely**: the HTTP interceptor that sniffed the per-task `GET /api/v2/process-link/task/{id}` call (`EpistolaTaskContextInterceptor`, `EpistolaTaskContextService`, the URL matcher, and the `HTTP_INTERCEPTORS` registration) is gone, since server-side prefill now delivers the task id in every task-open flow. Hosts no longer need `withInterceptorsFromDi()` for this plugin.
  - ⚠️ **Breaking for forms authored before this release.** With the interceptor gone, the task id reaches the components **only** through the embedded carrier. Forms that already contain an Epistola preview/download/retry-form component (dropped before this version) have no carrier, so they will fail with _"… is only available from within a user task"_ until the carrier is added. **Remediation: open the admin page's Forms tab and click "repair" (or "repair all")** — this injects the carrier into the affected forms. Classpath-deployed (read-only) forms are reconciled to their source on each boot, so for those re-drop the component in the form source instead. No interceptor fallback is provided.
  - Authorization is unchanged and stays **exact per-task** (`requireTaskBoundTo`); a forged task id resolves to a task the caller has no `VIEW` on. Covered by `EpistolaTaskValueResolverFactoryTest`, `EpistolaFormCarrierRepairServiceTest`, and `prefilled-task-id.spec.ts`.
- **The document-preview Formio component no longer fires spurious `POST /preview` requests that returned a `400` from Epistola's data validation** (`required property '…' not found`). Two causes are addressed:
  - **On task completion**: the preview wrapper (`PreviewWithOverrides` in `epistola-document-preview.formio.ts`) attached a debounced `change` listener that was never torn down, so a pending preview could outlive form teardown and run with reset data. It now removes the `change` listener and clears the pending debounce in `detach()` (re-armed on the next `attach()`, since Formio re-attaches components on every redraw), and the debounce callback bails out when the form is `submitting`/`submitted` or the component has been destroyed.
  - **On task open**: the component used to fire an immediate preview with no input overrides (before the form data had been mapped in), which 400'd for any template with required fields, then a second override-driven request ~1.5s later replaced it. The preview is now **override-driven**: when an override mapping is configured it waits for the mapped form data and shows a "Complete the form to generate a preview" placeholder until then — firing no request — and the initial override compute runs immediately (no 1.5s delay) so pre-filled forms paint without lag. Previews without an override mapping still load straight from the base data on open. Decision logic lives in `shouldLoadPreview` (`preview-utils.ts`).
  - The live preview-as-you-type behavior is unchanged. Covered by `epistola-document-preview.formio.spec.ts` and `preview-utils.spec.ts`.
- **Parallel `generate-document` branches in one case now each get their own document** (previously only one of N succeeded; the others failed downstream with `Document variable '…' is null or empty`). The async-result correlation keyed on a single, process-level `epistolaJobPath` variable plus `correlateAllWithResult()`, so parallel branches (e.g. the subsidy case's 3 documents) overwrote each other's key — only the last branch's result correlated, and that one result woke **all** waiting catch events while only its own result variable got the `documentId`. Approaches that store the key on a per-branch execution are unreliable because Operaton does not guarantee the execution-tree shape. The fix anchors the correlation key on the catch event's own subscription execution, so it is **topology-independent**:
  - `generate-document` includes the composite `jobPath` in its rich result object (`{requestId, status, documentId, errorMessage, jobPath}`) and writes a locator variable (named by the jobPath, value = result-variable name) so a completion resolves the result variable from just `(tenantId, requestId)`.
  - Each `EpistolaDocumentGenerated` catch event carries its job's jobPath as the execution-local `epistolaWaitFor` token. The plugin **auto-attaches** a start listener (`EpistolaCatchEventStartListener`) that pins it from `${<resultVar>.jobPath}` — no per-catch-event configuration needed. Wiring uses only Camunda's sanctioned parse SPI (a `ProcessEnginePlugin` registering a `BpmnParseListener` that just attaches the listener); the source result variable is resolved at runtime via the **public** BPMN model + `ProcessLinkService` (`EpistolaCatchEventLinkResolver`). Authors can override by setting `epistolaWaitFor` themselves (a `camunda:inputParameter` `${<resultVar>.jobPath}`); the plugin never overwrites it.
  - The result collector correlates with a single indexed query — the subscription whose own `epistolaWaitFor` equals the completed job's jobPath — updates that branch's result variable, and wakes **only** that execution (`runtimeService.messageEventReceived`), never a broadcast. The admin "Pending Jobs" reconcile reads `epistolaWaitFor`.
  - Verified end-to-end against a real Operaton engine by `EpistolaParallelCorrelationIntegrationTest` (parallel gateway, multi-instance subprocess, sequential, self-heal), plus unit tests for the start listener and the link resolver. (`EpistolaPlugin.generateDocument`, `EpistolaMessageCorrelationService`, `EpistolaCatchEventStartListener`, `EpistolaCatchEventLinkResolver`, `EpistolaCatchEventParseListener`, `EpistolaProcessEnginePlugin`)
- **An `EpistolaDocumentGenerated` catch event no longer stalls if its result arrived before it subscribed.** That window only opens when there is an async boundary between `generate-document` and the catch event (advised against, and flagged by the validator): the result collector finds no subscription, updates the result variable, and acks — previously the catch event would then subscribe and wait forever. The auto-attached start listener now registers an after-commit callback (`EpistolaMessageCorrelationService.selfHeal`) that, once the subscription is committed, delivers an already-terminal result so the branch continues. The normal synchronous flow is unaffected (the result is still `PENDING`, so the callback is a no-op). Verified by `EpistolaParallelCorrelationIntegrationTest.selfHealCompletesACatchEventWhoseResultArrivedBeforeItSubscribed`.

### Changed (test-app)

- **The Epistola `EpistolaDocumentGenerated` intermediate catch events now use `camunda:asyncAfter="true"`** in all test-app demo processes (permit, objection, subsidy, bulk-letters, single-document). This commits the message correlation in its own transaction at the catch event, so a failure in a downstream task can no longer roll back the correlation and leave the event uncompleted — a defence-in-depth complement to the `download-document` storage fix.
- **The demo `download-document` process-links now use the default `temporary-resource` target**, writing a resource id to `…ResourceId` variables instead of inline content.

## [0.9.4] - 2026-06-08

### Fixed

- **The "Process Link" selector in the Document Preview Formio component settings was always empty.** The `epistola-process-link-selector` loads its options from `GET /admin/usage` and then keeps only the generate-document links, but it filtered on `actionKey === 'generate-document'` while the backend serializes the prefixed plugin action definition key `epistola-generate-document` (the value in `EpistolaAdminService.EPISTOLA_ACTION_KEYS`). The filter matched nothing, so the dropdown offered no process links to select. The action key is now a shared constant `GENERATE_DOCUMENT_ACTION_KEY` (`process-link-selector.util.ts`) used by both the component and its tests — the spec previously re-implemented the filter with the same wrong string, so it passed against data that never occurs in production.

## [0.9.3] - 2026-05-21

### Changed

- **Bumped `app.epistola.contract:client-spring3-restclient` 0.3.0 → 0.6.0.** Tracks the latest catalog protocol (code lists, fonts, release fingerprints, stencil `version`, `importCatalog.authoredMode`, `ImportCatalogResponse.aborted`). All additions are either additive or only apply to resource types this plugin doesn't ship (`stencil`, `font`, `codeList`), so existing `schemaVersion: 2` classpath catalogs — including the test-app's `municipality-demo` — remain valid wire input to a 0.6.0 server with no migration.

### Fixed

- **Switching catalogs in the generate-document configurator left a stale template/variant selection.** When the catalog changed, the cascade reset `selectedTemplateId$` and `variantIdValue` in component state, but the underlying `<v-select>`'s internal `selected$` BehaviorSubject kept the previous id (its `setDefaultSelection` ignores empty-string defaults, so binding `[defaultSelectionId]=""` is a no-op). The next v-form emission re-applied the stale id under the new catalog, and the cascade fired template-detail and variant lookups against the wrong catalog. Wired a `clearSelectionSubject$` for both the `templateId` and the explicit-mode `variantId` selects, triggered on catalog/template change. (Note: even with this fix, picking a template that doesn't exist in the chosen catalog still yields a server-side 404 — that root cause is the Epistola Suite `listTemplates` endpoint ignoring the `catalogId` path parameter, tracked separately.)
- **Process-link "Voltooien" button stayed disabled on `epistola-generate-document` even with every visible field filled in.** The `filename` `v-input` and the explicit-mode `variantId` `v-select` were nested inside a `<div class="field-with-fx">` wrapper introduced together with the JSONata fx toggle (commit `43390a4`). Valtimo's `<v-form>` collects child values via `@ContentChildren(InputComponent)` / `@ContentChildren(SelectComponent)` without `descendants: true`, so anything wrapped in a `<div>` is silently invisible to its `valueChange` emission. `filename` is in `baseComplete`, so the form's `valid` event could never go to `true`; `variantId` was technically optional in `baseComplete` but would also have been silently dropped from any saved configuration in explicit mode. Both fields now drive component-level state (`filenameValue`, `variantIdValue`) via the direct `(valueChange)` / `(selectedChange)` outputs, decoupling them from v-form's name-keyed collection. The fx toggles also call `revalidate()` so the button responds the instant the user switches modes. Also removed the orphaned `requiredFieldsStatus` field and `onRequiredFieldsStatusChange` handler (dead since the JSONata mapping builder replaced the tree mapping builder — no child component emits the status any more).
- **Catalog import NPE'd on Epistola Suite ≥ 0.5.3.** The server's `importCatalog` signature treats `authoredMode` as non-nullable Kotlin (even though the OpenAPI spec documents it as optional with default `MERGE`), so leaving the field off the multipart body produced `NullPointerException: Parameter specified as non-null is null: parameter authoredMode` on every startup sync and admin redeploy. `EpistolaServiceImpl.importCatalog` now always sends `authoredMode=MERGE`, which matches what the spec says the default would have been — no behaviour change for callers, no new plugin-config knob.
- **Keycloak `groups` claim now contains full hierarchical group paths.** The `epistola-suite` client's Group Membership protocol mapper in `docker/keycloak/valtimo-realm.json` had `full.path: "false"`, so the JWT emitted bare role names (e.g. `tenant-manager`) instead of the full paths Epistola Suite's `GroupMembershipParser` requires (e.g. `/epistola/platform/tenant-manager`). On a fresh demo Keycloak deploy, users in `/epistola/platform/tenant-manager` and other platform/tenant groups gained no effective permissions until an operator manually patched the realm. Flipped to `"true"`.
- **`service-account-epistola-suite` granted `realm-management:manage-clients`.** Epistola Suite now auto-provisions its own Group Membership mapper when `keycloakAdmin.ensureGroups=true` (Epistola Suite ≥ 0.22.0), which requires this realm-management role. The other three existing roles (`manage-users`, `view-users`, `query-users`) are kept for the group-management pathway.

### Caveat

Keycloak realm imports only run on first realm creation. Existing demo deployments (e.g. the test cluster) are not automatically migrated by changes to this file — the test cluster has already been patched manually via the Keycloak admin UI. A clean re-deploy of the demo Keycloak (e.g. PVC wiped) would apply the new settings from scratch.

## [0.9.2] - 2026-05-20

### Fixed

- **Configurator-installed hosts loaded the plugin without its menu, HTTP interceptor, or Formio components.** `EpistolaPluginModule` declared the menu service, `EpistolaTaskContextInterceptor`, and the `ENVIRONMENT_INITIALIZER` that registers the `epistola-document` / `epistola-retry-form` / `epistola-document-preview` / override-builder / process-link-selector Formio components only inside `forRoot()`. The Valtimo Configurator's generated `AppModule` emits `imports: [EpistolaPluginModule]` (no `.forRoot()` call — confirmed against `valtimo-platform/notify-nl-plugin` and `valtimo-platform/freemarker-plugin`, both of which have plain-class modules with no `forRoot`), so none of those providers activated. Providers are now declared at module level, and `forRoot()` is kept as a no-op passthrough so the existing README setup keeps working. `EPISTOLA_ENABLED` gating is unchanged — the `isEpistolaEnabled()` short-circuit still runs inside the initializer, so disabling the plugin still hides every surface it claimed to hide. **Import only at the application root**: the interceptor uses `multi: true`, so importing the module into a lazy/feature module in addition to `AppModule` would fire it twice per request.

## [0.9.1] - 2026-05-19

### Fixed

- **Image build of consumer apps failed with a missing `jsonata` module.** The published `@epistola.app/valtimo-plugin` package only declared `jsonata` as a _peer_ dependency (an earlier attempted fix), but the plugin's compiled code imports `jsonata` at runtime (`jsonata-converter.ts`, `jsonata-editor.component.ts`). Peer dependencies are not reliably auto-installed in a host app / Docker image build, so the consuming Valtimo app ended up without `jsonata` and the build broke. `jsonata` is a leaf utility with no shared-singleton constraint (unlike Angular / `@valtimo/*`, which are legitimately peers), so it is now a regular `dependency` and is installed transitively by any package manager. Also added to ng-packagr's `allowedNonPeerDependencies` so the library build keeps it external (same handling as `tslib`).
- **Admin page always showed the running plugin version as `vdevelopment`** — even in released/production builds. `EpistolaAdminService.getPluginVersion()` reads `Implementation-Version` from the jar manifest, but the backend `jar` task never wrote that attribute, so the lookup returned `null` in every build and fell back to `"development"`. The `jar` task now populates `Implementation-Title` / `Implementation-Version` from the Gradle project version (release builds pass `-Pversion=<x.y.z>`), and the fallback also treats Gradle's `unspecified` default as `development` so plain local builds keep the friendly label instead of showing `vunspecified`.

## [0.9.0] - 2026-05-18

### Added

- **Dangling-reference detection in the admin usage overview** — the Epistola admin "usage" tab now flags `epistola-generate-document` process links whose configured `catalogId` / `templateId` / literal `variantId` no longer resolve in the connected Epistola installation (typical cause: a classpath catalog whose startup auto-deploy failed). Surfaced through the existing per-row `problems` list (red Carbon tags + `table-warning` rows + per-config problem count) — no new endpoint, DTO, or frontend code. Best-effort by design: only literal references are checked (a `variantId` written as a JSONata expression is resolved at runtime and skipped — the literal/expression rule mirrors the frontend `isExpression()` in `preview-utils.ts`); checks are ordered catalog → template → variant so a missing catalog doesn't also report a misleading "template not found"; and when Epistola is unreachable **no** false "does not exist" problems are emitted (reachability stays the `/health` tab's job). Reference lookups are memoized per usage-scan (`EpistolaReferenceCache`) so a many-link scan does not produce an N+1 Epistola API storm. Assumes the value stored on the link equals the Epistola-returned `*.id` (true today — the runtime action passes the same token as the API path parameter); pinned by unit tests so a future Epistola API change is caught.
- **Plugin version & changelog on the admin page** — a new global "Changelog" tab on the admin overview shows the running plugin version (`/versions`, also still the header badge) and renders the plugin's `CHANGELOG.md`. The CHANGELOG is copied into the plugin jar at build time (`processResources` → classpath `epistola/CHANGELOG.md`); a server-side parser turns the "Keep a Changelog" markdown into structured releases/sections/items, served as JSON by the new PBAC-gated `GET /api/v1/plugin/epistola/admin/changelog`. The service returns an empty list rather than failing if the file is absent or unparseable. The tab lazy-loads on first open and renders the structure with the existing Carbon components — versioned release blocks, section headings, item lists — so **no markdown-renderer dependency is added to the published frontend library**.
- **Manual classpath-catalog redeploy from the admin page** — each plugin configuration's detail view gains a "Catalogs" tab listing every catalog found on the application classpath (slug + version), each annotated with its **live status in that configuration's Epistola installation** — `IN_EPISTOLA` (a catalog with this slug exists), `NOT_IN_EPISTOLA` (Epistola was reached and has no such catalog — redeploy it), or `UNKNOWN` (Epistola unreachable; never falsely reported as missing). Existence is queried from Epistola at request time rather than tracked in memory, so it stays accurate across backend restarts and `templateSyncEnabled=false` configs. (Epistola's catalog API exposes no version, so this is presence, not a version comparison — tracked by epistola-suite#433 and follow-up #45.) Each row has a **Redeploy** button that force-pushes that catalog's classpath resources to Epistola, bypassing both the version-skip check that the startup sync applies **and** the `templateSyncEnabled` plugin property (explicit operator action — only `epistola.enabled` still gates it). Lets an operator recover from a failed/skipped startup auto-deploy without restarting the app, and closes the loop with the dangling-reference detection above. New PBAC-gated endpoints `GET /api/v1/plugin/epistola/admin/configurations/{configurationId}/catalogs` and `POST …/catalogs/{slug}/redeploy` (`EpistolaAdministration:MANAGE`); a failed import returns HTTP 502 with the error in the response body, an unknown configuration/slug returns 400. The success response carries Epistola's per-resource `installed` / `updated` / `failed` / `total` counts, shown inline next to the button. The automatic startup sync (`EpistolaCatalogSyncTrigger`) is unchanged.

## [0.8.0] - 2026-05-08

### Added

- **Variable pattern: rich-object `resultProcessVariable`** — `generate-document` now writes a `Map<String, Object>` (`requestId` / `status` / `documentId` / `errorMessage`) to the configured result variable instead of a plain requestId string. The result collector updates the same variable in-place when the job finishes, so processes that don't use a catch event can read the result later via JUEL: `${epistolaResult.status == 'COMPLETED'}`, `${epistolaResult.documentId}`. Companion var `epistolaResultVariableName` (internal) tracks the user-configured name so the collector knows where to write. Catch-event users continue to get the legacy `epistolaStatus` / `epistolaDocumentId` / `epistolaErrorMessage` per-execution variables — both patterns work in the same process.
- **Manual catch-event reconcile** — admin UI now exposes a Reconcile button per row in the Pending Jobs tab, and the new `POST /api/v1/plugin/epistola/admin/pending/{executionId}/reconcile` endpoint asks Epistola for the current job status and re-runs message correlation if the job is in a terminal state (`COMPLETED` / `FAILED` / `CANCELLED`). Returns 409 when the job is still in flight so the UI can show "still pending" without treating it as an error. PBAC-gated by `EpistolaAdministration:MANAGE`. Use this when a result was already acked from Epistola but no BPMN execution received the message — typically caused by a race between the result-collector and the engine commit.
- **BPMN race-safety validator** — the new `EpistolaProcessDefinitionValidator` walks every deployed process definition that uses `generate-document` and only warns when the BPMN unambiguously uses the catch-event pattern (the `generate-document` service task's forward graph reaches an `EpistolaDocumentGenerated` catch event through any number of gateways without an intervening wait state). When that pattern is detected, two violations can fire: `PLATFORM_ASYNC_AFTER_ON_SERVICE_TASK` (the platform-injected `expression="${null}" + asyncAfter="true"` signature; remediation is to add an explicit `camunda:expression` to the service task), and `ASYNC_BEFORE_ON_CATCH_EVENT` (user-set asyncBefore on the catch event). The validator never modifies a BPMN. Violations are logged at WARN, surfaced via `GET /api/v1/plugin/epistola/admin/validations`, and shown in the admin page's BPMN-validatie tab. Soft-warn only — does not fail deployment. Scan cadence configurable via `epistola.validator.initial-delay-ms` (default 30s) and `epistola.validator.interval-ms` (default 10min).
- **0-match correlation visibility** — `EpistolaMessageCorrelationService` now logs a WARN when a result is acked from Epistola but no BPMN execution matched the jobPath, including the suggested remediation. This was previously silent.
- **`EpistolaAdministration` PBAC resource type** with a `MANAGE` action. Operators can revoke the default `ROLE_ADMIN` grant and assign the action to a more specific role to lock down the Epistola admin pages independently of the global admin role.
- **`epistola-document` Formio component** — unified after-generation document UX. One component, one configuration, three presentations via the `display` property: `inline` (PDF in a panel), `button` (download button only), `both` (default — inline preview with a download icon in the header). Configured with `documentIdVariable` / `tenantIdVariable` (defaults `epistolaDocumentId` / `epistolaTenantId`). Distinct from `epistola-document-preview`, which dry-runs the data mapping; use `epistola-document` to display the actual PDF that was previously generated.
- **CycloneDX SBOMs** for backend and frontend on every release. The backend SBOM is generated by the `org.cyclonedx.bom` Gradle plugin and published to Maven Central as the `cyclonedx` JSON classifier on `app.epistola.valtimo:epistola-plugin`. The frontend SBOM is generated by `@cyclonedx/cdxgen` (root `pnpm sbom:plugin` script) and bundled inside the published npm tarball at `sbom.json`. Both SBOMs are also attached to each GitHub Release as `epistola-plugin-<version>-{backend,frontend}-sbom.json`.

### Removed (breaking)

- **Per-execution result scalars: `epistolaStatus`, `epistolaDocumentId`, `epistolaErrorMessage` are no longer set.** The `EpistolaMessageCorrelationService` correlation builder used to call `setVariable` for each of these on the catch-event execution; that's gone. The single source of truth for result data is now the configured `resultProcessVariable` (which holds the rich `Map` — see the Added entry above). **Migration**: replace BPMN gateway expressions and Formio `pv:` keys:
  - `${epistolaStatus == 'COMPLETED'}` → `${<resultProcessVariable>.status == 'COMPLETED'}` (e.g. `${epistolaResult.status == 'COMPLETED'}`).
  - `pv:epistolaStatus` → `pv:<resultProcessVariable>.status` (Valtimo's `pv:` prefix supports JSON-pointer dot-notation on read, so this works without any other change).
  - Same pattern for `epistolaDocumentId` and `epistolaErrorMessage`.

### Changed (breaking)

- **Action keys renamed with `epistola-` prefix.** The `@PluginAction` keys are now `epistola-generate-document`, `epistola-check-job-status`, and `epistola-download-document` (was `generate-document` / `check-job-status` / `download-document`). Reason: Valtimo's built-in Documenten API plugin uses the unprefixed `download-document` key, and Valtimo's process-link UI dispatches to a configurator by action key alone — same key on two plugins picked the wrong configurator. Migration: update every `*.process-link.json` file's `pluginActionDefinitionKey` to the new prefixed name. Existing process-link rows in the DB referencing the old keys will fail to bind to the renamed actions; re-deploy or re-create them.
- **`download-document` action's `documentIdVariable` config field renamed to `documentVariable`.** With the rich-object pattern, the variable now holds the full result `{requestId, status, documentId, errorMessage}`, not just an id — the old name was misleading. Affects the BPMN action's `actionProperties` JSON, the `GET /api/v1/plugin/epistola/documents/download` query parameter, the `epistola-document` Formio component's `@Input()`, and the `download-document-configuration` UI. Migration: rename the property/attribute in your process-link JSONs and Formio forms. The `check-job-status` action keeps `documentIdVariable` (different semantics — it's a scalar output target).
- **`resultProcessVariable` shape: String → `Map<String, Object>`** (see Added entry above). Migration: BPMNs that read this variable directly (e.g. `${epistolaRequestId}`) must switch to `${epistolaRequestId.requestId}` (or rename the variable). The `check-job-status` action's `requestIdVariable` reader and the `download-document` action's `documentIdVariable` reader both accept both shapes transparently for backward compatibility — no changes needed there. In-flight processes that submitted on the previous version still hold the legacy String value and continue to work via the type-tolerant readers; new submissions use the rich object.
- **Frontend defaults shifted to `epistolaResult`**: the `epistola-document` Formio component, the `download-document` action's `documentIdVariable`, and the `check-job-status` action's `requestIdVariable` now default to `epistolaResult` (was `epistolaDocumentId` / `epistolaRequestId`). Existing process-link configurations are unaffected (defaults only apply when no explicit value is set); new authoring picks up the canonical name.
- **`GET /api/v1/plugin/epistola/documents/{documentId}/download`** is replaced by **`GET /api/v1/plugin/epistola/documents/download`** with a new query-param shape: `taskId`, `caseDocumentId`, `documentIdVariable`, `tenantIdVariable`, optional `filename` and `disposition` (`attachment` or `inline`). The PDF id is no longer accepted on the wire — the backend reads it from a named process variable on the caller's task.

### Removed (breaking)

- **`GET /api/v1/plugin/epistola/preview-sources`** and the corresponding auto-discover preview mode. The `epistola-document-preview` Formio component now requires `sourceActivityId` to be configured at design time and shows "Preview is not configured" otherwise. `EpistolaPluginService.getPreviewSources(...)` and the `PreviewSource` model are deleted. Forms that depended on auto-discover must be updated to pin a `processDefinitionKey + sourceActivityId`.
- **`epistola-preview-button` and `epistola-download` Formio components** — superseded by the unified `epistola-document` (above). Forms using either should switch to `epistola-document` and remove any `value: {documentId, tenantId}` hand-wiring.

### Security

- **PBAC-based authorization for plugin endpoints** ([#38](https://github.com/epistola-app/valtimo-epistola-plugin/issues/38)). User-task-bound endpoints (`POST /preview`, `GET /retry-form`, `GET /documents/{id}/download`) now require a `taskId` and check `OperatonTask:VIEW` via Valtimo's `AuthorizationService`. The Formio preview/download/retry-form components inject `TaskDetailContentComponent` from `@valtimo/task` and read `taskInstanceId$` to forward the task id to the backend. Admin endpoints (`/admin/**`) require a new PBAC permission, `EpistolaAdministration:MANAGE`, granted by default to `ROLE_ADMIN` via a seeded `*.permission.json` changeset. Configurator endpoints (`/configurations/**`, tooling listings, `/evaluate-mapping`) remain HTTP-gated by `ROLE_ADMIN` (the de-facto process-link author authority in Valtimo 13.21).
- **Tightened linkage on preview / retry-form**. The earlier check (VIEW on the supplied task) was bypassable: a caller with VIEW on any task could pivot by sending their own `taskId` plus a foreign `processInstanceId` / `documentId`. `POST /preview` and `GET /retry-form` now additionally require that the request's `processInstanceId` equals the task's process instance and that the request's `documentId` equals the task's process business key (the Valtimo case-document UUID). Mismatch returns 403.
- **Tightened linkage on download**. The download endpoint now resolves the Epistola PDF id and tenant id from named process variables on the caller's task instead of accepting them on the wire. Callers send `taskId`, `caseDocumentId`, and the _names_ of the process variables that hold the PDF id and tenant id (`documentIdVariable`, `tenantIdVariable` — defaults `epistolaDocumentId` / `epistolaTenantId`). The backend verifies same-task / same-case binding, reads the live variables, and proxies the download. Forge-proof by construction: a caller cannot supply an id that isn't already in their process. No history-variable dependency.
- **Renovate at the repo root** — replaces the stale `test-app/backend/renovate.json` with a single root config covering the whole monorepo. Groups Valtimo backend/frontend, Angular, Spring Boot, Testcontainers, and GitHub Actions so coupled deps move together; weekly schedule; manual review only (no auto-merge); SHA-pinned GitHub Actions via `helpers:pinGitHubActionDigests`.
- **Trivy filesystem scan** in CI on every PR and push to `main`. SARIF results upload to the GitHub Security tab under category `trivy-fs`. Report-only (`exit-code: 0`) — gating will flip to fail on `CRITICAL` once the baseline is clean.
- **Trivy image scans** on each release for both `demo-backend` and `demo-frontend` GHCR images, with SARIF upload under `trivy-image-{backend,frontend}` categories. Report-only.
- **CodeQL workflow** for `java-kotlin` (backend plugin) and `javascript-typescript` (frontend plugin + test-app) running weekly and on every PR/push to `main`.

## [0.7.0] - 2026-05-06

### Fixed

- **Drop the `@PostConstruct` reconcile in `EpistolaResultCollectorRunner`** to silence the harmless-but-noisy `BeanCurrentlyInCreationException` warning at boot. The Epistola plugin bean is still being constructed when `@PostConstruct` runs on the runner, so `pluginService.createInstance(cfg)` couldn't resolve `epistolaPluginFactory` and every config logged a WARN. The first reconcile is already covered by Valtimo's `PluginsDeployedEvent` and the `@Scheduled` tick (which fires immediately when the scheduler starts). Removing the early call has no functional impact — both rescue paths fire within the same boot — and produces a clean startup log.

### Changed

- **Kick the result collector after a successful generate** — `EpistolaPlugin.generateDocument` calls `EpistolaResultCollectorRunner.kickFor(baseUrl, apiKey, tenantId)` after a successful submit. If the collector has backed off into idle mode, this brings the next poll forward to ~3s instead of waiting out the full backoff (up to 30s by default). Threshold-guarded inside the contract collector — no-op when polling fast, useful only after periods of inactivity. Two new properties on `epistola.result-collector`: `kickIntervalMs` (default 3000) and `backoffMultiplier` (default 3.0; was effectively 2.0 hard-coded in the contract client). The new multiplier gives the sequence 1s → 3s → 9s → 27s → 30s (capped at `maxIntervalMs`), reaching idle mode faster — the kick is the safety net that gets us back to fast polling when work arrives.
- **Replaced per-execution polling with the `ResultCollector`** — `PollingCompletionEventConsumer` is gone. A single `EpistolaResultCollectorRunner` bean now manages one `ResultCollector` per active Epistola plugin configuration, streaming completed/failed results from `POST /tenants/{tenantId}/generation/collect` (NDJSON, gzip-negotiated, sequence-acked). Each result is delivered to the existing `EpistolaMessageCorrelationService` with the same `epistola:job:{tenantId}/{requestId}` job path encoding, so deployed BPMN processes don't need any changes. Significantly lower load on the suite (no per-request status polling). Reconciliation runs on bean startup, on a 60s scheduled tick, and reactively on Valtimo's `PluginsDeployedEvent` and `PluginConfigurationDeletedEvent` so plugin config changes from the UI are picked up immediately on the handling instance.
- **`generate-document` action passes `routingKey` on submit** — derived from `EpistolaResultCollectorRunner.routingKeyFor(...)` so the result is routed back to this Valtimo node's collector partition. Falls back to the server's default (hash of `requestId`) when the collector hasn't completed its first poll yet.
- **Bumped contract dependency to `0.3.0`** (`app.epistola.contract:client-spring3-restclient`).
- **Plugin properties** — `epistola.poller.*` removed; replaced by `epistola.result-collector.*` (`enabled`, `batch-size`, `min-interval-ms`, `max-interval-ms`, `reconcile-interval-ms`, `kick-interval-ms`, `backoff-multiplier`).
- **Outbound requests now carry the contract's identity headers** (`User-Agent: epistola-contract/<version> valtimo-epistola-plugin/<version>` and `X-EP-Node-Id`), required by contract v0.3+. `EpistolaApiClientFactory` wires the `ClientIdentity` interceptor onto every `RestClient` it builds.

### Removed

- **`PollingCompletionEventConsumer`** and the `EpistolaCompletionEventConsumer` interface — superseded by `EpistolaResultCollectorRunner`.
- **`EpistolaCallbackResource`** — the placeholder webhook endpoint (`POST /api/v1/plugin/epistola/callback/generation-complete`) is gone. The suite never wired this path; with the result collector in place there's no remaining need for a push channel.
- **Plugin properties `epistola.poller.enabled` / `epistola.poller.interval`** — see `Changed` above for the replacements.

### Notes

- Authentication uses `X-API-Key` — the `api-key` `authMethod` in the contract's `ConsumerDto` enum. JWT (self-signed + OAuth) registration paths defined in the contract are not used by this plugin; switching to those would be a follow-up.

### Fixed

- **CI workflows install mise before invoking `./gradlew`** — the gradle wrapper is a `mise exec -- gradle` shim, but `mise` isn't on the GitHub Actions runner by default, causing `exec: mise: not found`. Replaced `actions/setup-java@v4` with `jdx/mise-action@v2` in `ci.yml` and `release.yml` (build + publish-backend + docker-backend jobs). Mise installs JDK and Gradle from `.mise.toml`, so CI now mirrors local dev exactly. `setup-gradle@v4` is kept for build caching.

- **`jsonata` declared as a frontend peer dependency** — `frontend/plugin/src/lib/utils/jsonata-converter.ts` imports `jsonata` at runtime, but the package was only listed under `devDependencies`. Consumers installing `@epistola.app/valtimo-plugin` would crash at runtime with a missing module. Added to `peerDependencies` (kept in `devDependencies` so the lib's own build/tests still work standalone, same pattern as `@valtimo/*`). Also added the dep to `test-app/frontend/package.json` so local dev mirrors a real consumer.

- **`monaco-editor` declared as a frontend peer dependency** — the JSONata editor reaches Monaco via `(window as any).monaco`, populated from the bundle the consumer copies into `assets/monaco-editor` via their `angular.json`. Monaco was never imported as an ES module so the plugin compiled fine without it, but every consumer still needs it in their `node_modules` for the asset-copy step to work and for the editor to function at runtime. Same packaging-bug shape as `jsonata` above; the README already called it a "peer dependency" but the package.json didn't reflect that. Added to `peerDependencies` with a permissive range (`>=0.40.0` — covers every Monaco release with the language API surface the plugin uses). Not added to `devDependencies` because the plugin's own build/tests don't import it.

- **Gradle 9 compatibility** — Bumped `foojay-resolver-convention` from `0.8.0` to `1.0.0`. The old version referenced `JvmVendorSpec.IBM_SEMERU`, which was removed in Gradle 9, causing settings evaluation to fail when loading the project.
- **`:test-app:backend` testcontainers resolution** — Added `testImplementation(platform(libs.testcontainers.bom))` so the renamed Testcontainers 2.x artifact (`org.testcontainers:testcontainers-postgresql`) gets a version. Previously the Valtimo BOM only managed the old 1.x name (`org.testcontainers:postgresql`), so the dependency resolved with no version and the build failed.

### Changed

- **Plugin logo replaced with the actual Epistola logo** — `frontend/plugin/src/lib/assets/epistola-logo.ts` previously held a placeholder document icon. Replaced with the wax-sealed letter-stack logo from the Epistola website (`website/public/logo.svg`), inlined as a base64 data URL so the library has no extra asset to ship. The logo surfaces in the Valtimo plugin configuration UI via `pluginLogoBase64` in `epistola.specification.ts`.

- **`docker/docker-compose.yml`** — `ved-epistola-server` host port aligned to `4000:4000` (was `4010:4000`). The host port now matches Epistola's documented default, so `http://localhost:4000` works directly in the plugin's `baseUrl` field without a one-port offset. Mock service kept on `4010:4010` so it stays distinct from a real server when both are referenced in docs.

- **Removed JSONata signature reflection hack** — `JsonataMappingService` no longer uses reflection to null out `Jsonata.JFunction.signature` after construction. The previous code passed the function name into the JFunction constructor's `signature` parameter (causing a parse failure), then patched it via reflection. Now passes `null` for the signature directly — same effect, no dependency on JSONata internals.
- **Custom JSONata function failures now throw instead of silently returning `null`** — `JsonataMappingService.registerCustomFunctions` previously caught all exceptions from custom function bodies and returned `null` to JSONata, producing wrong template data with no error trail. It now rethrows as `ExpressionEvaluationException` (unwrapping `InvocationTargetException` so the original cause is preserved). Brings custom functions in line with how the rest of the evaluator already surfaces errors.

### Added

- **Save-time JSONata validation in the generate-document configurator** — A new backend endpoint `POST /api/v1/plugin/epistola/validate-jsonata` parses the configured expressions (dataMapping, filename when in `fx` mode, variantId when in `fx` mode, expression-mode variant attribute values) without evaluating them, returning per-field syntax errors. The configurator calls it on Save and blocks the emit when any expression fails to parse, surfacing field-level errors at the top of the form. Catches malformed expressions like `{ broken` or `$pv.foo &` at design time. Note: this is a syntax check only — typos in variable names and runtime type errors still surface at process execution.

- **Enumerable `$pv` in JSONata** — `LazyProcessVariableMap` now supports an optional bulk loader so JSONata expressions like `$keys($pv)`, `$each($pv, ...)`, and `$pv.*` see actual variables instead of an empty map. Per-key access (`$pv.someVar`) keeps its existing lazy resolver path. `EvaluationContext` gained a `processVariableEnumerator` builder method; both `EpistolaPlugin` (using `execution::getVariables`) and `PreviewService` (overlaying input overrides on top of `runtimeService.getVariables(processInstanceId)`) now supply it.

- **Input-level overrides for document preview** — The `epistola-document-preview` Formio component can now be configured with a specific process link (`processDefinitionKey` + `sourceActivityId`) and an override mapping that feeds form field values into the template as `$doc`/`$pv` overrides before JSONata evaluation. This enables live document previews while users are still filling in forms.
- **Dynamic JSONata expressions for variantId, variant attributes, and filename** — All single-value fields in the generate-document action now support JSONata expressions (e.g., `$pv.letterType`, `"besluit-" & $doc.name & ".pdf"`). Added `evaluateScalar()` to `JsonataMappingService` for scalar evaluation with the same `$doc`/`$pv`/`$case` bindings. Frontend adds `fx` toggle buttons to switch between dropdown/plain input and expression mode.

### Documentation

- **README**: pinned the Maven coordinate (`app.epistola.valtimo:epistola-plugin:0.6.0`) instead of the bare `${epistolaVersion}` placeholder, and noted that the Sonatype search index can lag behind a release while the metadata at `repo1.maven.org` is authoritative.
- **README**: documented `EPISTOLA_BASE_URL` / `epistola.base-url` in the Backend setup. Was previously only inferrable from `docker/docker-compose.yml`.
- **README**: added a _Required configuration_ section listing every per-instance plugin property (`baseUrl`, `apiKey`, `tenantId`, `defaultEnvironmentId`, `templateSyncEnabled`) with type/required/secret flags, and the application-wide `epistola.*` Spring property tree (`enabled`, `base-url`, `retry-form.*`, `result-collector.*`) sourced from `EpistolaProperties`. Pointer to `test-app/backend/src/main/resources/config/app.pluginconfig.json` for first-time setup against the demo Epistola server.
- **README**: added a _Catalog auto-deployment_ section documenting the classpath layout (`config/epistola/catalogs/{slug}/catalog.json` + `resources/template/*.json`) that the plugin scans on `ApplicationReadyEvent` when `templateSyncEnabled=true`, with the `:test-app:backend` `municipality-demo` catalog as a worked example. The feature was previously undocumented.
- **README**: rewrote _Local Development with Docker_ as _Running the Epistola server_ with three explicit options — Docker-only quickstart against the published GHCR image (no clone needed), compose with a profile reference table, and bring-your-own — plus a ports table that disambiguates the real server from the mock, and default credentials including the deterministic demo API key.
- **README**: clarified the _Frontend_ setup. Combined the plugin and `monaco-editor` install commands; flattened the angular.json snippet to a single asset entry; called out that `gzac-frontend-template` v/13+ already includes that entry; replaced the misleading `export const pluginSpecifications` line with the actual `PLUGINS_TOKEN` provider pattern that wires the plugin into Valtimo.

### Removed

- **ValueResolverService dependency** — The `doc:`, `pv:`, `case:`, `template:` prefix resolution system has been replaced by JSONata. All single-value expression evaluation now uses `JsonataMappingService.evaluateScalar()`.
  - `OverlayMap` — layered Map implementation that checks overrides first, delegates to the base map (e.g. lazy-loaded document) for non-overridden paths. Supports recursive overlay for nested structures.
  - `PreviewRequest.inputOverrides` — new field with `{ "doc": {...}, "pv": {...} }` structure, applied before JSONata evaluation (vs existing `overrides` which is applied after).
  - `EpistolaProcessLinkSelectorComponent` — Formio component for selecting a generate-document process link from a dropdown populated via the admin usage API.
  - `EpistolaOverrideBuilderComponent` — Formio component with table-based builder (simple mode) and raw JSON editor (advanced mode) for configuring input variable overrides.
  - Custom Formio registration for the preview component that listens to `root.on('change')` events, computes input overrides from the mapping configuration and current form data, and pushes them to the Angular component.

## [0.6.0] - 2026-04-28

### Changed

- **Split EpistolaPluginResource into three focused controllers** — `EpistolaTemplateResource` (template browsing), `EpistolaGenerationResource` (generation, preview, download), and `EpistolaToolingResource` (process variables, suggestions, expression functions). Each controller only declares the dependencies it needs. No endpoint URLs changed.

### Added

- **JSONata data mappings** — Replaced the custom prefix-based data mapping system (`doc:`, `pv:`, `expr:`) with [JSONata](https://jsonata.org), a purpose-built JSON transformation language. Supports conditionals, string operations, array filtering/projection, loops, and custom functions. Data mappings are now stored as a JSONata expression string.
  - `JsonataMappingService` evaluates JSONata with `$doc`, `$pv`, `$case` context variables
  - Custom functions from `ExpressionFunctionRegistry` bridged as native JSONata functions
  - Frontend JSONata editor with real-time syntax validation (via `jsonata` npm package)
  - Visual mapping builder (Simple mode) for field-by-field mapping that generates JSONata
  - Simple/Advanced mode toggle — switch between visual builder and raw code editor
  - Removed: `DataMappingResolverService`, `DataMappingResolver`, `TemplateMappingValidator`, old tree components
- **Expression function support** — Custom expression functions (`EpistolaExpressionFunction` interface) with typed `execute(ExpressionContext, ...)` methods, overload support, and reflection-based discovery. Built-in `formatDate` and `str` functions. Available as `$formatDate(...)`, `$str(...)` in JSONata expressions.
  - `GET /api/v1/plugin/epistola/expression-functions` REST endpoint listing available functions with typed signatures
- **Pending jobs overview** — Admin page now shows all process instances currently waiting for an Epistola document generation result. Displays configuration, process, activity, and request ID per waiting job. New backend endpoint `GET /api/v1/plugin/epistola/admin/pending`.
- **Process link export** — Download button on each process link row in the admin page that exports the full configuration as a `.process-link.json` file matching Valtimo's auto-deploy format. New backend endpoint `GET /api/v1/plugin/epistola/admin/export/{processLinkId}`.
- **oxfmt and oxlint** — Added code formatting (oxfmt) and linting (oxlint) with configuration matching epistola-suite. Managed via mise and npm devDependencies. CI pipeline updated with `lint:check` and `format:check` steps.
- **Project governance files** — CODE_OF_CONDUCT.md (Contributor Covenant v2.1), CONTRIBUTING.md (development workflow, commit conventions, testing requirements), SECURITY.md (vulnerability reporting policy), PR template, CODEOWNERS, issue templates (bug report, feature request, documentation), labels.yml, and root .editorconfig
- **Updated CLAUDE.md** — Replaced outdated "Current State" (which claimed mock implementations) and "Implementation Phases" (all completed) with accurate documentation of the actual implementation, known limitations, and test coverage gaps
- **Epistola admin page** (`/epistola`) — Dedicated admin page under the Admin > Other menu with a card-based overview per plugin configuration showing connection status, tenant ID, server version, usage count, and problem count. Click a card to drill into its details showing case definition, process links, actions, and issues with clickable links to the process link configuration page. Plugin version shown as badge. Deep linking via `?configurationId=<uuid>`. Self-registers route and menu item via `forRoot()` — no host app configuration needed.
- **Admin REST endpoints** — `GET /api/v1/plugin/epistola/admin/health` (connection check), `/versions` (plugin version), `/usage` (process link overview with problem detection). Requires `ROLE_ADMIN`.
- **NL and EN translations** for all admin page labels

## [0.5.2] - 2026-04-21

### Fixed

- Enable content hash output hashing (`outputHashing: "all"`) in Angular build to prevent browser cache issues across deployments
- Add cache-control headers to nginx config — `no-cache` for `index.html`, `immutable` with 1-year expiry for hashed assets
- Apply same cache-control headers to Helm chart nginx ConfigMap
- Update Helm chart `appVersion` from 0.4.0 to 0.5.2

### Reverted

- Revert case definition version bump to 2.0.0 — the issue was browser cache, not process link deployment

## [0.5.0] - 2026-04-20

### Added

- **Catalog selector in generate-document configuration** — Users now pick a catalog first, then see templates from that catalog. Added `CatalogInfo` domain record, `getCatalogs()` service method, `GET /configurations/{configurationId}/catalogs` REST endpoint, `createCatalogsApi()` factory method, and frontend catalog dropdown with cascading template loading.
- **Catalog sync on startup** — Plugin scans classpath `config/epistola/catalogs/*/catalog.json`, builds ZIP in memory, and POSTs to Epistola's catalog import endpoint on startup. Replaces the old per-template sync.

### Changed

- **Refactored generate-document configuration reactive state** — Replaced 6 independent `init*Loading()` methods with a single cascading reactive chain in `initCascade()`. Prefill values now seed the cascade (catalog → templates → variants/fields) instead of running as a separate subscription with timing issues. Added `distinctUntilChanged()` to prevent duplicate loads. Template fields are guaranteed loaded before data mapping prefill is applied.
- **BREAKING: Added `catalogId` to all catalog-scoped API calls** for Epistola contract 0.2.0 compatibility. Affected methods: `getTemplates`, `getTemplateDetails`, `getAttributes`, `getVariants`, `submitGenerationJob`, `previewDocument`.
- Added `catalogId` as a required action property on the `generate-document` plugin action.
- Added `catalogId` and `catalogName` fields to `TemplateInfo` domain record.
- REST endpoints for templates, attributes, variants, and validate-mapping now require a `catalogId` query parameter.
- Removed `defaultEnvironmentId` from test plugin config — catalog-imported templates use latest published version fallback.

### Removed

- Template import (`importTemplates`) — superseded by catalog import.
- Unused `ImportTemplatesRequest`/`ImportTemplatesResponse` imports.

## [0.4.5] - 2026-04-09

### Fixed

- **Restored `versionTag` in case definitions** — Valtimo requires `versionTag` to be present (NPE on startup without it). The `dev` profile is needed to allow draft case definitions.
- **Default Spring profile** changed back to `"dev"`. The `dev` profile enables draft case definition support which is required for auto-deployed case definitions with `versionTag`.

### Helm Chart (0.3.2)

- Default `springProfilesActive` changed from `""` to `"dev"`.

## [0.4.2] - 2026-04-08

### Fixed

- **CI: upgraded Node.js from 22 to 24** across all workflows. Node 24 ships with npm 11 which is required for OIDC trusted publishing. This removes the need for the broken `npm install -g npm@latest` self-upgrade step.

## [0.4.1] - 2026-04-08 [BROKEN]

_Release pipeline failed — npm and Docker frontend artifacts not published. Use v0.4.2 instead._

## [0.4.0] - 2026-04-08

### Added

- **Configurator metadata**: Added `valtimo-configurator-metadata.json` for plugin auto-discovery by the Valtimo Configurator.

### Changed

- **Case definitions: removed `versionTag`** from all 5 demo case definitions. The `versionTag` field triggered Valtimo's draft gate, which required `dev`/`test`/`inttest` profiles. Removing it allows case definitions to load in any environment.

### Helm Chart (0.3.0)

#### Changed

- **Per-credential secret references**: Each secret now supports `secretRef` to reference an existing K8s Secret directly, enabling secret reuse across apps (e.g. the same Keycloak client secret used by both valtimo-demo and epistola). Credentials without a `secretRef` are auto-generated when `value` is empty. Legacy `existingSecret` is still supported.
- **Auto-generate secrets**: All secret values are auto-generated with random values when left empty, removing hardcoded defaults from `values.yaml`. Generated secrets are persisted across Helm upgrades.
- **Default Spring profile** changed from `"demo,dev"` to `""` (empty). Neither profile was defined in the application.
- **Consolidated secrets management**: All secret values grouped under `secrets:` block. Client secrets injected into Keycloak realm at runtime via init container.
- **Chart release workflow**: Triggered by `chart-X.Y.Z` GitHub Release tag.

#### Removed

- **RabbitMQ support** from the valtimo-demo chart. Use `backend.extraEnv` if needed.
- **`externalEpistola.clientSecret`** value path (was never wired into any template).

#### Breaking Changes

- `secrets.*` values changed from flat strings to objects with `value` and `secretRef` fields. Migration: `secrets.keycloakClientSecret: "val"` → `secrets.keycloakClientSecret.value: "val"`
- `backend.existingSecret` → `secrets.existingSecret`
- `backend.keycloak.backendClientSecret` → `secrets.keycloakClientSecret`
- `backend.valtimo.pluginEncryptionSecret` → `secrets.pluginEncryptionSecret`
- `backend.operaton.adminPassword` → `secrets.operatonAdminPassword`
- `keycloak.adminPassword` → `secrets.keycloakAdminPassword`
- `epistola.keycloak.clientSecret` / `externalEpistola.clientSecret` → `secrets.epistolaClientSecret`
- `backend.rabbitmq.*` removed
- `backend.springProfilesActive` default changed from `"demo,dev"` to `""`

## [0.3.3] - 2026-04-02

### Added

- **Attribute key dropdown**: Variant attribute keys are now suggested via a dropdown populated from the tenant's attribute definitions (fetched from the new `GET /attributes` endpoint), with a "Custom..." option for free-text entry.
- **Required/Preferred toggle**: Each variant attribute entry now has a checkbox to mark it as "Required" (variant must match) or "Preferred" (preferred but not mandatory), matching the Epistola API's `VariantSelectionAttribute.required` field.
- **Attributes REST endpoint**: `GET /configurations/{id}/attributes` returns the attribute definitions for the plugin's tenant, used to populate the key dropdown.
- **Frontend test setup**: Jest configured for the plugin library with tests for attribute key filtering logic.

### Fixed

- **Preview error popup in retry form**: The "corrigeer documentgegevens" form was missing the `X-Skip-Interceptor: 422` header, causing Valtimo to show an error popup on preview failures. Now handled inline like the document preview component.
- **Generic preview error messages**: Preview failures now show the actual error from the Epistola API instead of the generic "Preview rendering failed: Failed to preview document".

### Changed

- **`GenerateDocumentConfig.variantAttributes` format**: Changed from `Record<string, string>` to `VariantAttributeEntry[]` (array of `{key, value, required}`). Existing configurations using the old format are automatically converted at both frontend prefill and backend execution time — no migration needed.
- **Epistola client**: Upgraded from 0.1.19 to 0.1.20 (adds `AttributesApi`).

## [0.3.2] - 2026-04-01

### Added

- **Feature toggle**: Added `epistola.enabled` property (default: `true`) to enable/disable the entire plugin. Set `epistola.enabled=false` in `application.yml` to prevent all Epistola beans from being registered. **Note:** before disabling, remove any existing Epistola plugin configurations and process links — the frontend has no equivalent toggle, so stale configurations would show in the UI but fail on all API calls.
- **`EpistolaPluginModule.forRoot()`**: New static method that auto-registers all Formio custom components via `ENVIRONMENT_INITIALIZER`, eliminating the need for manual `registerEpistola*Component()` calls in the consuming app's module constructor.

### Fixed

- **Stale data in select boxes**: Refactored template, variant, environment, and template fields loading to use `switchMap` instead of nested subscribes. Previously, changing a template selection could cause an old API response to overwrite the correct data if it arrived late.
- **Process variable dropdown not appearing**: Added `ChangeDetectorRef.markForCheck()` to `DataMappingTreeComponent` and `FieldTreeComponent` so that asynchronously loaded data (template fields, process variables) properly triggers re-rendering under `OnPush` change detection.
- **PV select not reflecting prefill value**: Replaced `[selected]` bindings on native `<option>` elements with `[(ngModel)]` on the `<select>` element for reliable two-way binding under `OnPush` change detection.
- **Browse mode select boxes empty on prefill**: Saved data mappings using slash notation (e.g. `doc:/objector/address/street`) were not recognized by the ValuePathSelector configured with `notation="dots"`. Added `normalizeToDots()` to convert slash-notation paths to dot notation before passing them as `defaultValue`, so the dropdown correctly matches and selects the prefilled path.
- **ValuePathSelector dropdown snap-back**: Binding a method call as `[defaultValue]` caused the setter to re-fire on every change detection cycle, snapping the dropdown back to manual mode. Fixed by caching the default value in `ngOnChanges` and binding to a property.

### Changed

- **Extracted `ValueInputComponent`**: The duplicated 3-mode input pattern (browse/pv/expression) used by both scalar fields and array source mapping is now a single reusable `<epistola-value-input>` component. Houses all browse-default caching, notation normalization, and PV dropdown logic.
- **Split `FieldTreeComponent` into focused sub-components**: The 322-line monolith handling SCALAR, OBJECT, and ARRAY field types is now a thin dispatcher delegating to `ScalarFieldComponent`, `ObjectFieldComponent`, and `ArrayFieldComponent`.
- **Consolidated `AsyncResource<T>` state pattern**: `GenerateDocumentConfigurationComponent` replaced 12 BehaviorSubjects (4 x {data, loading, error}) with 4 `AsyncResource<T>` subjects, reducing state management boilerplate.
- **Simplified `DataMappingTreeComponent`**: Converted from Observable inputs (`templateFields$`, `disabled$`, `prefillMapping$`) with manual subscriptions to plain `@Input()` properties with `ngOnChanges`, removing the need for `ChangeDetectorRef`, `destroy$` subject, and subscription management.

## [0.3.1] - 2026-03-30

### Added

- **`epistola-document-preview` Formio component**: Standalone inline preview panel that can be dropped into any user task form or case tab to show a live PDF preview of what a document will look like before the generate-document service task runs. Auto-discovers all generate-document process links from running process instances — shows a dropdown when multiple documents are available, auto-selects when there's only one. No configuration needed beyond an optional `label` field option.
- **`GET /api/v1/plugin/epistola/preview-sources`**: New REST endpoint that discovers previewable document sources for a given Valtimo document by querying active process instances and their generate-document process links.

### Changed

- **Release workflow**: Switched from `[release]` commit message trigger to GitHub Release-based trigger (`on: release: published`). Version is now explicitly set via the release tag instead of auto-calculated from `build.gradle.kts`. Failed releases can be re-triggered via `workflow_dispatch`.
- **Preview error handling**: Render failures return 422 instead of 502; frontend shows friendly info message instead of red error popup; preview logging downgraded to DEBUG level.

## [0.3.0] - 2026-03-30

### Added

- **Unit tests for FormioFormGenerator**: Tests for scalar/object/array field generation, default values, validation, humanizeLabel, description tooltips, and null/empty children edge cases.
- **Unit tests for PreviewService**: Tests for `deepMerge` (flat merge, nested merge, array replacement, null override) and `generatePreview` error paths (missing context, process not found, link not found, ambiguous activity, render failure).
- **Unit tests for RetryFormService**: Tests for explicit source activity, auto-discovery of single generate-document link, ambiguous/missing activity errors, process not found, missing templateId, no document ID, and source activity from task local variable.
- **Unit tests for EpistolaFormAutoDeployAspect**: Tests for case filter modes (all, none, regex), deduplication per case, null case definition ID, and missing form resource handling.

- **Taken tab on vergunningsaanvraag case**: Added a "Taken" (tasks) tab to the permit case so users can see and interact with open tasks directly from the case view.
- **User task fallback for failed document generation** ([docs](docs/user-task-fallback.md)): When a render fails, a user task shows a dynamically generated form with all template fields prefilled. The user edits and resubmits. The `generate-document` action automatically detects edited data — no separate retry action needed. Includes:
  - `epistola-retry-form` custom Formio component
  - `FormioFormGenerator` for template-to-Formio conversion
  - `DataMappingResolverService` for REST-context value resolution
  - `EpistolaFormAutoDeployAspect` for per-case form auto-deployment
  - Auto-discovery of source generate-document activity
  - `GET /api/v1/plugin/epistola/retry-form` endpoint
  - Example retry loop in the permit-confirmation BPMN process
- **Upgraded Valtimo** from 13.4.1 to 13.21.0 (backend + frontend)
- **Upgraded Spring Boot** from 3.4.1 to 3.5.12
- **Upgraded Epistola Client** from 0.1.13 to 0.1.18
- **Building blocks analysis** ([docs](docs/building-blocks-analysis.md)): Research on using Valtimo building blocks to encapsulate the generate-document + retry flow as a reusable call activity. Documents benefits, limitations (no custom config components, no global form selection in UI), and recommendations.

### Improved

- **Extracted RetryFormService**: Moved retry form orchestration logic out of EpistolaPluginResource into a dedicated service, reducing the controller from 9 to 4 dependencies.
- **Centralised process variable constants**: Created `EpistolaProcessVariables` constants class, eliminating magic strings across plugin actions, REST endpoints, and services.
- **Hardened EpistolaFormAutoDeployAspect**: Graceful handling of missing form resources (warns instead of crashing), regex validation at construction time, broader exception catching.
- **Added API error handling in generateDocument**: Epistola API failures now set status/error process variables before re-throwing, enabling the retry flow to trigger.
- **OnPush change detection**: Added `ChangeDetectionStrategy.OnPush` to all plugin frontend components for better performance.
- **Extracted countRequiredMapped utility**: Deduplicated required-field counting logic shared between FieldTreeComponent and DataMappingTreeComponent.
- **Moved HTTP logic to service**: EpistolaRetryFormComponent now uses EpistolaPluginService instead of injecting HttpClient directly.
- **Loading error feedback**: Generate-document configuration form now shows inline error messages when templates, variants, environments, or template fields fail to load.

### Fixed

- **valtimo-demo chart**: Made container-level `securityContext` configurable via values (`securityContext`, `initSecurityContext`, `initKeycloakSecurityContext`) instead of hardcoding `runAsUser: 1000`. This allows OpenShift deployments to null out `runAsUser` for restricted-v2 SCC compatibility. Chart version bumped to 0.2.0.

### Changed

- Public demo access now requires self-registration and supports passkey (WebAuthn) login. Static demo users were removed and Keycloak bootstrap credentials are auto-generated per installation.
- **Template definitions now require explicit default variant**: All `definition.json` files must include at least one variant with `"isDefault": true`. The Epistola server no longer creates a synthetic default variant automatically.
- Upgraded `epistola-client` from `0.1.11` to `0.1.13` (adds `isDefault` field to `ImportVariantDto`, makes `variants` required)
- Updated Epistola Suite docker image to `0.4.5` (server-side default variant resolution)

### Fixed

- Fixed Epistola tenant ID in plugin config: `demo-tenant` → `demo` to match epistola-suite's DemoLoader
- Removed client-side default variant resolution workaround — Epistola server (`>= 0.4.x`) now resolves the default variant automatically when neither `variantId` nor `attributes` is provided
- Added `epistola.base-url` to `application-dev.yml` to match docker-compose port mapping (`4010:4000`)

### Added

- **Public demo hardening**: Helm chart now exposes `publicUrls.*` and `keycloak.webAuthn.*` values, enables self-service Keycloak registration, provisions a `valtimo-users` group with `ROLE_USER`, auto-generates the Keycloak admin password, and imports WebAuthn-ready authentication flows so visitors can choose between passwords or passkeys.
- **Database Reset Endpoint** (`POST /api/v1/test/reset`): Drops and recreates the public schema, then shuts down the JVM. Designed for Kubernetes-deployed demo environments where pods auto-restart with a clean database. Valtimo and Operaton recreate all tables on boot, and case definitions/BPMN processes are redeployed from config.
- **Periodic Database Reset CronJob** (`database.standalone.reset`): Optional Kubernetes CronJob that drops and recreates selected databases on a schedule (default: every 6 hours), then rolling-restarts specified deployments. Hardcoded protection prevents resetting `keycloak`, `postgres`, `template0`, and `template1`. Disabled by default; enable via `database.standalone.reset.enabled=true` in standalone mode.

- **Template Sync**: Automatic synchronization of template definitions from classpath to Epistola server on startup
  - `templateSyncEnabled` plugin property with frontend checkbox toggle
  - Classpath scanner for `config/epistola/templates/*/definition.json` files
  - Version-based change detection (only syncs changed templates)
  - Bulk import via `POST /tenants/{tenantId}/templates/import` endpoint
  - Handles partial failures (tracks successful imports, retries failed ones on next sync)
- **Template definitions**: 10 government letter templates for all test-app BPMN processes (subsidy, objection, permit, bulk tax, example)
- **Tests**: Unit tests for `TemplateDefinitionScanner` and `EpistolaTemplateSyncService` (version tracking, change detection, partial failure handling)

### Changed

- Upgraded `epistola-client` from `0.1.7` to `0.1.10` (adds import API support)

- **Attribute-Based Variant Selection**: Generate document action now supports selecting variants by attributes instead of explicit variant ID. When configured with `variantAttributes`, the API automatically selects the matching variant based on key-value pairs. Attribute values support value resolver expressions (`doc:`, `pv:`, `case:`), enabling runtime variant selection based on process data (e.g., language, brand).

- **Variant Selection Mode Toggle** (Frontend): Generate document configuration now has a mode toggle between "Select variant" (existing dropdown) and "Select by attributes" (key-value pair inputs). Users can add/remove attribute entries, and values support value resolver expressions.

### Changed

- **`VariantInfo.tags` renamed to `attributes`**: The domain model and frontend interface now use `Map<String, String> attributes` instead of `List<String> tags`, matching the Epistola API v0.1.7 contract. Variant dropdowns display attributes as `key=value` pairs.

- **`EpistolaService.generateDocument()` signature updated**: Added `variantAttributes` parameter (`List<VariantSelectionAttribute>`) and made `variantId` nullable. Either `variantId` or `variantAttributes` must be provided, not both.

- **`GenerateDocumentConfig.variantId` now optional** (Frontend): The config interface allows either `variantId` or `variantAttributes` to be set.

### Dependencies

- Upgraded `app.epistola.contract:client-spring3-restclient` from 0.1.1 to 0.1.7
- Updated mock server image from `0.1.3` to `0.1.7` (docker-compose and Testcontainers)

### Breaking Changes

- `VariantInfo.tags` (List<String>) replaced by `VariantInfo.attributes` (Map<String, String>) — affects any code reading variant tags
- `EpistolaService.generateDocument()` has an additional `variantAttributes` parameter

### Added (previous)

- **Playwright MCP Configuration** (`.mcp.json`): Enables Claude Code to interactively drive a browser for UI verification during development sessions
- **Playwright E2E Test Suite** (`test-app/frontend/e2e/`):
  - Keycloak authentication setup with `storageState` persistence
  - Plugin configuration form tests (field rendering, validation, slug format)
  - Generate document action tests with API mocking for templates/variants/environments
  - Check job status and download document action smoke tests
  - Page objects for plugin management navigation
  - Sequential execution (single worker) for simplicity and reliability

- **Async Completion Polling Infrastructure**: Centralized batch poller that queries Operaton for processes waiting on Epistola document generation, checks job statuses, and correlates BPMN messages when jobs complete. Replaces per-process timer polling with a single scheduled task.
  - `EpistolaCompletionEventConsumer` interface for swappable notification strategies
  - `PollingCompletionEventConsumer` initial implementation using `@Scheduled` batch polling
  - `EpistolaMessageCorrelationService` shared correlation logic for both poller and webhook callback
  - Multi-tenant routing: groups waiting jobs by `epistolaTenantId` and routes to correct plugin config
  - Configurable via `epistola.poller.enabled` (default: true) and `epistola.poller.interval` (default: 30s)

- **Async Documentation** (`docs/async.md`): Documents the recommended BPMN pattern using Message Intermediate Catch Events, the polling architecture, multi-tenant routing, and future evolution path

- **Epistola REST API Integration**: Replaced mock implementation with real API calls using the Epistola client library (`app.epistola.contract:client-spring3-restclient`)

- **Plugin Configuration Properties**:
  - `baseUrl`: Epistola API base URL
  - `apiKey`: API key for authentication (stored securely)
  - `tenantId`: Tenant identifier
  - `defaultEnvironmentId`: Default environment for document generation

- **Generate Document Action** (`generate-document`):
  - Template and variant selection
  - Environment override (uses plugin default if not specified)
  - Data mapping with value resolver support (doc:, pv:, case:, template: prefixes)
  - Output format selection (PDF, HTML)
  - Filename configuration with value resolvers
  - Correlation ID for tracking across systems
  - Returns request ID stored in process variable

- **Check Job Status Action** (`check-job-status`):
  - Polls generation job status from Epistola
  - Stores status in process variable
  - Stores document ID when completed
  - Stores error message when failed
  - Enables polling-based async workflows

- **Download Document Action** (`download-document`):
  - Downloads completed documents from Epistola
  - Stores document content as Base64 in process variable

- **Callback Webhook Endpoint** (`POST /api/v1/plugin/epistola/callback/generation-complete`):
  - Receives generation completion notifications from Epistola
  - Correlates BPMN message `EpistolaDocumentGenerated`
  - Sets process variables: `epistolaStatus`, `epistolaDocumentId`, `epistolaErrorMessage`
  - Publicly accessible for webhooks (no authentication required)

- **REST API Endpoints**:
  - `GET /api/v1/plugin/epistola/configurations/{id}/templates`: List available templates
  - `GET /api/v1/plugin/epistola/configurations/{id}/templates/{templateId}`: Get template details with schema
  - `GET /api/v1/plugin/epistola/configurations/{id}/environments`: List available environments
  - `GET /api/v1/plugin/epistola/configurations/{id}/templates/{templateId}/variants`: List variants for a template

- **Domain Models**:
  - `EnvironmentInfo`: Environment identifier and name
  - `VariantInfo`: Variant identifier, template ID, name, and tags
  - `GenerationJobStatus`: Enum for job states (PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED)
  - `GenerationJobDetail`: Full job status with document ID and error message

- **Integration Tests**: Contract mock server tests for `EpistolaServiceImpl` using Testcontainers with the Epistola Prism mock server

- **Frontend Components**:
  - Updated generate-document configuration with variant, environment, and correlation ID fields
  - Variant dropdown loads dynamically based on selected template
  - Environment dropdown for optional environment override
  - Added translations for all new fields (English and Dutch)

- **CI/CD Pipeline** (GitHub Actions):
  - CI workflow: builds and tests backend (JDK 21 + Gradle) and frontend (pnpm + Node 22) in parallel on every PR and push to main
  - Release workflow: triggered by GitHub Release (`v*` tag), publishes backend to Maven Central, frontend to npm, and Docker images to GHCR
  - Docker images signed with Cosign (sigstore) for supply chain security
  - Version extracted from git tag, no version-bump commits needed

- **Maven Central Publishing**:
  - Configured `com.vanniktech.maven.publish` plugin for streamlined Maven Central publishing via Central Portal
  - Full POM metadata: EUPL-1.2 license, SCM links, developer info
  - GPG signing of all publications
  - Coordinates: `app.epistola.valtimo:epistola-plugin`

- **Recursive JSON Schema Parsing**:
  - `TemplateField` extended with `path` (dot-notation), `fieldType` (SCALAR/OBJECT/ARRAY), and `children`
  - `extractFieldsFromSchema()` now recursively processes nested objects and arrays
  - Nested fields visible in frontend with dot-notation paths (e.g., `invoice.lineItems[].total`)

- **Nested JSON Reconstruction** (`DataMappingResolver`):
  - Converts flat dot-notation keys from value resolvers into nested JSON structures
  - Wired into `generateDocument()` before sending data to Epistola API

- **Template Mapping Completeness Validation**:
  - `TemplateMappingValidator` checks all required fields have non-empty mappings
  - `POST /configurations/{id}/templates/{templateId}/validate-mapping` REST endpoint
  - Frontend blocks save until all required fields are mapped

- **Process Variable Discovery** (`ProcessVariableDiscoveryService`):
  - Discovers variable names from Operaton historic instances
  - Extracts variable names from BPMN model input/output parameters
  - `GET /api/v1/plugin/epistola/process-variables` REST endpoint

- **Input Mode Selector (Browse / PV / Expression)**:
  - Replaced binary fx toggle with a 3-button input mode selector per field
  - Browse mode (⊞): ValuePathSelector for doc:/case: document and case fields
  - PV mode (pv): Dropdown of discovered process variables (with text input fallback)
  - Expression mode (fx): Free-text input for manual expressions and literals
  - Input mode auto-detected from prefilled values (doc:/case: → browse, pv: → PV, other → expression)

- **Per-Item Array Field Mapping**:
  - Array fields with children now support mapping individual item fields when source field names differ
  - Toggle "Map field names per item" to switch between direct collection mapping and per-item mapping
  - Per-item format: `{"_source": "doc:order.items", "product": "productName", "price": "unitPrice"}`
  - `_source` key holds the collection source expression, other entries are template→source field name pairs
  - Backend `DataMappingResolver.mapArrayItems()` transforms source items by renaming fields per the mapping
  - `TemplateMappingValidator` validates both direct string and `_source` array mapping formats
  - Completeness badge shows mapped source + required child fields when collapsed
  - Backwards compatible: existing direct string array mappings continue to work

- **Schema-Mirrored Tree Form for Data Mapping**:
  - Replaced flat table UI with a tree form that mirrors the template schema hierarchy
  - OBJECT fields render as collapsible sections with children indented inside
  - SCALAR fields render as label + input rows (ValuePathSelector or text input)
  - ARRAY fields render as collapsible sections with "map collection to" input
  - fx toggle per field: switches between browse mode (ValuePathSelector) and expression mode (text input)
  - Completeness badge on collapsed sections shows mapped/total required fields
  - Red indicator for required fields without mappings
  - Auto-expand sections with unmapped required fields

- **Frontend Config Components for check-job-status and download-document**:
  - `CheckJobStatusConfigurationComponent`: 4 fields (requestIdVariable, statusVariable, documentIdVariable, errorMessageVariable) with sensible defaults
  - `DownloadDocumentConfigurationComponent`: 2 fields (documentIdVariable, contentVariable) with sensible defaults
  - Both registered in plugin specification with Dutch and English translations

- **Demo Case: UC1 — Vergunningsaanvraag (Permit Application)** (`test-app`):
  - Rich data mapping demo with nested objects (applicant, property) and arrays (activities)
  - BPMN flow: generate confirmation letter → async wait → status check → download → user review
  - FormIO forms for case intake and document review

- **Demo Case: UC2 — Bezwaarprocedure (Objection Handling)** (`test-app`):
  - Two sequential document generation cycles (acknowledgment + decision)
  - Conditional template selection based on assessment outcome (gegrond/ongegrond/deels_gegrond)
  - Demonstrates reuse of `epistolaRequestId` across sequential generation cycles

- **Demo Case: UC3 — Massale Correspondentie (Bulk Generation)** (`test-app`):
  - Multi-instance subprocess for parallel document generation from JSON taxpayer data
  - `CounterIncrementDelegate` for thread-safe success/fail counting
  - Groovy script task for JSON parsing
  - Result overview user task showing generation statistics

- **Demo Case: UC4 — Subsidie Zaakdossier (Subsidy with OpenZaak Archival)** (`test-app`):
  - Parallel gateway generating 3 documents simultaneously (Subsidiebesluit, Financieel Overzicht, Voorwaarden Bijlage)
  - Sequential archival via Documenten API (store) and Zaken API (link to zaak)
  - ZGW plugin specifications registered in frontend (OpenZaak, Documenten API, Zaken API, Catalogi API)

- **Helm**: Replaced Bitnami PostgreSQL subchart with CloudNativePG (CNPG) database support in the valtimo-demo chart. Three modes: `cnpg` (create Cluster), `cnpgExisting` (use existing Cluster), `external` (plain JDBC). Default: `cnpg`. **Breaking**: `valtimoPostgresql` values key replaced by `database`.

### Changed

- **Callback Endpoint Refactored**: `EpistolaCallbackResource` now delegates to `EpistolaMessageCorrelationService` instead of using `RuntimeService` directly. Uses `correlateAllWithResult()` for safe multi-instance correlation.

- **Generate Document Action**: Now stores a composite `epistolaJobPath` variable (`epistola:job:{tenantId}/{requestId}`) automatically (in addition to the user-configured result variable). This single variable atomically encodes both tenant and request ID, avoiding Operaton execution scoping issues where separate variables might not both be visible on downstream executions.

- **Configuration Properties**: Replaced legacy `epistola.valtimo.*` prefix with `epistola.*` and added poller configuration (`epistola.poller.enabled`, `epistola.poller.interval`)

- **Nested Data Mapping Format**: Data mapping now uses a nested structure mirroring the template
  schema hierarchy instead of flat dot-notation keys. Backend value resolution walks the tree
  recursively with batch expression resolution for efficiency. `TemplateMappingValidator` walks
  field tree and mapping tree in parallel by field name.

### Fixed

- **`@PluginActionProperty` values null at runtime**: Added `-parameters` Java compiler flag to `build.gradle.kts`. Without it, Valtimo's reflection-based property resolution couldn't match JSON keys to method parameter names, causing all action properties to be `null`.

- **Null `variantId` causing NPE**: `EpistolaServiceImpl` now defaults `variantId` to empty string when not provided, preventing `NullPointerException` from the Kotlin client's non-null constructor parameter. Note: this is a workaround until the client library makes `variantId` optional (see `docs/todo.md`).

- **Variable scoping for parallel/multi-instance execution**: Replaced separate `epistolaRequestId` and `epistolaTenantId` variables with a single composite `epistolaJobPath` variable. This prevents scoping issues where separate variables might not both be visible on message catch event executions.

- **Polling consumer direct execution targeting**: `PollingCompletionEventConsumer` now uses `runtimeService.messageEventReceived(messageName, executionId, variables)` to target specific executions directly, instead of process-variable-based correlation. Supports local variable fallback to process-instance scope for backwards compatibility.

- **single-document.bpmn message reference**: Replaced `receiveTask` (without messageRef) with `intermediateCatchEvent` with proper `messageEventDefinition` referencing a `bpmn:message` element. The poller queries `messageEventSubscriptionName` and couldn't find tasks without a message reference.

### Dependencies

- Added `app.epistola.contract:client-spring3-restclient:1.0.0` for Epistola API calls
- Uses Testcontainers with `ghcr.io/epistola-app/epistola-contract/mock-server` for API testing
- Added `com.ritense.valtimo:documenten-api` and `com.ritense.valtimo:catalogi-api` to test-app for UC4 OpenZaak integration

## [1.0.0-SNAPSHOT] - Initial Development

### Added

- Initial project structure with backend (Java) and frontend (Angular) plugins
- Basic plugin definition with Valtimo plugin framework integration
- Test application for development
