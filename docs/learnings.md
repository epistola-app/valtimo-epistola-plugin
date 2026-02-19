# Learnings

Hard-won lessons from building and debugging the Epistola Valtimo Plugin.

## Java Compilation: `-parameters` flag is required

**Problem**: All `@PluginActionProperty` values were `null` at runtime, despite being correctly stored in the database.

**Root cause**: Java by default strips method parameter names from bytecode. Valtimo's plugin framework uses reflection to match JSON action property keys (e.g., `"templateId"`) to Java method parameter names. Without the `-parameters` compiler flag, parameter names become `arg0`, `arg1`, etc. — so no keys match and all properties resolve to `null`.

**Fix** (in `build.gradle.kts`):
```kotlin
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}
```

**Detection**: The telltale sign is that ALL `@PluginActionProperty` values are null simultaneously, not just one. The log line showed `templateId=null, variantId=null, outputFormat=null, filename=null`.

**Why this is easy to miss**: Spring Boot projects typically inherit `-parameters` from the Spring Boot Gradle plugin. But a standalone library module (like our plugin) that only uses `java-library` and `spring-dependency-management` does NOT get it automatically.

---

## Valtimo Process Link Deployment

**How it works**: Valtimo deploys process links from two locations:
1. `classpath*:/config/global/process-link/**/*.process-link.json` (global, via `ProcessLinkDeploymentApplicationReadyEventListener`)
2. `classpath*:/config/case/<name>/<version>/process-link/*.process-link.json` (case-specific, via case definition deployers)

Both paths are supported. The file must be named `<process-definition-key>.process-link.json`.

**Process link JSON format**:
```json
[
  {
    "activityId": "my-service-task",
    "activityType": "bpmn:ServiceTask:start",
    "processLinkType": "plugin",
    "pluginConfigurationId": "<uuid-from-app.pluginconfig.json>",
    "pluginActionDefinitionKey": "generate-document",
    "actionProperties": {
      "templateId": "my-template",
      "outputFormat": "PDF"
    }
  }
]
```

**Plugin configuration**: Auto-deployed from `config/app.pluginconfig.json`:
```json
[
  {
    "id": "e6525773-...",
    "title": "Epistola Document Suite",
    "pluginDefinitionKey": "epistola",
    "properties": {
      "apiKey": "some-key",
      "tenantId": "tenant-id",
      "baseUrl": "http://localhost:4010"
    }
  }
]
```

---

## Kotlin Non-Null Constructor Parameters

**Problem**: The Epistola client library is written in Kotlin. Its `GenerateDocumentRequest` data class has `templateId: String` (non-null). Calling the constructor from Java with a `null` value throws:
```
NullPointerException: Parameter specified as non-null is null: method GenerateDocumentRequest.<init>, parameter templateId
```

**Lesson**: When consuming Kotlin libraries from Java, always check which constructor parameters are non-null (no `?` suffix). Kotlin inserts `Intrinsics.checkNotNullParameter()` calls at the start of every constructor. The error message is helpful — it names the exact parameter that was null.

**Debugging tip**: Use `javap -c` on the Kotlin class to see the null-check bytecode and confirm parameter order matches your Java call.

---

## Playwright MCP for UI Testing

**Setup**: Add `.mcp.json` at project root:
```json
{
  "mcpServers": {
    "playwright": {
      "command": "npx",
      "args": ["@playwright/mcp@latest"]
    }
  }
}
```

Restart Claude Code after creating this file to load the MCP server.

**Valtimo Login Flow** (Keycloak):
1. Navigate to `http://localhost:4200` → redirects to Keycloak
2. Fill username (`admin`) and password (`admin`)
3. Click "Sign In"
4. Wait for redirect back to app, sidebar takes ~2s to load

**Navigation** (Dutch locale):
- Sidebar: Dashboard, Dossiers (expandable), Taken, Analyse, Admin, Development
- Cases: click "Dossiers" to expand, then select a case type
- New case: "Creëer Nieuw Dossier" button → FormIO form → "Indienen" to submit

**Form filling**: Playwright MCP can fill FormIO forms using `browser_fill_form` with the element refs from `browser_snapshot`.

---

## Angular Pitfalls

**Never use `.bind(this)` in Angular template expressions** (e.g., `*ngTemplateOutlet` context). It creates a new function reference every change detection cycle, causing an infinite loop and browser freeze. Inline the calls directly instead.

**Valtimo `v-select`** is designed for use inside `v-form` (form collects values by name). For standalone dropdown usage, prefer native `<select>` elements or use `v-select` with proper `v-form` wrapping.

---

## Test Infrastructure

**Playwright E2E Tests**: Located at `test-app/frontend/e2e/`. Tests assume all services are running (Docker, backend, frontend). No `webServer` config to avoid slow startup.

**Auth persistence**: Keycloak auth is saved to `e2e/.auth/user.json` via `storageState`. A `setup` project in `playwright.config.ts` runs first and all test projects depend on it.

**API mocking**: Use `page.route()` to intercept REST calls in tests, avoiding dependency on backend state. Useful for template/variant/environment dropdowns.

**Backend integration tests**: Use Testcontainers with the Epistola Prism mock server (`ghcr.io/epistola-app/epistola-contract/mock-server`). Run with `./gradlew :backend:plugin:test`.