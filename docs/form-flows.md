# Epistola in Valtimo Form Flows

A Form Flow splits one user task across several screens. Epistola's task-bound components —
[`epistola-document-preview`](document-preview.md), [`epistola-document`](document-component.md) and
`epistola-retry-form` — work on any of those screens, because Valtimo's server-side prefill fills
their hidden task-id carrier on every step (see [formio-components.md](formio-components.md)).

What changes in a Form Flow is **where data lives**. This page covers the four things that are easy
to get wrong, each of which fails quietly rather than loudly.

The test application's **Form Flow voorbeeld** case
(`test-app/backend/src/main/resources/config/case/form-flow-demo/`) is a worked example of
everything below.

## 1. Put the preview on the step that completes the task

A Form Flow step completes the surrounding BPMN task through its `onComplete`:

```json
"onComplete": ["${valtimoFormFlow.completeTask(additionalProperties, step.submissionData)}"]
```

Put the preview on **that** step. On any earlier step the preview is already off screen by the time
the user commits, which defeats the point of showing them what they are about to generate.

## 2. Generate after the user tasks, not between them

Place the `generate-document` service task **after** the user tasks, not between the form flow task
and whatever follows it. The preview only needs its process link to _exist_ — it dry-runs the link
without creating a job — so nothing about the preview requires the service task to have executed.

Putting generation in between makes the user wait on Epistola in the middle of the flow, for a
document nobody has confirmed yet.

## 3. The document schema must accept what `completeTask` writes

`valtimoFormFlow.completeTask(additionalProperties, step.submissionData)` — the **two-argument**
overload — defaults its save path to `doc:/submission`. It writes the completing step's submission
data onto the case document _before_ completing the task.

If the case's document definition is `additionalProperties: false` and does not declare
`submission`, that write is rejected and the expression throws:

```
Error while executing expression:
'${valtimoFormFlow.completeTask(additionalProperties, step.submissionData)}'
```

The task never completes and the process never advances. Note the error names the expression, not
the schema — it is easy to misread as a Form Flow problem.

Two ways out:

- declare a `submission` object on the document definition, or
- use the three-argument overload and pass an explicit save path.

## 4. `$form` is one step — carry earlier values with a matching key

The preview's **Input Overrides** mapping is a JSONata expression over `$form`, and `$form` is the
data of **the form being rendered right now**. There is no combined bag of all steps: a reference to
a field submitted on an earlier step resolves to nothing, and the preview silently falls back to the
saved case data.

Valtimo's built-in mechanism for bridging this is prefill:

```
FormFlowStepTypeFormHandler.prefillWithSubmissionData
  → instance.getSubmissionDataContext()   // merged submission data of all steps so far
  → FormDefinition.preFill(json)          // fills matching components' defaultValue
```

`preFill` only fills components that **already exist** on the step's form; it never creates them.
So to read an earlier step's `subject`:

1. Add a component to the **later** step's form whose property name is exactly `subject`. A
   `hidden` component is fine — the demo uses one.
2. Map it in the preview's Input Overrides, e.g. Scope `doc`, Input Path `title`, Form Field
   `subject` — which serialises to `{ "doc": { "title": $form.subject } }`.

Valtimo prefills the carrier from the earlier step, Formio copies it into the form data, and
`$form.subject` resolves.

### Things that catch people out

- **The key must match exactly.** Nothing validates it against the earlier step. The builder does
  warn when a mapping references a field this form doesn't declare at all, which catches the common
  case, but it cannot tell you whether an upstream step actually submits that key.
- **Formio uniquifies keys within a form.** Duplicating a carrier silently renames the copy to
  `subject1`, which prefills nothing.
- **A hidden carrier does not drive auto-refresh.** The preview refreshes on `change` and
  `focusout`; a hidden field fires neither, so it is picked up by the preview's initial compute (and
  the Refresh button). If you want the preview to react as the user types, the field must be visible
  on that step.
- **Later steps win.** `getSubmissionDataContext` merges steps in order, so a later step's value
  overrides an earlier one for the same key, and flow submission data takes precedence over
  document/process-variable prefill for the same component.

### The alternative: persist it instead

If the value does not need to reflect _unsaved_ form state, skip `$form` entirely. Have the earlier
step write to the document or a process variable, and let the generate-document data mapping read
`$doc`/`$pv` directly — no carrier and no override mapping. The trade-off is that it persists data
before the user has confirmed anything, and the preview then shows stored state rather than what is
on screen.

## Field picker scope

The **Form Field** dropdown in Input Overrides lists only fields on the form you have open. The
Formio builder passes just that one form schema to the edit dialog (`options.editForm`), so it has
no knowledge of sibling steps. A field from an earlier step appears in the list once this form
declares a carrier for it, as described above.
