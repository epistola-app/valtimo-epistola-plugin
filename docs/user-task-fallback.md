# User Task Fallback for Failed Document Generation

When Epistola refuses a letter — a required value missing, a value in the wrong shape — the process
should let someone fix the cause and try again. The cause is almost always the **case**: the letter
is built from it by the generate action's mapping, so a value missing from the letter is missing
from the case.

## Recommended: correct the case, then generate again

Route the failure path to a user task that shows what Epistola refused and lets the employee correct
the case with an ordinary case form, then loop back to the same `generate-document` task. It runs
its mapping again, on the corrected case.

```
Start → Generate ◄──────────────────────────────┐
           │                                      │
           ▼                                      │
        Wait → Status OK? ──No──► Correct the case (user task)
                   │
                  Yes
                   ▼
               Download → Review → End
```

- **The case and the letter cannot disagree.** The letter is built from the corrected case, and every
  later letter gets the correction too.
- **Only what the form offers can change**, through the form's own validation, and the change is
  on the case like any other edit.
- **No plugin component is needed**: Epistola's reason is already on the result variable
  (`${epistolaResult.errorMessage}`), and the correction is a plain Valtimo form.

The process copies the reason onto a variable the correction form can show:

```xml
<bpmn:sequenceFlow id="Flow_not_completed" sourceRef="check-status" targetRef="correct-case-data">
  <bpmn:extensionElements>
    <camunda:executionListener event="take"
        expression="${execution.setVariable('generationError', epistolaResult.errorMessage)}" />
  </bpmn:extensionElements>
  <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${epistolaResult.status != 'COMPLETED'}</bpmn:conditionExpression>
</bpmn:sequenceFlow>

<bpmn:userTask id="correct-case-data" name="Corrigeer Dossiergegevens">
  <bpmn:incoming>Flow_not_completed</bpmn:incoming>
  <bpmn:outgoing>Flow_corrected_to_generate</bpmn:outgoing>
</bpmn:userTask>

<bpmn:sequenceFlow id="Flow_corrected_to_generate" sourceRef="correct-case-data" targetRef="generate-confirmation" />
```

The correction form shows `pv:generationError` read-only and the case fields the letter is built
from, keyed by their case path (`applicant.bsn`), so submitting it writes them to the case.

A letter that needs input the case does not hold is a different situation: use the
[letter composer](letter-composer.md), which asks only for what is missing and can write it back to
the case.

### Example

The `permit` case in the test-app: leave the BSN empty on its start form and the letter is refused.

- BPMN: `test-app/backend/.../permit/1.0.0/bpmn/permit-confirmation.bpmn`
- Correction form: `test-app/backend/.../permit/1.0.0/form/correct-permit-data.form.json`
- Process links: `test-app/backend/.../permit/1.0.0/process-link/permit-confirmation.process-link.json`

## Deprecated: the retry form

> **Deprecated since 0.21.0, to be removed in a later release.** It keeps working until then. It
> edits the letter rather than the case: the corrected values go into that one letter only, so the
> case keeps the wrong value and the next letter fails the same way; every template field can be
> changed, including values the case supplied correctly; and the mapping is skipped. Correct the
> case instead, as above. Using it logs a deprecation warning once per run. The plugin no longer
> deploys its form: a case that still uses it ships the form itself, see [The form](#the-form).

The rest of this section describes how it works, for installations that still use it.

When Epistola document generation fails (e.g., missing required data, template errors), the process can route to a user task that shows a dynamically generated form with all template fields prefilled. The user corrects the data and resubmits.

### How It Works

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
6. The BPMN loops back to `generate-document`, which detects the edited data and uses it directly (skipping the configured JSONata mapping)
7. If generation succeeds, the process continues normally. If it fails again, the loop repeats.

### Setup

#### 1. BPMN Process

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

#### 2. Process Link

Add a process-link entry for the retry user task pointing to the `epistola-retry-document` form,
which the case ships itself ([The form](#the-form)):

```json
{
  "activityId": "retry-edit-data",
  "activityType": "bpmn:UserTask:create",
  "processLinkType": "form",
  "formDefinitionName": "epistola-retry-document"
}
```

#### 3. Component Registration

`EpistolaPluginModule` registers the retry and document components automatically. Beyond the form above, no component registration is needed.

### How generate-document Detects a Retry

The `generate-document` action checks for a process variable named `epistolaEditedData`:

- **Present**: Uses the edited JSON data directly. Clears the variable after use.
- **Absent**: Evaluates the configured JSONata data mapping as usual.

This means the same `generate-document` service task handles both initial generation and retries. No separate retry action is needed.

### Source Activity Discovery

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

### Dynamic Form Generation

The retry form is generated dynamically by the backend endpoint `GET /api/v1/plugin/epistola/retry-form`. It:

1. Looks up the original generate-document process link
2. Extracts the data mapping from action properties
3. Evaluates the configured JSONata mapping against the current case/process context
4. Fetches the template field schema from Epistola
5. Generates a Formio form JSON with fields prefilled from the resolved data

Template fields are mapped to Formio components:

| Template Field Type | Formio Component                |
| ------------------- | ------------------------------- |
| SCALAR (string)     | `textfield`                     |
| SCALAR (number)     | `number`                        |
| SCALAR (boolean)    | `checkbox`                      |
| OBJECT              | `fieldset` with nested children |
| ARRAY               | `datagrid` with item columns    |

Field keys use dot-notation paths (e.g., `applicant.address.street`) so Formio automatically nests the submission data.

### The form

Until 0.21.0 the plugin deployed this form into every case (`epistola.retry-form.*`). That
deployment is gone, and the settings do nothing; `epistola.retry-form.enabled: true` logs a warning
saying so. A case that still uses the retry form adds it to its own forms, as
`form/epistola-retry-document.form.json` in the case's configuration. A form deployed by an earlier
version stays in place; this matters only for case versions deployed after the upgrade.

```json
{
  "display": "form",
  "components": [
    {
      "key": "pv:epistolaResult.errorMessage",
      "type": "textfield",
      "input": true,
      "label": "Error",
      "disabled": true
    },
    {
      "type": "epistola-retry-form",
      "key": "pv:epistolaEditedData",
      "input": true,
      "label": "Document Data",
      "components": [
        {
          "type": "hidden",
          "key": "epistolaTaskId",
          "input": true,
          "label": "Epistola Task Id",
          "persistent": false,
          "properties": {
            "sourceKey": "epistola:taskId"
          }
        }
      ]
    },
    {
      "key": "submit",
      "type": "button",
      "input": true,
      "label": "Retry Generation",
      "action": "submit",
      "theme": "primary",
      "disableOnInvalid": true
    }
  ]
}
```

The hidden `epistolaTaskId` field is the task-id carrier the component reads (see
[formio-components.md](formio-components.md)); keep it.

### Architecture

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
│    ├── JsonataMappingService → evaluate configured mapping    │
│    ├── EpistolaService → get template field schema           │
│    └── FormioFormGenerator → build Formio JSON with defaults │
│                                                              │
│  generate-document action                                    │
│    ├── Check epistolaEditedData PV                           │
│    ├── If present: use edited data, clear PV                 │
│    └── If absent: resolve data mapping as usual              │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```
