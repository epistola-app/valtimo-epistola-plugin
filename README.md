# Valtimo Epistola Plugin

Epistola document generation plugin for Valtimo.

## Installation

### Backend

Add the plugin dependency to your Valtimo application's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("app.epistola.valtimo:epistola-plugin:0.6.0")
}
```

Releases are published to [Maven Central](https://central.sonatype.com/artifact/app.epistola.valtimo/epistola-plugin) — the [Maven metadata](https://repo1.maven.org/maven2/app/epistola/valtimo/epistola-plugin/maven-metadata.xml) is authoritative if the search index lags behind.

The plugin auto-configures itself via Spring Boot. Set `epistola.enabled=false` to disable globally — see [Required configuration](#required-configuration) for the full property tree.

The plugin needs to know where to reach the Epistola server (see [Running the Epistola server](#running-the-epistola-server) for how to start one):

```yaml
# application.yml — equivalent to setting EPISTOLA_BASE_URL in the environment
epistola:
  base-url: http://localhost:4000/api
```

### Frontend

Install the plugin:

```bash
npm install @epistola.app/valtimo-plugin
# or
pnpm add @epistola.app/valtimo-plugin
```

`monaco-editor` is pulled in automatically as a peer dependency (it powers the JSONata editor). If your package manager has peer-dep auto-install turned off (`auto-install-peers=false` in pnpm, or you're on npm < 7), install it explicitly: `npm install monaco-editor`.

**angular.json** — serve Monaco's bundle from `assets/monaco-editor` by adding this entry to your build configuration's `assets` array:

```json
{ "glob": "**/*", "input": "node_modules/monaco-editor", "output": "assets/monaco-editor" }
```

> Users starting from [`gzac-frontend-template`](https://github.com/generiekzaakafhandelcomponent/gzac-frontend-template) v/13+ already have this entry — no action needed.

**App module** — register the plugin module and wire its plugin specification into Valtimo's `PLUGINS_TOKEN`:

```typescript
import { EpistolaPluginModule, epistolaPluginSpecification } from "@epistola.app/valtimo-plugin";
import { PLUGINS_TOKEN } from "@valtimo/plugin";

