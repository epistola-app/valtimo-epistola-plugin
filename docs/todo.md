# Epistola Plugin Development Progress

## Overview

The Epistola plugin enables document generation within Valtimo/GZAC processes. Documents are generated asynchronously - a request is submitted and a callback is received when generation is complete.

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

- [ ] **Create Angular plugin module**
  - `epistola-plugin.module.ts`
  - `epistola-plugin.specification.ts`

- [ ] **Plugin configuration component**
  - Configure tenant ID
  - Configure API credentials
  - Configure callback settings

- [ ] **Action configuration component** (`generate-document`)
  - Template ID input/selector
  - Data mapping UI (key-value pairs with value resolver support)
  - Output format dropdown (PDF/HTML)
  - Filename input
  - Result variable name input

- [ ] **Plugin logo**
  - Create/obtain Epistola logo
  - Convert to Base64

- [ ] **Translations**
  - Dutch (nl)
  - English (en)

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

### File Structure

```
valtimo-epistola-plugin/
├── src/main/java/app/epistola/valtimo/
│   ├── config/
│   │   ├── EpistolaPluginAutoConfiguration.java
│   │   └── EpistolaProperties.java
│   ├── domain/
│   │   ├── FileFormat.java
│   │   └── GeneratedDocument.java
│   ├── plugin/
│   │   ├── EpistolaPlugin.java
│   │   └── EpistolaPluginFactory.java
│   └── service/
│       ├── EpistolaService.java
│       └── EpistolaServiceImpl.java
├── src/main/resources/
│   └── META-INF/spring/
│       └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
├── src/test/
│   └── ...
├── docs/
│   └── todo.md (this file)
├── build.gradle.kts
└── settings.gradle.kts
```

## References

- [Valtimo Plugin Development Guide](../../PLUGIN_DEVELOPMENT.md) (in main Plugins repo)
- [Valtimo Official Docs - Custom Plugins](https://docs.valtimo.nl/features/plugins/plugins/custom-plugin-definition)
- [Valtimo Official Docs - Process Links](https://docs.valtimo.nl/features/process-link)
