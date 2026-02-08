# Valtimo Epistola Plugin

## Build Order

The frontend plugin must be built BEFORE the test-app frontend:

1. **Build plugin library** (outputs to `dist/epistola-plugin`):
   ```bash
   cd frontend/plugin && pnpm build
   ```

2. **Start test-app frontend** (references plugin via `file:../../dist/epistola-plugin`):
   ```bash
   cd test-app/frontend && pnpm install && pnpm start
   ```

3. **Start test-app backend** (can run independently):
   ```bash
   ./gradlew :test-app:backend:bootRun
   ```

## Local Development with Docker

Start local dependencies:
```bash
docker compose -f docker/docker-compose.yml up -d
```

Services:
- PostgreSQL: localhost:5432
- Keycloak: localhost:8082
- Epistola Mock Server: localhost:4010

## Project Structure

```
frontend/
  plugin/          # Angular plugin library (@epistola/valtimo-plugin)
backend/
  plugin/          # Spring Boot plugin (app.epistola.valtimo:epistola-plugin)
test-app/
  frontend/        # Full Valtimo frontend for development
  backend/         # Full Valtimo backend for development
docker/            # Docker compose for local dependencies
```

## Important Notes

- **Frontend changes**: Rebuild plugin AND restart test-app frontend
- **Package name**: Epistola client uses `app.epistola.client` (not `io.epistola`)
- **Plugin properties**: Backend `@PluginProperty` keys must match frontend field names exactly
- **Translations**: Add both `nl` and `en` translations in `epistola.specification.ts`

## Testing

Run backend tests:
```bash
./gradlew :backend:plugin:test
```

Build frontend:
```bash
cd frontend/plugin && pnpm build
```

## Current State

- **Mock implementation** in `EpistolaServiceImpl.java` with hardcoded templates
- **Single action**: `generate-document` (submits request, returns mock UUID)
- **Dependency declared** but not used: `app.epistola.contract:client-spring3-restclient`
- **Missing**: API connectivity, callback handling, environment/variant support

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| API Scope | Generation + Read-only Templates/Environments/Variants | Plugin is for document generation, not template authoring |
| Authentication | API key in plugin config | Simple, stateless, matches typical service integrations |
| Async Pattern | Message correlation + polling fallback | Supports both patterns for flexibility |
| Environment/Variant | Plugin default + action override | Sensible defaults with per-action flexibility |

---

## Implementation Phases

### Phase 1: Backend API Client Integration

**Goal**: Replace mock implementation with real API calls

1. **Create `EpistolaClientConfiguration.java`**
   - Configure RestClient beans for Epistola APIs
   - Add API key authentication interceptor
   - Wire GenerationApi, TemplatesApi, EnvironmentsApi, VariantsApi beans

2. **Update `EpistolaPlugin.java`** - Add plugin properties:
   ```java
   @PluginProperty(key = "baseUrl", required = true)
   private String baseUrl;

   @PluginProperty(key = "apiKey", secret = true, required = true)
   private String apiKey;

   @PluginProperty(key = "defaultEnvironmentId")
   private String defaultEnvironmentId;
   ```

3. **Update `EpistolaServiceImpl.java`** - Replace mock with real API calls:
   - Inject Epistola API clients
   - Map Epistola DTOs to plugin domain objects
   - Handle API errors appropriately

4. **Add domain models**:
   - `EnvironmentInfo` - id, name
   - `VariantInfo` - id, templateId, tags
   - `GenerationJobStatus` - enum (PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED)

**Files to modify/create**:
- `backend/plugin/src/main/java/app/epistola/valtimo/config/EpistolaClientConfiguration.java` (new)
- `backend/plugin/src/main/java/app/epistola/valtimo/service/EpistolaServiceImpl.java`
- `backend/plugin/src/main/java/com/ritense/valtimo/epistola/plugin/EpistolaPlugin.java`
- `backend/plugin/src/main/java/app/epistola/valtimo/domain/*.java` (new models)

---

### Phase 2: Enhanced Generate Document Action

**Goal**: Support environment and variant selection

1. **Update `generate-document` action** - Add parameters:
   - `variantId` (optional) - specific variant to use
   - `environmentId` (optional) - uses plugin default if not specified
   - `correlationId` (optional) - for tracking across systems

2. **Update `EpistolaService` interface** - Add new method signature with extended parameters

