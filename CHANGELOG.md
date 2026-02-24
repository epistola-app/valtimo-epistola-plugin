# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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

### Changed

- **Callback Endpoint Refactored**: `EpistolaCallbackResource` now delegates to `EpistolaMessageCorrelationService` instead of using `RuntimeService` directly. Uses `correlateAllWithResult()` for safe multi-instance correlation.

- **Generate Document Action**: Now stores `epistolaRequestId` and `epistolaTenantId` as process variables automatically (in addition to the user-configured result variable). Required for the polling completion consumer.

- **Configuration Properties**: Replaced legacy `epistola.valtimo.*` prefix with `epistola.*` and added poller configuration (`epistola.poller.enabled`, `epistola.poller.interval`)

- **Nested Data Mapping Format**: Data mapping now uses a nested structure mirroring the template
  schema hierarchy instead of flat dot-notation keys. Backend value resolution walks the tree
  recursively with batch expression resolution for efficiency. `TemplateMappingValidator` walks
  field tree and mapping tree in parallel by field name.

### Fixed

- **`@PluginActionProperty` values null at runtime**: Added `-parameters` Java compiler flag to `build.gradle.kts`. Without it, Valtimo's reflection-based property resolution couldn't match JSON keys to method parameter names, causing all action properties to be `null`.

- **Null `variantId` causing NPE**: `EpistolaServiceImpl` now defaults `variantId` to empty string when not provided, preventing `NullPointerException` from the Kotlin client's non-null constructor parameter. Note: this is a workaround until the client library makes `variantId` optional (see `docs/todo.md`).

- **Variable scoping for parallel/multi-instance execution**: `epistolaRequestId` and `epistolaTenantId` are now stored as local variables (`setVariableLocal`) instead of process-instance variables. This prevents parallel branches and multi-instance iterations from overwriting each other's request IDs.

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
