# Valtimo Epistola Plugin

## Before committing

Run `pnpm format` before every `git commit` that touches `.md`, `.yaml`, `.yml`, `.ts`, or `.json` files — `pnpm format:check` is the gate CI runs, easier to fix locally than to chase down after pushing. Other pre-push gates (`pnpm test`, `./gradlew :backend:plugin:test`, `pnpm build`) are listed in [Testing](#testing).

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
- **Feature toggle**: The plugin can be disabled per environment from a single build artifact.
  - **Backend**: `epistola.enabled=false` (Spring property, defaults to `true`). Disables auto-configuration — no beans, no endpoints, no result collector.
  - **Frontend**: `EPISTOLA_ENABLED=false` (container env var, substituted into `assets/config.js` via the Dockerfile entrypoint, defaults to `true`). The plugin library reads `window['env']['epistolaEnabled']` at runtime via the exported `isEpistolaEnabled()` helper. `EpistolaPluginModule.forRoot()` stays unconditional in the host's `imports`; its `ENVIRONMENT_INITIALIZER` short-circuits when disabled, the `/epistola` route guard redirects to `/`, and `epistolaPluginSpecification.pluginId` stops matching the backend `epistola` definition. Keep `PLUGINS_TOKEN` static (`useValue`) in host apps; do not push optional-loading conditionals into installer-facing app-module wiring.
  - Set both flags `false` per environment to fully hide the plugin (no admin menu, no admin page, no plugin picker entry, no process-link action types). On environments where the plugin was previously active, remove existing plugin configurations and process links **before** disabling — otherwise stored references remain in the database and will surface stale entries that fail on API calls if the plugin is re-enabled.

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
- **Async completion**: `EpistolaResultCollectorRunner` manages one contract `ResultCollector` per active plugin configuration and correlates results via `EpistolaMessageCorrelationService`
- **Catalog sync**: Automatic import of classpath-based catalogs on startup (`EpistolaCatalogSyncService`)
- **Retry flow**: Dynamic Formio form generation for failed document retries (`RetryFormService`)
- **Document preview**: Preview without creating generation jobs (`PreviewService`)
- **Admin page**: Health checks, plugin usage overview, version info (`EpistolaAdminResource`)
- **Variant selection**: 3 modes — default, explicit variantId, or attribute-based automatic selection
- **Value resolution**: Data mappings are JSONata expressions evaluated with `$doc`, `$pv`, and `$case` context variables
- **Security**: Admin endpoints require `ROLE_ADMIN`; generation/tooling endpoints require normal authentication
- **Configuration**: Spring Boot auto-configuration with feature toggles (`epistola.enabled`, `epistola.result-collector.enabled`, `epistola.retry-form.enabled`)

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
| Async Pattern       | Result collector + message correlation                 | Avoids per-process timers and keeps BPMN simple           |
| Environment/Variant | Plugin default + action override                       | Sensible defaults with per-action flexibility             |
| Composite Job Path  | `epistola:job:{tenantId}/{requestId}` single variable  | Avoids scoping issues, enables correlation and polling    |

---

## Known Limitations

- **Server version endpoint**: `EpistolaAdminService.getVersions()` returns the plugin version only. Blocked: Epistola does not yet expose a version/health API endpoint.

## Test Coverage

### What's tested

- `EpistolaServiceImpl` — Integration test using Epistola contract mock server (Testcontainers + Prism)
- `JsonataMappingService` — JSONata evaluation and custom function bridging
- `FormioFormGenerator` — Form schema generation from template fields
- `EpistolaResultCollectorRunner` — Result collector lifecycle and message delivery
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
- Full multi-node result collector behavior depends on Epistola contract/server integration tests
- `EpistolaPluginResource` — only document download endpoint tested, other endpoints untested at controller level
- Frontend `.spec.ts` unit tests — minimal coverage (E2E tests cover the main flows)
