# Epistola Plugin Development Progress

## Current Status

**Last Updated:** 2025-12-19

| Component | Status | Notes |
|-----------|--------|-------|
| Backend Plugin | Ready | Compiles, stub implementation |
| Frontend Plugin | Ready | Angular library created, not yet tested |
| Test App Backend | Ready | Spring Boot with Valtimo deps |
| Test App Frontend | Not Started | Requires Angular setup |
| Docker Infrastructure | Ready | PostgreSQL + Keycloak |
| Epistola API Integration | Not Started | Currently returns mock data |

**Next Priority:** Implement actual Epistola API client or set up test app frontend

## Overview

The Epistola plugin enables document generation within Valtimo/GZAC processes. Documents are generated asynchronously - a request is submitted and a callback is received when generation is complete.

## Project Structure

The project has been restructured to support separate publishing of backend and frontend:

```
valtimo-epistola-plugin/
├── backend/
│   └── plugin/                      # Backend plugin (Maven artifact)
│       ├── src/main/java/
│       ├── src/test/java/
│       └── build.gradle.kts
│
├── frontend/
│   └── plugin/                      # Frontend plugin (npm package)
│       ├── src/lib/
│       │   ├── components/
│       │   ├── models/
│       │   └── assets/
│       ├── package.json
│       └── ng-package.json
│
├── test-app/
│   ├── backend/                     # Spring Boot test application
│   │   └── build.gradle.kts
│   └── frontend/                    # Angular test application (TODO)
│
├── docker/
│   ├── docker-compose.yml           # PostgreSQL, Keycloak
│   └── keycloak/
│       └── valtimo-realm.json       # Keycloak realm config
│
├── build.gradle.kts                 # Root build with node plugin
├── settings.gradle.kts              # Gradle subprojects
└── docs/
    └── todo.md (this file)
```

## Completed

### Backend Plugin Structure

- [x] **Plugin class** (`EpistolaPlugin.java`)
  - Plugin-level configuration: `tenantId`
  - `generate-document` action with configurable parameters
  - Value resolution for `doc:`, `pv:`, `case:` prefixes
  - Data mapping from case/process data to template fields

- [x] **Plugin factory** (`EpistolaPluginFactory.java`)
  - Injects `EpistolaService` and `ValueResolverService`

- [x] **Auto-configuration** (`EpistolaPluginAutoConfiguration.java`)
  - Registers `EpistolaService` bean
  - Registers `EpistolaPluginFactory` bean

- [x] **Spring Boot auto-configuration registration**
  - `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [x] **Domain classes**
  - `FileFormat.java` - Enum with PDF, HTML
  - `GeneratedDocument.java` - Result DTO containing `documentId`

- [x] **Service interface** (`EpistolaService.java`)
  - `generateDocument()` method signature

- [x] **Service stub implementation** (`EpistolaServiceImpl.java`)
  - Placeholder implementation returning mock document ID

- [x] **Dependencies** (`build.gradle.kts`)
  - Added `value-resolver` dependency
  - Added `process-link` dependency

### Project Restructuring

- [x] **Gradle multi-module setup**
  - Root `build.gradle.kts` with node-gradle plugin
  - Subprojects: `backend:plugin`, `test-app:backend`
  - Shared version management via `extra` properties

- [x] **Frontend plugin library**
  - Angular library with ng-packagr
  - `EpistolaPluginModule` with configuration components
  - `epistolaPluginSpecification` matching backend plugin key
  - Plugin configuration component (tenantId)
  - Action configuration component (generate-document)
  - Translations (nl, en)

- [x] **Test application backend**
  - Spring Boot app with full Valtimo dependencies
  - Liquibase changelog
  - Application configuration

- [x] **Docker infrastructure**
  - PostgreSQL for application database
  - Keycloak with test realm and users

### Action Parameters

The `generate-document` action accepts these parameters (configured per service task):

| Parameter | Type | Description |
|-----------|------|-------------|
| `templateId` | String | Template ID in Epistola |
| `dataMapping` | Map<String, String> | Maps template fields to data sources |
| `outputFormat` | FileFormat | PDF or HTML |
| `filename` | String | Output filename (supports value resolvers) |
| `resultProcessVariable` | String | Process variable to store request ID |

## TODO

### Backend

- [ ] **Implement Epistola API client**
  - Create HTTP client for Epistola API
  - Implement authentication (API key, OAuth, etc.)
  - Implement `generateDocument()` in `EpistolaServiceImpl`

- [ ] **Implement callback handling**
  - Create REST endpoint to receive generation completion callbacks
  - Correlate callback with waiting process instance
  - Complete external task or send BPMN message

- [ ] **Add plugin-level configuration**
  - API base URL
  - Authentication credentials (secret)
  - Callback URL configuration

- [ ] **Error handling**
  - Handle API errors gracefully
  - Implement retry logic if needed
  - Throw appropriate BPMN errors

- [ ] **Unit tests**
  - Test value resolution
  - Test data mapping
  - Mock Epistola API responses

- [ ] **Integration tests**
  - Test full plugin action execution
  - Test callback handling

### Frontend

- [ ] **Test application frontend**
  - Set up Angular application with Valtimo modules
  - Register Epistola plugin module and specification
  - Configure Keycloak authentication

- [ ] **Data mapping UI improvement**
  - Key-value pair editor component
  - Value resolver autocomplete

- [ ] **Plugin logo**
  - Create/obtain Epistola logo
  - Convert to proper Base64

### Documentation

- [ ] **README.md**
  - Installation instructions
  - Configuration guide
  - Usage examples

- [ ] **CHANGELOG.md**
  - Track version changes

## Architecture Notes

### Async Document Generation Flow

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Process   │     │  Epistola   │     │  Epistola   │
│   Engine    │     │   Plugin    │     │   Service   │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       │ Service Task      │                   │
       │ Start             │                   │
       │──────────────────>│                   │
       │                   │ Submit request    │
       │                   │──────────────────>│
       │                   │                   │
       │                   │   Request ID      │
       │                   │<──────────────────│
       │                   │                   │
       │ Store request ID  │                   │
       │<──────────────────│                   │
       │                   │                   │
       │ (Process waits    │                   │
       │  for callback)    │                   │
       │                   │                   │
       │                   │   Callback with   │
       │                   │   document ID     │
       │                   │<──────────────────│
       │                   │                   │
       │ Complete task/    │                   │
       │ Send message      │                   │
       │<──────────────────│                   │
       │                   │                   │
```

## Build Commands

```bash
# Build backend plugin
./gradlew :backend:plugin:build

# Build frontend plugin (requires npm install first)
cd frontend/plugin && npm install && npm run build

# Start Docker services
docker compose -f docker/docker-compose.yml up -d

# Run test application (backend)
./gradlew :test-app:backend:bootRun
```

## Publishing

```bash
# Publish backend to Maven
./gradlew :backend:plugin:publish

# Publish frontend to npm
cd frontend/plugin && npm publish
```

## References

- [Valtimo Official Docs - Custom Plugins](https://docs.valtimo.nl/features/plugins/plugins/custom-plugin-definition)
- [Valtimo Official Docs - Process Links](https://docs.valtimo.nl/features/process-link)
