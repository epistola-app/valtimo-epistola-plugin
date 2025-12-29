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

## License

EUPL-1.2