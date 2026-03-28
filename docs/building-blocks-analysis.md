# Building Blocks for Epistola: Analysis

## What We Want to Achieve

The Epistola plugin provides a `generate-document` action that submits data to the Epistola API and returns a generated document. When generation fails, the user should be able to **correct the input data and retry** without starting over.

Today, this retry flow is implemented as:

- An inline user task in the case BPMN with a dynamically generated Formio form
- An AOP aspect (`EpistolaFormAutoDeployAspect`) that auto-deploys the retry form per case definition
- A custom REST endpoint (`/retry-form`) that reads the original process-link configuration, resolves data mapping expressions, fetches the template schema, and generates a Formio form prefilled with current values

**The goal**: make the entire generate-document + retry flow **reusable** so that every case that needs document generation doesn't have to duplicate the retry user task, gateway, loop, and form configuration in its BPMN. Ideally a case just adds one call activity and gets the full lifecycle.

## Valtimo Building Blocks Overview

Valtimo building blocks (`com.ritense.valtimo:building-block`, available since v13.21.0) are reusable process fragments. Each building block packages:

- A **BPMN subprocess** (the reusable logic)
- **Forms** (scoped to the building block)
- A **document schema** (the building block's internal data model)
- **Process links** (connecting activities to forms, plugins, or nested building blocks)

A case references a building block via a **call activity** in its BPMN. At runtime, the building block creates its own document instance, executes its subprocess, and returns results via output mappings.

### Deployment

Building blocks are auto-deployed from the classpath. Place files under `config/building-block/<key>/<version>/` and `BuildingBlockDefinitionDeploymentService` handles the rest on `ApplicationReadyEvent`. Dependencies between building blocks are resolved via topological sort.

```
config/building-block/
  epistola-generate-document/
    1-0-0/
      building-block/
        definition/
          epistola-generate-document.building-block-definition.json
        building-block-definition-main-process-definition.json
      document/
        definition/
          epistola-generate-document.schema.document-definition.json
      bpmn/
        epistola-generate-document.bpmn
      form/
        epistola-retry-document.form.json
      process-link/
        epistola-generate-document.process-link.json
```

### Configuration UI

When an admin configures a call activity to use a building block, the process-link modal shows a multi-step wizard:

1. **Select building block** from a list (name, description, icon)
2. **Select plugin configuration** (e.g., which Epistola instance to use)
3. **Configure input/output mappings** using value-path-selectors (`doc:`, `pv:`, `case:`)

### Runtime flow

1. Case process reaches the call activity
2. `BuildingBlockCallActivityListener` creates a building block document instance
3. Input mappings are evaluated against the **live** case document (runtime, not design-time)
4. Building block subprocess executes
5. On completion, output mappings write results back to the case

## Where Building Blocks Help

### Deployment simplification

**Current**: The retry form is deployed per-case via AOP, requiring `EpistolaFormAutoDeployAspect` with `@After` advice on `FormDefinitionImporter.import()`, per-case tracking via `ConcurrentHashMap`, regex-based case filtering, and configuration properties.

**With building block**: Resources on the classpath are auto-deployed once. No AOP, no per-case tracking, no configuration.

### Process reuse

**Current**: Every case BPMN must duplicate the full flow:

```
Generate -> Wait -> Check Status -> (success) Download
                                 -> (failed) Retry User Task -> loop back
```

**With building block**: The case BPMN simplifies to:

```
[Call Activity: epistola-generate-document] -> Download
```

All retry logic, status checking, and error handling is encapsulated inside the building block.

### Data isolation

Building blocks create their own document instance at runtime. Input/output mappings bridge data between the case and the building block. The BB's internal state (request IDs, error messages, edited data) doesn't pollute the case's process variables.

### Plugin configuration inheritance

Building blocks support `pluginConfigurationMappings`. The admin selects which Epistola plugin configuration to use at the call activity level. Service tasks inside the BB resolve their plugin configuration by walking up the execution hierarchy via `DefaultBuildingBlockPluginConfigurationResolver`.

## Where Building Blocks Are Limited

### No custom configuration components

**This is the main limitation.** Plugins support custom configuration components per action via `FunctionConfigurationComponent` and `PluginSpecification.functionConfigurationComponents`. This is how `generate-document` provides its custom UI with template dropdown, data mapping tree, and output format selector.

Building blocks do **not** have this extension point. Every building block gets the same generic configuration wizard with a value-path-selector mapping table. The components are hardcoded in `process-link-modal.component.html`. There is no injection token, no dynamic component loading, and no way to register a custom component per building block type.

**Impact**: An admin configuring the epistola building block would use a generic mapping table instead of the purpose-built generate-document form. They'd map `doc:/applicant/firstName` to `dataMapping.applicant.firstName` row by row, without seeing the template schema, without the tree view, and without validation that required fields are mapped.

### Building block fields come from a static document schema

The input/output fields shown in the mapping UI are derived from the building block's JSON Schema (`BuildingBlockFieldService`). This means:

- Field names must be predefined in the schema
- No dynamic fields based on selected template
- The admin can't see which fields are required by the Epistola template

### No action property passthrough

Plugin action properties (like `templateId`, `outputFormat`, `filename`) configured in the BB's internal process-link are **fixed at design time**. There's no mechanism to expose them as configurable building block inputs that the admin can set at the call activity level.

The workaround is to put these values in the BB document schema and have the admin map them via input mappings, but this loses the typed configuration experience (e.g., template dropdown populated from API).

### Separate document context

The BB's internal plugin action resolves `doc:` expressions against the **BB document**, not the case document. The same data mapping configured once today would need to be configured in two places: case to BB input mapping, and BB internal process-link to plugin action.

## Why Not a Global Form?

The backend's `FormProcessLinkMapper.resolveFormDefinition()` supports falling back to global forms (forms without a `blueprintId`). A form deployed via `GlobalFormDefinitionImporter` (path: `config/global/form/`) would be resolved correctly at runtime.

However, the **frontend form selection UI only shows case-scoped forms**. Admins cannot select a global form when configuring a process link through the UI. This is why the AOP aspect deploys the form per-case: the identical form content must exist in each case's scope to appear in the dropdown.

## Comparison

| Approach | Config UX | Deployment | Process Reuse | Valtimo Changes Needed |
|---|---|---|---|---|
| **Current (AOP)** | Custom form (template dropdown, tree) | AOP per-case deploy | Must copy BPMN pattern | None |
| **Building block** | Generic mapping table | Auto from classpath | Single call activity | None (but UX degrades) |
| **Building block + custom config** | Custom form (same as today) | Auto from classpath | Single call activity | Add BB custom config extension |
| **Global form in UI** | Custom form (same as today) | Single global deploy | Must copy BPMN pattern | Show global forms in dropdown |

## Recommendations

### Short-term: Keep current approach

The AOP-based per-case deployment works. The form content is identical for every case; only the scoping differs. The plugin action provides the best configuration UX through its custom `FunctionConfigurationComponent`.

### Medium-term: Propose changes to Valtimo

Two options, either or both:

**Option A: Global forms in form selection UI**

Add global form visibility in the form selection dropdown. This is a frontend change to `@valtimo/process-link`. The form selection component needs to also query global forms (or forms without a blueprint). This would eliminate the AOP aspect entirely while keeping the rest of the architecture unchanged.

**Option B: Custom building block configuration components**

Add an extension point for building blocks similar to what plugins have:

1. An `InjectionToken<Array<BuildingBlockConfigurationComponent>>` (similar to `FORM_FLOW_COMPONENT_TOKEN`)
2. A way for building blocks to declare a `configurationComponentKey` in their definition
3. The process-link modal dynamically loads the registered component instead of the generic mapping UI
4. The custom component emits `BuildingBlockProcessLinkCreateDto` (including `inputMappings`, `outputMappings`, `pluginConfigurationMappings`)

This would allow the epistola building block to register a configuration component that reuses the existing template dropdown, data mapping tree, and output format selector.

### Long-term: Full building block with custom config

Once Valtimo supports custom BB configuration components:

1. Package the entire generate, wait, check, retry flow as a building block
2. Register a custom configuration component that provides the same UX as today
3. Cases add a single call activity and get the full document generation lifecycle
