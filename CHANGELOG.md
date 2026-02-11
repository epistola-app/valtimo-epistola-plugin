# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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

### Changed

- **Nested Data Mapping Format**: Data mapping now uses a nested structure mirroring the template
  schema hierarchy instead of flat dot-notation keys. Backend value resolution walks the tree
  recursively with batch expression resolution for efficiency. `TemplateMappingValidator` walks
  field tree and mapping tree in parallel by field name.

### Dependencies

- Added `app.epistola.contract:client-spring3-restclient:1.0.0` for Epistola API calls
- Uses Testcontainers with `ghcr.io/epistola-app/epistola-contract/mock-server` for API testing

## [1.0.0-SNAPSHOT] - Initial Development

### Added

- Initial project structure with backend (Java) and frontend (Angular) plugins
- Basic plugin definition with Valtimo plugin framework integration
- Test application for development
