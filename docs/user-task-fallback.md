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

## Removed: the retry form

The retry form (`epistola-retry-form`, `GET /retry-form`, the `epistolaEditedData` variable) was
removed in 1.0. It edited the letter rather than the case, so the case kept the wrong value and the
next letter failed the same way. Correct the case and generate again, as described above.
`epistola.retry-form.enabled: true` now only logs a warning that the retry form is gone. A process
that still routes to a form with an `epistola-retry-form` component has to be changed to a
correction form before upgrading.
