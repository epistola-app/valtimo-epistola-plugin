# User Task Fallback for Failed Document Generation

When Epistola document generation fails (e.g., missing required data, template errors), the process can route to a user task that shows a dynamically generated form with all template fields prefilled. The user corrects the data and resubmits.

## How It Works

```
Start → Generate Doc ◄──────────────────┐
            │                             │
            ▼                             │
         Wait → Status OK? ──No──► Edit Data (user task)
                    │
                   Yes
                    ▼
                Download → Review → End
```

1. `generate-document` submits data to Epistola as usual
2. If generation fails (status = FAILED), the BPMN gateway routes to a retry user task
3. The user task renders a **dynamically generated Formio form** with all template fields prefilled from the original data
4. The user edits the data and submits
5. The form writes the edited data to process variable `epistolaEditedData`
6. The BPMN loops back to `generate-document`, which detects the edited data and uses it directly (skipping value resolver expressions)
7. If generation succeeds, the process continues normally. If it fails again, the loop repeats.

## Setup

### 1. BPMN Process

Add a retry user task on the failure path that loops back to the original generate-document service task:

```xml
<!-- The generate-document service task accepts both fresh and retry invocations -->
<bpmn:serviceTask id="generate-confirmation" name="Generate Document">
  <bpmn:incoming>Flow_from_start</bpmn:incoming>
  <bpmn:incoming>Flow_retry</bpmn:incoming>
  <bpmn:outgoing>Flow_to_wait</bpmn:outgoing>
</bpmn:serviceTask>

<!-- Status check gateway -->
<bpmn:exclusiveGateway id="check-status" name="Generation OK?">
  <bpmn:incoming>Flow_from_wait</bpmn:incoming>
  <bpmn:outgoing>Flow_completed</bpmn:outgoing>
  <bpmn:outgoing>Flow_failed</bpmn:outgoing>
</bpmn:exclusiveGateway>

<bpmn:sequenceFlow id="Flow_completed" sourceRef="check-status" targetRef="download">
  <bpmn:conditionExpression>${epistolaStatus == 'COMPLETED'}</bpmn:conditionExpression>
</bpmn:sequenceFlow>

<!-- Failure path: route to retry user task -->
<bpmn:sequenceFlow id="Flow_failed" sourceRef="check-status" targetRef="retry-edit-data">
  <bpmn:conditionExpression>${epistolaStatus != 'COMPLETED'}</bpmn:conditionExpression>
</bpmn:sequenceFlow>

<!-- Retry user task loops back to generate-document -->
<bpmn:userTask id="retry-edit-data" name="Correct Document Data">
  <bpmn:incoming>Flow_failed</bpmn:incoming>
  <bpmn:outgoing>Flow_retry</bpmn:outgoing>
</bpmn:userTask>

<bpmn:sequenceFlow id="Flow_retry" sourceRef="retry-edit-data" targetRef="generate-confirmation" />
```

### 2. Process Link

Add a process-link entry for the retry user task pointing to the built-in `epistola-retry-document` form:

```json
{
    "activityId": "retry-edit-data",
    "activityType": "bpmn:UserTask:create",
    "processLinkType": "form",
    "formDefinitionName": "epistola-retry-document"
}
```

No custom form JSON is needed — the plugin auto-deploys the `epistola-retry-document` form for each case.

### 3. Register the Formio Component (test-app / your app)

In your Angular `AppModule`, register the retry form component:

```typescript
import {
  registerEpistolaDownloadComponent,
  registerEpistolaRetryFormComponent
} from '@epistola.app/valtimo-plugin';

export class AppModule {
  constructor(private injector: Injector) {
    registerEpistolaDownloadComponent(injector);
    registerEpistolaRetryFormComponent(injector);
  }
}
```

That's it. No per-process form JSON, no custom configuration.

## How generate-document Detects a Retry

The `generate-document` action checks for a process variable named `epistolaEditedData`:

- **Present**: Uses the JSON data directly (no value resolver expressions evaluated). Clears the variable after use.
- **Absent**: Resolves the data mapping expressions (`doc:`, `pv:`, `case:`) as usual.

This means the same `generate-document` service task handles both initial generation and retries. No separate retry action is needed.

## Source Activity Discovery

The retry form needs to know which `generate-document` activity's configuration (template, data mapping) to use. This is resolved automatically:

1. **Single generate-document activity** (most common): auto-discovered from process links. Zero configuration needed.
2. **Multiple generate-document activities** (e.g., parallel document generation): specify which one via a BPMN input parameter on the retry user task:

```xml
<bpmn:userTask id="retry-edit-data" name="Correct Document Data">
  <bpmn:extensionElements>
    <camunda:inputOutput>
      <camunda:inputParameter name="epistolaSourceActivityId">generate-confirmation</camunda:inputParameter>
    </camunda:inputOutput>
  </bpmn:extensionElements>
</bpmn:userTask>
```

The resolution priority is:
1. Explicit `sourceActivityId` query parameter (from Formio field option)
2. `epistolaSourceActivityId` local variable on the active user task (BPMN input parameter)
3. Auto-discovery from process links (single generate-document only)

## Dynamic Form Generation

The retry form is generated dynamically by the backend endpoint `GET /api/v1/plugin/epistola/retry-form`. It:

1. Looks up the original generate-document process link
2. Extracts the data mapping from action properties
3. Resolves all value expressions against the current document
4. Fetches the template field schema from Epistola
5. Generates a Formio form JSON with fields prefilled from the resolved data

Template fields are mapped to Formio components:

| Template Field Type | Formio Component |
|---|---|
| SCALAR (string) | `textfield` |
| SCALAR (number) | `number` |
| SCALAR (boolean) | `checkbox` |
| OBJECT | `fieldset` with nested children |
| ARRAY | `datagrid` with item columns |

Field keys use dot-notation paths (e.g., `applicant.address.street`) so Formio automatically nests the submission data.

## Auto-Deployment

The `epistola-retry-document` form is auto-deployed for each case definition via an AOP aspect on `FormDefinitionImporter`. This is configurable:

```yaml
epistola:
  retry-form:
    enabled: true          # default: true
    case-filter: "all"     # "all", "none", or regex (e.g., "permit.*|subsidy.*")
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Frontend                                                     │
│                                                              │
│  epistola-retry-form (Formio custom component)               │
│    ├── Fetches form from GET /retry-form                     │
│    ├── Renders Formio sub-form with template fields          │
│    └── Emits edited JSON via valueChange → pv:epistolaEditedData │
│                                                              │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ Backend                                                      │
│                                                              │
│  GET /retry-form                                             │
│    ├── ProcessLinkService → find generate-document config    │
│    ├── DataMappingResolverService → resolve doc:/pv: values  │
│    ├── EpistolaService → get template field schema           │
│    └── FormioFormGenerator → build Formio JSON with defaults │
│                                                              │
│  generate-document action                                    │
│    ├── Check epistolaEditedData PV                           │
│    ├── If present: use edited data, clear PV                 │
│    └── If absent: resolve data mapping as usual              │
│                                                              │
│  EpistolaFormAutoDeployAspect                                │
│    └── @After FormDefinitionImporter.import()                │
│        → deploy epistola-retry-document for each case        │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## Example

See the permit-confirmation process in the test-app:
- BPMN: `test-app/backend/.../bpmn/permit-confirmation.bpmn`
- Process-link: `test-app/backend/.../process-link/permit-confirmation.process-link.json`
- Start form with optional BSN field to trigger failures: `test-app/backend/.../form/permit-start.form.json`
