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

## License

EUPL-1.2