3. **Update frontend** - Add to generate-document configuration:
   - Environment dropdown
   - Variant dropdown (loaded based on selected template)
   - Optional correlationId field

**Files to modify**:
- `backend/plugin/src/main/java/com/ritense/valtimo/epistola/plugin/EpistolaPlugin.java`
- `backend/plugin/src/main/java/app/epistola/valtimo/service/EpistolaService.java`
- `frontend/plugin/src/lib/components/generate-document-configuration/`

---

### Phase 3: New Plugin Actions

**Goal**: Add job status and document download capabilities

1. **`check-job-status` action**:
   - Input: process variable containing request ID
   - Output: status, documentId (if completed), errorMessage (if failed)
   - Use case: Polling pattern for async completion

2. **`download-document` action**:
   - Input: process variable containing document ID
   - Output: PDF bytes stored in case file or temp location
   - Integration with Valtimo document storage

3. **Frontend configuration components** for each new action

**Files to create**:
- `backend/plugin/src/main/java/app/epistola/valtimo/domain/GenerationJobDetail.java`
- `frontend/plugin/src/lib/components/check-job-status-configuration/`
- `frontend/plugin/src/lib/components/download-document-configuration/`

---

### Phase 4: Callback Webhook Support

**Goal**: Enable message correlation for async completion

1. **Create `EpistolaCallbackResource.java`**:
   - Endpoint: `POST /api/v1/plugin/epistola/callback/generation-complete`
   - Correlates BPMN message `EpistolaDocumentGenerated`
   - Sets variables: documentId, status

2. **Update security configuration** - Allow unauthenticated access with signature validation

3. **Document BPMN pattern** with example process

**Files to create/modify**:
- `backend/plugin/src/main/java/app/epistola/valtimo/web/rest/EpistolaCallbackResource.java` (new)
- `backend/plugin/src/main/java/app/epistola/valtimo/config/EpistolaHttpSecurityConfigurer.java`

---

### Phase 5: REST Endpoints for Frontend

**Goal**: Expose environment and variant data

1. **Extend `EpistolaPluginResource.java`**:
   - `GET /configurations/{id}/environments` - List environments
   - `GET /configurations/{id}/templates/{templateId}/variants` - List variants

2. **Update `EpistolaPluginService`** (frontend) - Add API methods

**Files to modify**:
- `backend/plugin/src/main/java/app/epistola/valtimo/web/rest/EpistolaPluginResource.java`
- `frontend/plugin/src/lib/services/epistola-plugin.service.ts`

---

### Phase 6: Testing

**Goal**: Integration tests with WireMock

1. **Add WireMock dependency** for HTTP-level testing
2. **Create `EpistolaServiceIntegrationTest`** - Test all API operations
3. **Create `EpistolaPluginTest`** - Test plugin actions in BPMN context

Approach: Functional input/output testing, avoid mocking internal components

**Files to create**:
- `backend/plugin/src/test/java/app/epistola/valtimo/service/EpistolaServiceIntegrationTest.java`
- `backend/plugin/src/test/java/com/ritense/valtimo/epistola/plugin/EpistolaPluginTest.java`

---

### Phase 7: Documentation

1. **Update README.md** - Installation, configuration, usage examples
2. **Create CHANGELOG.md** - Track all changes
3. **Add BPMN examples** - Message correlation pattern

---

## Verification Plan

1. **Unit verification**: Run `./gradlew :backend:plugin:test`
2. **Integration verification**:
   - Start test-app with `./gradlew :test-app:backend:bootRun`
   - Configure plugin with real Epistola instance
   - Create process with generate-document action
   - Verify document generation works end-to-end
3. **Frontend verification**:
   - Build frontend: `cd frontend && pnpm build`
   - Start test-app frontend and verify UI components

---

## Commit Strategy

1. `feat: add Epistola client configuration with authentication`
2. `feat: replace mock EpistolaService with real API implementation`
3. `feat: add environment and variant support to generate-document action`
4. `feat: add check-job-status plugin action`
5. `feat: add download-document plugin action`
6. `feat: add callback endpoint for async generation completion`
7. `feat: add environments and variants REST endpoints`
8. `feat(frontend): add environment and variant selection to action config`
9. `test: add integration tests for Epistola service`
10. `docs: add usage documentation and CHANGELOG`
