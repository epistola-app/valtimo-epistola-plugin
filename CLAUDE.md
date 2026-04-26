# Valtimo Epistola Plugin

## Build Order

The frontend plugin must be built BEFORE the test-app frontend:

1. **Build plugin library** (outputs to `frontend/plugin/dist/`):

   ```bash
   cd frontend/plugin && pnpm build
   ```

2. **Start test-app frontend** (references plugin via pnpm `workspace:*`):

   ```bash
   cd test-app/frontend && pnpm start
   ```

3. **Start test-app backend** (can run independently):
   ```bash
   ./gradlew :test-app:backend:bootRun
   ```

## Development Scenarios

### Scenario 1: Full local stack (both from source)

Best for cross-repo development when changing both Epistola and the plugin.

```bash
# Epistola infrastructure (Postgres 4001, Keycloak 8080)
cd repos/epistola-suite/apps/epistola/docker && docker compose up -d

# Epistola app (port 4000)
cd repos/epistola-suite
pnpm install && pnpm build
./gradlew :apps:epistola:bootRun --args='--spring.profiles.active=demo,localauth'

# Plugin infrastructure (Postgres 5432, Keycloak 8081)
cd repos/valtimo-epistola-plugin/docker && docker compose up -d

# Valtimo backend (port 8080, connects to Epistola on 4000)
cd repos/valtimo-epistola-plugin && ./gradlew :test-app:backend:bootRun

# Valtimo frontend (port 4200)
cd repos/valtimo-epistola-plugin/frontend/plugin && pnpm build
cd ../../test-app/frontend && pnpm install && pnpm start
```

### Scenario 2: Epistola only

For working on Epistola without the plugin.

```bash
cd repos/epistola-suite/apps/epistola/docker && docker compose up -d
./gradlew :apps:epistola:bootRun --args='--spring.profiles.active=demo,localauth'
```

### Scenario 3: Plugin + Epistola container

For plugin development without building Epistola from source.

```bash
# Start infra + Epistola container (port 4010)
cd repos/valtimo-epistola-plugin/docker
docker compose --profile server up -d

# Valtimo backend (port 8080, connects to Epistola on 4010)
./gradlew :test-app:backend:bootRun --args='--spring.profiles.active=dev'

# Valtimo frontend
cd frontend/plugin && pnpm build
cd ../../test-app/frontend && pnpm install && pnpm start
```

### Infrastructure ports

| Service            | Epistola stack | Plugin stack              |
| ------------------ | -------------- | ------------------------- |
| PostgreSQL         | 4001           | 5432                      |
| Keycloak           | 4002           | 8081                      |
| App                | 4000 (Gradle)  | 8080 (Valtimo backend)    |
| Frontend           | —              | 4200                      |
| Epistola container | —              | 4010 (`--profile server`) |

## Project Structure

```
frontend/
  plugin/          # Angular plugin library (@epistola.app/valtimo-plugin)
backend/
  plugin/          # Spring Boot plugin (app.epistola.valtimo:epistola-plugin)
test-app/
  frontend/        # Full Valtimo frontend for development
  backend/         # Full Valtimo backend for development
docker/            # Docker compose for local dependencies
```

## Important Notes

- **Frontend changes**: After rebuilding the plugin, just restart the test-app frontend (no `pnpm install` needed thanks to `workspace:*` symlink):
  ```bash
  cd frontend/plugin && pnpm build
  cd ../../test-app/frontend && pnpm start
  ```
- **Package name**: Epistola client uses `app.epistola.client` (not `io.epistola`)
- **Plugin properties**: Backend `@PluginProperty` keys must match frontend field names exactly
- **Translations**: Add both `nl` and `en` translations in `epistola.specification.ts`
- **Feature toggle**: Set `epistola.enabled=false` to disable the backend entirely. This is backend-only — the frontend has no equivalent guard. Remove any existing plugin configurations and process links before disabling, otherwise the UI will show stale entries that fail on API calls.

## Testing

**All tests and checks must pass before pushing:**

```bash
# Backend tests
./gradlew :backend:plugin:test

# Frontend tests (Jest unit tests)
cd frontend/plugin && pnpm test

# Frontend build (must also succeed)
cd frontend/plugin && pnpm build

# Formatting (run from project root)
pnpm format         # auto-fix
pnpm format:check   # check only (used in CI)
```

## Current State

### Backend

