# Valtimo Epistola Plugin

Epistola document generation plugin for Valtimo.

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

## Configuration

### Feature Toggle

The plugin can be disabled entirely by setting `epistola.enabled=false` in `application.yml`:

```yaml
epistola:
  enabled: false
```

When disabled, no Epistola beans are registered (REST endpoints, plugin factory, poller, callback handler, etc.). The default is `true`.

**Important:** This toggle is backend-only. Before disabling, remove any existing Epistola plugin configurations and process links from the Valtimo database. Otherwise, the frontend will still show stale entries that fail on all API calls since the backend endpoints no longer exist.

## Local Development with Docker

Start the full stack (PostgreSQL, Keycloak, and Epistola server):

```bash
docker compose -f docker/docker-compose.yml --profile server up -d
```

This starts:
- **PostgreSQL** on `localhost:5432`
- **Keycloak** on `localhost:8081` (shared `valtimo` realm)
- **Epistola server** on `localhost:4010` (with SSO via Keycloak)

### Keycloak authentication

Both Valtimo and Epistola share the same Keycloak realm (`valtimo`). The
Docker Compose setup handles the split-horizon problem (browsers reach
Keycloak at `localhost:8081`, containers reach it at `keycloak:8080`) using:

- **KC_HOSTNAME** = `http://localhost:8081` — ensures JWT `iss` claims are
  consistent
- **KC_HOSTNAME_BACKCHANNEL_DYNAMIC** = `true` — allows containers to reach
  Keycloak on the internal hostname
- **EPISTOLA_AUTH_OIDC_BACKCHANNELBASEURL** = `http://keycloak:8080` —
  Epistola uses this for server-to-server OIDC calls (token, JWK, userinfo)

Default Valtimo users: `admin/admin` and `user/user`. Epistola demo users: `reader@demo/reader`, `editor@demo/editor`, `generator@demo/generator`, `manager@demo/manager`, `admin@demo/admin` (full access).

### Running the test application

Start the backend (connects to the Docker-managed PostgreSQL and Keycloak):

```bash
./gradlew :test-app:backend:bootRun --args='--spring.profiles.active=dev'
```

Build and start the frontend:

```bash
cd frontend/plugin && pnpm build
cd ../../test-app/frontend && pnpm start
```

Open `http://localhost:4200` (Valtimo) or `http://localhost:4010` (Epistola).

### Using the mock server instead

For quick tests without a real Epistola server:

```bash
docker compose -f docker/docker-compose.yml --profile mock up -d
```

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
