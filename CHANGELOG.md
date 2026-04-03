# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **Helm: consolidated secrets management**: All secret values are now grouped under a top-level `secrets:` block in `values.yaml`. The `secrets.existingSecret` value allows referencing a pre-existing Kubernetes Secret (e.g., managed by SealedSecrets) instead of the chart-managed one. Client secrets are no longer embedded in the Keycloak realm ConfigMap — they are injected at runtime via an init container.

### Removed

- **Helm: RabbitMQ support removed** from the valtimo-demo chart. Use `backend.extraEnv` if needed.

### Breaking Changes

- `backend.existingSecret` moved to `secrets.existingSecret`
- `backend.keycloak.backendClientSecret` moved to `secrets.keycloakClientSecret`
- `backend.valtimo.pluginEncryptionSecret` moved to `secrets.pluginEncryptionSecret`
- `backend.operaton.adminPassword` moved to `secrets.operatonAdminPassword`
- `keycloak.adminPassword` moved to `secrets.keycloakAdminPassword`
- `epistola.keycloak.clientSecret` and `externalEpistola.clientSecret` merged into `secrets.epistolaClientSecret`
- `backend.rabbitmq.*` removed entirely

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