- **Full Epistola API integration** via `app.epistola.contract:client-spring3-restclient` (OpenAPI-generated client)
- **3 plugin actions**: `generate-document`, `check-job-status`, `download-document`
- **Async completion**: Both polling (`PollingCompletionEventConsumer`, configurable interval) and webhook callback (`EpistolaCallbackResource`)
- **Catalog sync**: Automatic import of classpath-based catalogs on startup (`EpistolaCatalogSyncService`)
- **Retry flow**: Dynamic Formio form generation for failed document retries (`RetryFormService`)
- **Document preview**: Preview without creating generation jobs (`PreviewService`)
- **Admin page**: Health checks, plugin usage overview, version info (`EpistolaAdminResource`)
- **Variant selection**: 3 modes — default, explicit variantId, or attribute-based automatic selection
- **Value resolution**: Supports `doc:`, `pv:`, `case:`, `template:` prefixes in data mappings
- **Security**: Callback endpoint public (webhooks), admin endpoints require `ROLE_ADMIN`, all other endpoints authenticated
- **Configuration**: Spring Boot auto-configuration with feature toggles (`epistola.enabled`, `epistola.poller.enabled`, `epistola.retry-form.enabled`)

### Frontend

- **23 Angular 19 components** including action configurators, data mapping builder, Formio components, and admin page
- **4 services**: Plugin API client, Admin API client, Menu service, Template field utilities
- **Full bilingual translations** (NL/EN) in `epistola.specification.ts`
- **Formio integration**: Custom components for download, retry form, preview button, and document preview

### Plugin Properties

- `baseUrl` (required) — Epistola API base URL
- `apiKey` (required, secret) — API authentication key
- `tenantId` (required) — Epistola tenant slug (3-63 chars, lowercase with hyphens)
- `defaultEnvironmentId` (optional) — Default environment for generation
- `templateSyncEnabled` (optional) — Enable classpath template sync on startup

## Design Decisions

| Decision            | Choice                                                 | Rationale                                                 |
| ------------------- | ------------------------------------------------------ | --------------------------------------------------------- |
| API Scope           | Generation + Read-only Templates/Environments/Variants | Plugin is for document generation, not template authoring |
| Authentication      | API key in plugin config                               | Simple, stateless, matches typical service integrations   |
| Async Pattern       | Message correlation + polling fallback                 | Supports both patterns for flexibility                    |
| Environment/Variant | Plugin default + action override                       | Sensible defaults with per-action flexibility             |
| Composite Job Path  | `epistola:job:{tenantId}/{requestId}` single variable  | Avoids scoping issues, enables correlation and polling    |

---

## Known Limitations

- **Callback signature verification**: The callback endpoint (`EpistolaCallbackResource`) accepts but does not verify the `X-Epistola-Signature` header. Blocked: Epistola does not yet support HMAC signing.
- **Server version endpoint**: `EpistolaAdminService.getVersions()` returns the plugin version only. Blocked: Epistola does not yet expose a version/health API endpoint.
- **Plugin logo**: Uses a placeholder logo (`epistola-logo.ts`). Should be replaced with the actual Epistola logo.

## Test Coverage

### What's tested

- `EpistolaServiceImpl` — Integration test using Epistola contract mock server (Testcontainers + Prism)
- `DataMappingResolver` — Dot-notation flattening and nested structure building
- `TemplateMappingValidator` — Required field validation
- `FormioFormGenerator` — Form schema generation from template fields
- `PollingCompletionEventConsumer` — Scheduled polling logic
- `EpistolaMessageCorrelationService` — BPMN message correlation
- `PreviewService` — Document preview functionality
- `RetryFormService` — Dynamic retry form generation
- `EpistolaAdminService` — Health checks and usage overview
- `EpistolaFormAutoDeployAspect` — Form auto-deployment aspect
- `CatalogScanner` — Classpath catalog discovery
- `EpistolaCatalogSyncService` — Catalog import with version tracking
- `NormalizeVariantAttributes` — Old vs new format normalization
- `EpistolaPluginResource` (document download only)
- **5 Playwright E2E suites**: Plugin configuration, generate-document, check-job-status, download-document

### Gaps (tracked as future work)

- `EpistolaPlugin` action methods (`generateDocument`, `checkJobStatus`, `downloadDocument`) — no unit tests for the orchestration logic
- `EpistolaCallbackResource` — no tests for webhook handling
- `EpistolaPluginResource` — only document download endpoint tested, other endpoints untested at controller level
- Frontend `.spec.ts` unit tests — minimal coverage (E2E tests cover the main flows)