@NgModule({
  imports: [EpistolaPluginModule.forRoot()],
  providers: [
    {
      provide: PLUGINS_TOKEN,
      useValue: [
        // …other plugin specifications…
        epistolaPluginSpecification,
      ],
    },
  ],
})
export class AppModule {}
```

## Required configuration

There are two configuration layers — application-wide settings (in `application.yml`) and per-instance settings (created in the Valtimo console under **Admin → Plugins → Epistola Document Suite**).

### Per-instance plugin properties

Filled in via the Valtimo console form when creating an Epistola plugin configuration.

| Property               | Type    | Required | Secret | Description                                                                                                                                                    |
| ---------------------- | ------- | -------- | ------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `baseUrl`              | string  | yes      | no     | Base URL of the Epistola API, e.g. `http://localhost:4000/api` for a local Docker run.                                                                         |
| `apiKey`               | string  | yes      | yes    | API key minted in the Epistola server's UI under **Settings → API Keys**.                                                                                      |
| `tenantId`             | string  | yes      | no     | Tenant slug in Epistola — 3–63 characters, lowercase alphanumeric with hyphens.                                                                                |
| `defaultEnvironmentId` | string  | no       | no     | Default environment slug for document generation, 3–30 chars. Can be overridden per action at process-time.                                                    |
| `templateSyncEnabled`  | boolean | no       | no     | When `true`, the plugin automatically syncs catalogs from the classpath on startup — see [Catalog auto-deployment](#catalog-auto-deployment). Default `false`. |

> **First-time setup against the demo Epistola server** (`SPRING_PROFILES_ACTIVE=demo,localauth`): the demo profile seeds tenant `demo` with a deterministic API key. See [`test-app/backend/src/main/resources/config/app.pluginconfig.json`](test-app/backend/src/main/resources/config/app.pluginconfig.json) for the exact values shipped with the demo image.

### Application-wide settings

Configure under `epistola:` in `application.yml`:

```yaml
epistola:
  enabled: true # disable the plugin entirely (default: true)
  base-url: ${EPISTOLA_BASE_URL} # fallback baseUrl, also used by classpath-deployed plugin configs
  retry-form:
    enabled: true # auto-deploy the retry form for case failures (default: true)
    case-filter: "all" # "all" | "none" | regex on case definition keys
  poller:
    enabled: true # poll Epistola for async job completion (default: true)
    interval: 30000 # poll cycle in ms (default: 30000)
```

Source of truth: `app.epistola.valtimo.config.EpistolaProperties`.

> When setting `epistola.enabled=false`, first remove any existing Epistola plugin configurations and process links from the Valtimo database. Otherwise stored references remain in the database and surface stale entries that fail on every API call if the plugin is re-enabled later.

### Disabling the plugin per environment

A common deployment shape is to enable Epistola on test but keep it dark on production while shipping a single frontend artifact. Two flags control this:

| Flag                                 | Layer    | Default | Effect when `false`                                                                                                                                                                  |
| ------------------------------------ | -------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `epistola.enabled` (Spring property) | Backend  | `true`  | Auto-configuration short-circuits — no beans, REST resources, scheduled poller, or callback endpoint registered.                                                                     |
| `EPISTOLA_ENABLED` (container env)   | Frontend | `true`  | The plugin specification stops matching the backend `epistola` definition; the plugin module also skips menu/Formio registration and guards the admin route — no menu, route, or UI. |

Set both `false` per environment for a fully invisible plugin. The frontend flag is read at runtime from `window['env']['epistolaEnabled']`, populated by `envsubst` against `assets/config.template.js` at container start (the standard Valtimo runtime-config pattern). Same image, different env vars per environment.

Host app wiring does not change for optional loading. Keep `EpistolaPluginModule.forRoot()` in `imports` and keep `epistolaPluginSpecification` in the normal `PLUGINS_TOKEN` array, as shown in the setup snippet above. The plugin library reads the flag at runtime and hides its own surfaces when disabled.

When the flag is `false`, the plugin's `ENVIRONMENT_INITIALIZER` short-circuits before registering Formio components and the menu service, the `/epistola` route guard redirects to `/`, and `epistolaPluginSpecification.pluginId` no longer matches the backend `epistola` plugin definition — so no admin menu entry, no admin page, no plugin configuration picker entry, and no process-link action types appear.

> **Why not conditionally spread the plugin specification in `PLUGINS_TOKEN`?** Angular's AOT compiler cannot statically resolve `window['env']` accesses (NG1010), so runtime conditionals should not live directly in `@NgModule` decorator metadata. Epistola keeps the host app's provider static and moves the runtime check behind the plugin specification and module guards.

The default-true semantics (any value other than literal `false` / `'false'` is enabled) match the backend's `matchIfMissing = true` behaviour, so deployments that never set the env var keep the plugin enabled.

**`assets/config.template.js`** — append the placeholder so the entrypoint's `envsubst` pass picks it up:

```js
window["env"]["epistolaEnabled"] = "${EPISTOLA_ENABLED}";
```

The plugin module's JS still ships in the bundle (price of one-build-many-envs); it is just never activated when disabled.

## Catalog auto-deployment

When a plugin configuration has `templateSyncEnabled: true`, the plugin scans the classpath for Epistola catalogs on `ApplicationReadyEvent` and pushes them to the Epistola server. Idempotent and version-tracked — repeated startups are no-ops unless `release.version` in `catalog.json` changes.

### Layout on the classpath

```
src/main/resources/config/epistola/catalogs/
  ├── municipality-demo/
  │   ├── catalog.json              # required — declares slug, release version, templates
  │   └── resources/
  │       └── template/
  │           ├── besluit-bezwaar.json
  │           ├── voorwaarden-bijlage.json
  │           └── …
  └── another-catalog/
      └── …
```

Each `catalog.json` must include at minimum a `catalog.slug` and a `release.version` — the slug is the catalog's identity in Epistola, the version drives the redeploy decision.

### Worked example

The `:test-app:backend` module ships a complete `municipality-demo` catalog with eight Dutch municipal templates — see [`test-app/backend/src/main/resources/config/epistola/catalogs/municipality-demo/`](test-app/backend/src/main/resources/config/epistola/catalogs/municipality-demo/). It's enabled via [`test-app/backend/src/main/resources/config/app.pluginconfig.json`](test-app/backend/src/main/resources/config/app.pluginconfig.json) (`templateSyncEnabled: true`). Replicate that structure in your own Valtimo backend to ship templates alongside your application code.

Implementation: `app.epistola.valtimo.deploy.EpistolaCatalogSyncTrigger` + `CatalogScanner` (classpath glob `classpath*:config/epistola/catalogs/*/catalog.json`).

## Project Structure

```
├── backend/           # Kotlin backend plugin
├── frontend/plugin/   # Angular frontend plugin
├── test-app/          # Test application for development
└── docs/              # Documentation
```

## Development

### Prerequisites

- Node.js >= 18
- pnpm 9.x
- Java 21 (for backend)

### Setup

```bash
pnpm install
```

### Running the test application

```bash
# Build the plugin and start the test app
pnpm dev
```

Or separately:

```bash
# Build the plugin first
pnpm build:plugin

# Start the test app
pnpm start
```

### Developing the frontend plugin

When making changes to the frontend plugin (`frontend/plugin/`), you need to rebuild for changes to take effect in the test app.

**Option 1: Manual rebuild**

After making changes, rebuild and reinstall:

```bash
pnpm build:plugin && pnpm install
```

Then refresh your browser.

**Option 2: Watch mode (two terminals)**

```bash
# Terminal 1: Watch plugin changes
pnpm watch:plugin

# Terminal 2: Run dev server
pnpm start
```

> **Note:** The Angular dev server does not automatically detect changes to the rebuilt plugin due to how `file:` dependencies work with symlinks. After ng-packagr rebuilds, you may need to run `pnpm install` or restart the dev server for changes to appear.

### Building for production

```bash
pnpm build
```

## Running the Epistola server

The plugin connects to a running Epistola backend. There are three ways to provide one.

### Option A — Docker only, no clone (simplest)

The Epistola server is published as `ghcr.io/epistola-app/epistola-suite:latest` and needs a Postgres alongside it. A self-contained `docker run` flow:

```bash
docker network create epistola-net

docker run -d --name epistola-pg --network epistola-net \
  -e POSTGRES_USER=epistola -e POSTGRES_PASSWORD=epistola -e POSTGRES_DB=epistola_suite \
  postgres:16-alpine

docker run -d --name epistola-server --network epistola-net \
  -p 4000:4000 \
  -e SPRING_PROFILES_ACTIVE=demo,localauth \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://epistola-pg:5432/epistola_suite \
  -e SPRING_DATASOURCE_USERNAME=epistola \
  -e SPRING_DATASOURCE_PASSWORD=epistola \
  ghcr.io/epistola-app/epistola-suite:latest
```

Epistola UI: <http://localhost:4000>. Set the plugin's `baseUrl` to `http://localhost:4000/api`.

### Option B — Compose (this repo)

If you've cloned this repo, [`docker/docker-compose.yml`](docker/docker-compose.yml) orchestrates the full stack via profiles:

| Profile      | What it adds                                               | When to use                                                             |
| ------------ | ---------------------------------------------------------- | ----------------------------------------------------------------------- |
| `server`     | Postgres + Keycloak + Epistola server                      | Running Valtimo locally from source against a real Epistola             |
| `mock`       | Postgres + Keycloak + Epistola mock-server (contract-only) | Offline / CI — exercises the plugin without a real Epistola             |
| `containers` | Adds pre-built Valtimo demo backend + frontend             | End-to-end demo without building your own Valtimo                       |
| `reset`      | One-shot DB reset utility                                  | Testing the CronJob/data flows in [Demo Environment](#demo-environment) |

Common combos:

```bash
# End-to-end demo (Valtimo at :4200, Epistola at :4000):
docker compose -f docker/docker-compose.yml --profile server --profile containers up -d

# Just the Epistola side, then run your own Valtimo locally:
docker compose -f docker/docker-compose.yml --profile server up -d
```

Run the test-app against the docker stack (build the plugin first so the test-app/frontend picks it up):

```bash
./gradlew :test-app:backend:bootRun --args='--spring.profiles.active=dev'
cd frontend/plugin && pnpm build
cd ../../test-app/frontend && pnpm start
```

Open <http://localhost:4200> (Valtimo) or <http://localhost:4000> (Epistola).

#### Keycloak SSO between Valtimo and Epistola

Both Valtimo and Epistola share the `valtimo` realm. The compose setup handles the split-horizon problem (browsers reach Keycloak at `localhost:8081`, containers reach it at `keycloak:8080`) using:

- **`KC_HOSTNAME`** = `http://localhost:8081` — keeps JWT `iss` claims consistent.
- **`KC_HOSTNAME_BACKCHANNEL_DYNAMIC`** = `true` — allows containers to reach Keycloak on the internal hostname.
- **`EPISTOLA_AUTH_OIDC_BACKCHANNELBASEURL`** = `http://keycloak:8080` — Epistola uses this for server-to-server OIDC calls (token, JWK, userinfo).

### Option C — Bring your own

Run the Epistola server somewhere reachable and set `baseUrl` accordingly. The plugin doesn't care how it's hosted.

### Ports

| Service                             | URL                                                                                                                 |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| Epistola server (`server` profile)  | <http://localhost:4000>                                                                                             |
| Epistola mock (`mock` profile)      | <http://localhost:4010> (mock listens on 4010 internally — kept distinct so it's never confused with a real server) |
| Keycloak                            | <http://localhost:8081>                                                                                             |
| Valtimo (with `containers` profile) | <http://localhost:4200>                                                                                             |

### Default credentials

- **Valtimo / Keycloak:** `admin/admin`, `user/user`
- **Epistola server demo users:** `reader@demo/reader`, `editor@demo/editor`, `generator@demo/generator`, `manager@demo/manager`, `admin@demo/admin` (full access)
- **Plugin → Epistola API key (demo profile):** tenant `demo` with the deterministic key in [`test-app/backend/src/main/resources/config/app.pluginconfig.json`](test-app/backend/src/main/resources/config/app.pluginconfig.json)

## Kubernetes Deployment

See [charts/valtimo-demo/README.md](charts/valtimo-demo/README.md) for Helm
chart documentation including Keycloak SSO, standalone mode, and production
deployment examples.

## Demo Environment

### Database Reset

The test-app includes a database reset endpoint for demo/development environments. This is useful when the application is deployed on Kubernetes and you need to restore the database to a clean state.

```
POST /api/v1/test/reset
```

**Requires:** Authentication (any authenticated user via Valtimo's default security)

**What it does:**

1. Drops the `public` schema (`DROP SCHEMA public CASCADE`)
2. Recreates the `public` schema with proper grants
3. Returns `202 Accepted`
4. Shuts down the JVM after a 1-second delay (so the HTTP response completes)

**On Kubernetes**, the pod restarts automatically after `System.exit(0)`. Other replicas will fail on their next database call, causing liveness probes to fail and triggering restarts. On startup, Valtimo and Operaton recreate all tables, and case definitions, BPMN processes, and plugin configurations are redeployed from `config/`.

**Example:**

```bash
# Get a token from Keycloak
TOKEN=$(curl -s -X POST 'http://localhost:8081/realms/valtimo/protocol/openid-connect/token' \
  -d 'grant_type=password&client_id=valtimo-console&username=admin&password=admin' \
  | jq -r '.access_token')

# Reset the database
curl -X POST http://localhost:8080/api/v1/test/reset \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json"
```

### Public Demo Authentication

Public internet demos now require each visitor to register their own Keycloak
account. Registrations are open (email + password) and every new account is
added to the `valtimo-users` group which grants the `ROLE_USER` realm role.
After signing in, users can enroll a hardware/software passkey from their
Keycloak account profile and subsequently log in with "Use passkey".

- `publicUrls.*` Helm values describe the externally reachable URLs so redirect
  URIs and Keycloak hostnames remain correct in any cluster/ingress setup.
- A secure Keycloak admin password is generated automatically (unless
  overridden) and stored in the backend secret. Retrieve it with:

  ```bash
  kubectl get secret <release>-valtimo-demo-backend \
    -o jsonpath='{.data.keycloak-admin-password}' | base64 -d
  ```

- Configure the chart by setting the public URLs for frontend and Keycloak:

  ```yaml
  publicUrls:
    frontend: https://valtimo.demo.example
    keycloak: https://auth.demo.example/auth

  keycloak:
    webAuthn:
      rpId: valtimo.demo.example
      passwordlessRpId: auth.demo.example
  ```

  The RP IDs must match the effective domain served over TLS so browsers accept
  passkey registration.

## License

EUPL-1.2
