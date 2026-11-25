# Changelog: test app

The test app (`test-app/`) is the Valtimo application this repository uses to develop, test and
demo the plugin. It is not part of the plugin; changes to the plugin itself are in the
[plugin changelog](../CHANGELOG.md). Test app changes from before this file are below, under the
plugin release they were listed with.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- **A test that a task submission survives the letter composer** (`ComposerSubmissionTransactionE2ETest`,
  [#246](https://github.com/epistola-app/valtimo-epistola-plugin/issues/246)). It completes a task
  inside Valtimo's real transaction, once on a building block's document and once as a case worker
  who may not view the case type, and checks the submission commits. Both failed with
  `UnexpectedRollbackException` before the plugin's fix. It also creates a real `correspondentie`
  case and checks that write-back with no composer in scope still writes the letter's decision to
  the case, through the case definition the stored document carries.
- **A letter about an event, sent ad hoc from the case.** The case type `evenement` gets
  a process "Losse brief" with the letter composer on its start form, offering two letters from
  the bundled catalog `gzac-uitnodiging` (now 1.3.0): badges with a QR code for every participant,
  and an attendance list per daypart. The case supplies the event, the programme and the
  participants; each letter asks the employee for a few fields of its own (the kind of badge and
  what its QR code holds, the daypart and room of the list), with a live preview before anything
  is generated.
- **A reminder to one participant of an event.** The process "Herinnering sturen" on
  `evenement` picks the participant from the case in its start form, takes an optional note and
  previews the reminder before generating it. No code: a select reads the participant list the
  form is prefilled with from the case document (a plain `deelnemers` key), and the
  generate-document mapping finds the chosen
  participant by e-mail address. The letter composer cannot offer a choice whose options come from
  the case, which is why this one has a process of its own. Both built for the TechTalk on the
  GZAC user day of 17 November 2026.
- **The event's sign-up form previews the invitation.** "Aanmelden" on `evenement` shows
  the invitation next to the form while it is filled in. "Ik kom naar" is now a multi-select, so
  the preview gets the chosen dayparts as the same list the invitation is generated from. The
  invitation's contract requires a participant name of at least one character, so the preview
  waits for one instead of rendering a letter addressed to nobody.

- **Instructions for a speaker, as an ad-hoc letter on `evenement`.** The letter composer of
  "Losse brief" offers a third letter (catalog `gzac-uitnodiging` 1.4.3): the case supplies the
  event and the programme, the employee fills in the speaker, session, room and time, and optional
  extra instructions in a WYSIWYG editor (a rich-text block field). The instructions fill the first
  page; the programme follows on a page of its own. Its data contract uses only
  what the Epistola contract editor can edit (no `title`, `enum` or `minLength`), so a field can be
  added to it live; the template already shows a sentence for `presentatieAanleverenVoor` once that
  field exists and is filled. The composer now also asks for optional fields, so a field added that
  way shows up without being marked required.

### Security

- **The demo images take the patched libraries, not Spring Boot's older pins.** The backend's
  Spring dependency management let Boot 3.5.16's BOM override Valtimo's own: Valtimo asks for
  Tomcat 10.1.60 and the image shipped 10.1.55, with its critical CVEs. The build now sets security
  floors for Tomcat, Netty, Jackson, log4j, HttpClient 5, commons-lang3, Guava, BouncyCastle,
  org.json and OpenSearch (2.18 to 2.19.7), each on the line it was already on. Both images also
  apply their base OS's security updates at build time. What is left is recorded in
  `.trivyignore`: Spring Framework 6.2's (fixed only in 7, so Valtimo 14), ognl inside mybatis 3.5,
  a Jackson copy that Operaton's webjar carries as static content and never loads, and Angular 19's.
- **Dev tooling off vulnerable minimatch and @babel/core.** minimatch has no 10.x fix, and 11
  drops what glob 13 uses, so jest's glob moves to 14 with it.

### Changed

- **Runs on Valtimo 13.55.1** (was 13.54.0). Its swagger-ui (5.34.1) no longer carries the
  vulnerable DOMPurify. Where OpenSearch is enabled, 13.55 needs OpenSearch 2.7 or later and a
  one-time "Rebuild / migrate index" on Admin > OpenSearch.

- **The test app is built with Kotlin 2.4 and runs on the standard library it is compiled
  against.** Spring Boot's dependency management pinned every Kotlin artifact to Boot's own 1.9.25,
  so the application ran on the 1.9.25 standard library whatever the Kotlin plugin said. From
  Kotlin 2.4 the pin reaches the compiler as well and breaks the build. `kotlin.version` now follows the Kotlin plugin, and ktlint
  keeps the Kotlin compiler it was built with.

### Removed

- **Publishing the demo images no longer bumps the demo's pins.** Neither the release nor the
  test-app release workflow dispatches the infra pin bump any more, and the test-app release's
  `promote` input is gone. The demo's configuration now lives in the internal infra repository on
  Forgejo (the GitHub copy is archived), and the demo is pinned there by hand.

### Fixed

- **No more 403 toasts for trainees on Admin → Dossiers.** The case-type overview reads the
  case-definition groups, and a case type's page reads its document definition. Both were still
  ROLE_ADMIN-only, so every trainee who opened them saw an error. Trainees may now read both
  (`TrainingHttpSecurityConfigurer`); changing a document definition stays admin-only.
- **"Herinnering sturen" no longer fails for a participant who signed up twice.** With the same
  e-mail address in the case's participant list more than once, the mapping found every match and
  sent the reminder template a list instead of one participant ("/deelnemer: array found, object
  expected"). It now takes the latest sign-up for that address, in both the data and the file name.

## Plugin 1.0.0 - 2026-11-12

### Added

- **Demo app (test-app): case type `evenement` with a personal invitation.** An event case carries
  its programme and participants; the action "Aanmelden" puts a participant on the list
  (`registerParticipant` delegate, since Valtimo cannot append to a list in the case document) and
  generates their invitation from the new bundled catalog `gzac-uitnodiging`, which shows every
  daypart and mutes the ones they did not sign up for. The PDF is kept on the case and appears under
  Documents. The start form carries the GZAC user day 2026 programme as its default. Built for the
  TechTalk on the GZAC user day of 17 November 2026.

## Plugin 1.0.0-alpha.1 - 2026-10-30

### Changed

- **Demo app (test-app): its process links name the catalog in full**
  (`local/demo/municipality-demo`), as a link saved against 2.0 does, so the admin overview no
  longer flags them. The training template (`form-flow-demo`) keeps the bare key on purpose: each
  trainee's clone resolves it to the catalog in the trainee's own tenant.

### Fixed

- **Demo app (test-app): a page longer than the window scrolls again while the demo banner is
  shown.** The banner's wrapper clipped everything below the fold, such as the Epistola admin
  page's list of plugin actions and their problems. The content area now scrolls under Valtimo's
  top bar and side nav, which stay in place.

## Plugin 0.21.0 - 2026-10-14

### Added

- **Test-app release workflow** (`test-app-release.yml`) publishes only the demo images, tagged
  `<last plugin release>-testapp.<run>`, without a plugin release.

### Changed

- **Training (test-app):** trainees work in the shared case types on their own cases only, and
  their own Epistola tenant gets the demo templates.

### Fixed

- **Training (test-app): a trainee sees their own dossier.** Cloned case definitions are now
  activated, including ones provisioned by 0.20.x on the trainee's next request.

## Plugin 0.20.0 - 2026-09-21

### Added

- **Iframe embedding for the demo frontend (test-app only, off by default).** An allowlisted host
  page can embed the app and drive it over `postMessage`, in the same dialect as epistola-suite's
  own embedding. Enable it with `embeddingEnabled` and `embeddingAllowedParentOrigins`
  (`EMBEDDING_ENABLED` / `EMBEDDING_ALLOWED_PARENT_ORIGINS`, or `frontend.embedding.*` in the Helm
  chart).
  - The host navigates by typed resource identity only, never by URL, and navigation goes through
    Valtimo's own route guards.
  - Every misconfiguration fails closed: `frame-ancestors 'none'` and an inert bridge.
  - With authentik, an expired session raises `auth-required` to the host instead of blanking the
    frame.
  - The published plugin library is unchanged. See [docs/embedding.md](docs/embedding.md) and
    [ADR 0005](docs/adr/0005-iframe-embedding-bridge.md).
- **Interactive training facility (test-app only, opt-in via the `training` Spring profile).** A
  user with the Keycloak realm role `ROLE_DEMO` gets a personal copy of the `form-flow-demo` case
  type and their own Epistola plugin configuration on first login, and can administer only their
  own copies.
  - Trainees carry `ROLE_ADMIN`, because Valtimo's admin UI requires it. The backend scopes the
    case-definition, case, task and plugin endpoints to the trainee's own resources, and blocks the
    rest of the admin surface.
  - `epistola.training.epistola-shared-secret` provisions an Epistola tenant per trainee;
    `epistola.training.facility-shared-secret` lets a monitoring tool follow every trainee's
    progress. That credential has full admin access, not read-only.
  - **Known gaps:** nine Valtimo PBAC resource types and the task batch endpoints are not yet scoped
    per trainee, and dossiers are never cleaned up. See
    [docs/training-facility.md](docs/training-facility.md).

## Plugin 0.19.0 - 2026-08-28

### Added

- The test application includes a **Form Flow voorbeeld** case: a two-step Valtimo Form Flow whose
  confirmation step carries an `epistola-document-preview`, completing into a regular `Vervolgtaak`.
  The preview reads the subject typed on the first step through a re-declared hidden carrier, and
  document generation runs after both user tasks so it cannot affect the task transition.
- `FormFlowTransitionE2ETest` boots the real test-app against Testcontainers and walks the
  **Form Flow voorbeeld** case end to end — opening the `Genereer brief` task, completing both form
  flow steps, and asserting the process advanced to the regular `Vervolgtaak` and that the
  completing step's submission data reached the case document. It runs in CI as part of
  `:test-app:backend:test`, so the class of failure that broke this fixture (a rejected
  `doc:/submission` write making `completeTask` throw) is now caught on every PR rather than only
  by the browser suite, which is local-only.

## Plugin 0.18.0 - 2026-08-25

### Added

- The test application now verifies that `$getCustomData()` is invoked during document generation and that its scalar and string-array results reach the submitted template data.
- The test application validates the actual `$getCustomData()` result against its published JSON Schema to prevent metadata/runtime contract drift.
- The test application now registers a schema-backed `$getCustomData()` JSONata function with scalar fields and a string-array field for manual expression-editor testing.

### Changed

- **The test-app Karma suite can use Brave when Chrome is unavailable.** Local headless runs preserve explicit `CHROME_BIN` configuration, then search an optional `BRAVE_BIN` override and common Brave stable, beta, and nightly locations across macOS, Linux, and Windows.

## Plugin 0.17.0 - 2026-07-30

### Fixed

- **The ESM test application's Karma suite now exercises the rendered unified expression editor in CI.** Its CommonJS Karma configuration uses a `.cjs` extension, the obsolete webpack `require.context` bootstrap is removed, and headless Chrome verifies selection persistence, three-view lossless cycling, fixed control sizing, and accessible error relationships instead of relying only on class-level tests.

## Plugin 0.14.0 - 2026-07-23

### Added

- **The test-app backend now has a `demo-remote` profile for using the shared Epistola demo server.** Running with `--spring.profiles.active=dev,demo-remote` points the local Valtimo backend at `https://demo.epistola.app/api`, so local frontend/admin work can exercise a real Epistola server without starting the local Epistola container.

## Plugin 0.13.1 - 2026-07-13

### Added

- **The demo test-app now shows a "demo application" banner.** A fixed header banner in the test-app frontend warns users that this is a demo application whose database may be reset at any time. It can be dismissed per browser session (remembered in `sessionStorage`, so it reappears in a new browser/session), and a transformed wrapper shifts Valtimo's viewport-fixed chrome below the banner so nothing overlaps. This is scoped to the demo test-app only and is not part of the published plugin.

## Plugin 0.13.0 - 2026-07-09

### Added

- **Demo authentication can now target Authentik as a selectable OIDC provider.** Keycloak remains the default, while the test-app frontend can switch to an Authentik-compatible authorization-code + PKCE adapter through its runtime `authProvider` config. The backend adds an `authentik` Spring profile that validates generic OIDC JWTs and maps Keycloak-shaped `realm_access.roles` / `resource_access.*.roles` claims into Valtimo authorities without instantiating the Keycloak Admin client. Docker Compose exposes Authentik OIDC env vars and an alternate frontend config file; the Helm chart adds `auth.provider` and `auth.oidc.*` values for Keycloak-or-Authentik deployments.

### Fixed

- **The test-app frontend now keeps Angular auth modules statically importable.** Keycloak and Authentik modules are both imported by the root app module, while the runtime `authProvider` switch only selects the active auth config/providers. This preserves the local `pnpm start` workflow and the separate `pnpm start:authentik` workflow.
- **Authentik mode now satisfies Valtimo frontend components that still inject `KeycloakService`.** The test-app provides a small compatibility service that delegates to Keycloak in Keycloak mode and exposes Authentik roles/profile/token data in Authentik mode, avoiding undefined `resourceAccess` reads during menu construction.

## Plugin 0.11.0 - 2026-06-22

### Added

- **New example processes in the test-app `example` case** demonstrate more correlation topologies. All reuse the `single-document` generate step + start form and are startable from the UI:
  - `single-document-event-gateway` — an **expected-to-fail anti-pattern**: an event-based gateway racing the `EpistolaDocumentGenerated` message against a `PT30M` timer **cannot correlate**. The gateway places the message subscription on a transient **child** execution while the `epistolaWaitFor` token can only live on the **parent** — they never co-locate, so the message branch never fires and only the timer branch wins. There is no BPMN-level fix; the correct "wait with a timeout" is a receive task / catch event with an interrupting boundary timer (`single-document-receive-task`). The limitation is locked in by `EpistolaAutoWiringCorrelationIntegrationTest`.
  - `single-document-receive-task` — waits with a **receive task** instead of a round intermediate catch event, with an interrupting `PT30M` boundary timer (→ `EpistolaGenerationTimeout`). Auto-wired like a catch event (see the receive-task auto-wiring entry below) — no `epistolaWaitFor` mapping needed.
  - `single-document-async` — `generate-document` has `camunda:asyncAfter`, so the generate step and the catch-event subscription commit in separate transactions; proves the plugin's **self-heal** delivers a result that lands in that window (the catch event is auto-wired, no manual mapping). The BPMN validator flags the async boundary as an advisory note (added latency), not a stall.
  - `parallel-documents` — a **parallel gateway** generates two documents concurrently, each on its own round catch event with a **distinct `resultProcessVariable`** (`resultA` / `resultB`); proves per-branch auto-wired correlation wakes exactly the branch that submitted each completion.
  - `letter-by-type` — a deliberate **anti-pattern reproduction**: an exclusive gateway picks a different template per input, the branches **merge**, and a **single** catch event waits. Because two generate-document tasks with **different** result variables (`resultA` / `resultB`) reach one catch event, the auto-wiring can only pin one — the branch whose variable wasn't pinned gets **no `epistolaWaitFor` token**, so its completion never correlates, the process stalls at the wait, and (because `getPendingJobs()` skips token-less executions) it's **invisible in the admin Pending Jobs tab**. The validator flags it as `AMBIGUOUS_CATCH_EVENT`. **Fix:** give both branches the **same** `resultProcessVariable` (correct for an exclusive merge — only one branch runs), or give each branch its own catch event. See [async.md → Ambiguous (merged) catch events](docs/async.md#ambiguous-merged-catch-events).
  - `three-branch-one-linked` — an exclusive gateway with **three** branches where only branch A has a `generate-document` link (B and C are `${null}` no-op tasks), all merging into one shared catch event. Confirms the **happy path**: going through the linked branch correlates fine (a single generate link feeding one catch event is unambiguous). A positive counterpart to `letter-by-type`.
  - `letter-by-type-fixed` — the **corrected** sibling: identical topology, but both branches write the same `resultProcessVariable` (`epistolaResult`), so the shared catch event resolves unambiguously, the token is pinned whichever branch ran, and the process always completes. Not flagged by the validator. Both branches verified to correlate and complete.

  Documented in [async.md → Adding a timeout](docs/async.md#adding-a-timeout-event-gateway--boundary-timer), [Parallel generation](docs/async.md#parallel-generation), and [Race-safety](docs/async.md#race-safety-keep-the-boundary-synchronous).

### Fixed

- **The test-app backend E2E suite now runs in CI, and a pre-existing failure in it is fixed.** A new `Backend E2E (Testcontainers)` CI job runs `./gradlew :test-app:backend:test` (which boots the real Valtimo app against Testcontainers Postgres + Keycloak; Epistola is mocked) — previously CI compiled the test-app but skipped these tests (`-x test`), so they only ran locally. Wiring them in surfaced a pre-existing failure: the value-resolver E2E started a process with no business key, so Valtimo's user-task-create listeners (`CaseAssigneeTaskCreatedListener` / `CaseTaskTeamAutoAssignListener`) hit `UUID.fromString(null)` / "no document found". The test now creates a real `example` case document and starts the process with its id as the business key, matching how real dossier processes run. (The Playwright UI E2E suites need a full running stack and remain a local / planned-nightly step, not part of PR CI.)

## Plugin 0.10.0 - 2026-06-16

### Changed

- **The Epistola `EpistolaDocumentGenerated` intermediate catch events now use `camunda:asyncAfter="true"`** in all test-app demo processes (permit, objection, subsidy, bulk-letters, single-document). This commits the message correlation in its own transaction at the catch event, so a failure in a downstream task can no longer roll back the correlation and leave the event uncompleted — a defence-in-depth complement to the `download-document` storage fix.
- **The demo `download-document` process-links now use the default `temporary-resource` target**, writing a resource id to `…ResourceId` variables instead of inline content.

## Plugin 0.7.0 - 2026-05-06

### Fixed

- **`:test-app:backend` testcontainers resolution** — Added `testImplementation(platform(libs.testcontainers.bom))` so the renamed Testcontainers 2.x artifact (`org.testcontainers:testcontainers-postgresql`) gets a version. Previously the Valtimo BOM only managed the old 1.x name (`org.testcontainers:postgresql`), so the dependency resolved with no version and the build failed.

## Plugin 0.3.0 - 2026-03-30

### Added

- **Template definitions**: 10 government letter templates for all test-app BPMN processes (subsidy, objection, permit, bulk tax, example)

### Added (previous)

- **Playwright E2E Test Suite** (`test-app/frontend/e2e/`):
  - Keycloak authentication setup with `storageState` persistence
  - Plugin configuration form tests (field rendering, validation, slug format)
  - Generate document action tests with API mocking for templates/variants/environments
  - Check job status and download document action smoke tests
  - Page objects for plugin management navigation
  - Sequential execution (single worker) for simplicity and reliability
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

### Dependencies

- Added `com.ritense.valtimo:documenten-api` and `com.ritense.valtimo:catalogi-api` to test-app for UC4 OpenZaak integration

## Plugin 1.0.0-SNAPSHOT - Initial Development

### Added

- Test application for development
