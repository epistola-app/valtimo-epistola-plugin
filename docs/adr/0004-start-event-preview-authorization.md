# ADR 0004 — How a document preview is authorized at a BPMN start event

- **Status:** Accepted
- **Date:** 2026-09-02
- **Deciders:** Epistola plugin maintainers
- **Related:** `EpistolaGenerationResource`, `PreviewService`, `epistola-document-preview`, `docs/document-preview.md`, `docs/authorization.md`, issue #67

## Context and problem statement

The `epistola-document-preview` component has only ever worked inside a user task. That is not a
cosmetic restriction: the task performs three distinct jobs for the preview, and all three disappear
at a start event.

| Job               | How the task provides it                                                              |
| ----------------- | ------------------------------------------------------------------------------------- |
| **Authorization** | `OperatonTask:VIEW` on the task id (`requireTaskViewable`)                            |
| **Context**       | `processInstanceId` → process definition → process link; business key → case document |
| **Delivery**      | The task id reaches the component through server-side form prefill (hidden carrier)   |

A start form is precisely where a preview belongs — fill in the form, check the letter, _then_ start
the case. Today an author must start the case first and preview from the first user task, which
creates a dossier for a letter the user may decide not to send.

Supporting this means answering three separate questions: what authorizes the call when there is no
task, where the data comes from when there is no case and no process instance, and how the component
knows which of the two modes it is in. This ADR records the authorization answer and the mode-
selection rule, because both are security-relevant and neither is obvious from the code.

Two start flavours exist in Valtimo, and they differ materially:

- **New case** — `CaseProcessStartModalComponent` requests the start form with `documentId = null`.
  There is no document and no process instance.
- **Start a process on an existing case** — `CaseSupportingProcessStartModalComponent` passes a real
  `documentId`. A document exists; there is still no process instance.

## Decision drivers

- **No silent authorization downgrade.** A preview must never quietly move from a stricter gate to a
  weaker one, and must never render a document the caller could not otherwise obtain.
- **The authorization subject must be derived server-side**, or — where it is unavoidably supplied by
  the client — must itself be the resource that is checked.
- **Permission to _start_ a process must not confer permission to _read_ a case.** This is the direct
  lesson of commit `8972c16`, where a client-supplied `documentId` let a caller read any case through
  the JSONata mapping.
- **Existing forms must be unaffected.** Every form deployed today must keep behaving exactly as it
  does, with no re-authoring.
- **Stay within the plugin's declared Valtimo compatibility range** rather than raising the floor for
  an additive feature.

## Decision outcome

**A separate `POST /preview/start` endpoint, authorized on `OperatonExecution:CREATE` against the
`OperatonProcessDefinition`, with the mode chosen by explicit authored configuration.**

1. **Authorization** mirrors what Valtimo itself checks before serving the start form the component
   sits on (`ProcessLinkActivityService.getStartEventObject`):

   ```java
   new RelatedEntityAuthorizationRequest<>(
           OperatonExecution.class, OperatonExecutionActionProvider.CREATE,
           OperatonProcessDefinition.class, processDefinitionId)
   ```

   The preview therefore inherits exactly the audience of the form it lives on — no more, no less.

2. **When a `documentId` is supplied**, `JsonSchemaDocument:VIEW` is required on that document _in
   addition_. This is deliberately stricter than Valtimo's own start-form path, which passes the
   document only as _context_ for the execution permission. Valtimo can afford that because it
   derives the id from the route the user already navigated to; our endpoint accepts it on the wire
   and renders its content into a PDF, so read permission must be checked outright.

3. **The client supplies a `processDefinitionKey`, not an id.** The key is version-stable and already
   persisted in every saved form; a version-pinned id would break on the next BPMN deployment. The
   server resolves it to a `processDefinitionId` and uses only that downstream. The key being
   client-supplied is safe because it selects _which_ resource is checked, never _whether_ one is.

4. **Mode is explicitly authored**, via a `previewContext: 'task' | 'start'` component setting that
   defaults to `'task'`. Each mode calls exactly one endpoint and there is no code path from one to
   the other. A start-mode component that finds a task id is treated as misconfigured and renders
   nothing.

5. **`POST /preview` is unchanged**, and never accepts anything but a task.

## Alternatives considered

### Deliver start context through a second value resolver

**Impossible, not merely undesirable.** `FormProcessLinkActivityHandler.getStartEventObject` calls
`PrefillFormService.getPrefilledFormDefinition(formDefinitionId, documentId)`, which returns the raw
form definition before touching any resolver when `documentId` is null. For a brand-new case _no
prefill runs at all_, so no carrier can be filled. `ValueResolverPropertyKey` also enumerates the
property keys Valtimo passes to `createResolver(Map)` and has no process-definition key, so there is
no seam to extend without an upstream change. Recorded here because the existing `epistola:taskId`
carrier makes this look like the natural approach.

### Infer the mode from which identifiers are present

Rejected. Falling back to start mode when no task id arrives fails in exactly the situation it would
fire. It downgrades the gate from `OperatonTask:VIEW` on a specific task to a process-definition
permission, and it drops `$doc`/`$pv` to overrides-only — `JsonataMappingService.buildProcessVariableMap`
binds `$pv` to `Map.of()` rather than throwing, so the user sees a _plausible letter with fields
silently missing_. That is the failure class 0.19.0 added the override-builder warning for.

It is not hypothetical: commits `3d9ff8f`, `211396f`, `e13fdc1` and `2a07549` all exist because the
task-id carrier went missing from builder-saved forms. An inference-based fallback would have
converted that loud, correct failure into a silent, wrong one.

### Gate on `JsonSchemaDocumentDefinition:CREATE`

Rejected, and recorded explicitly so it is not re-opened. The name suggests "may create a case of this
type", but tracing every reference in Valtimo 13.42 shows it is used **only** inside
`JsonSchemaDocumentDefinitionService.deploy(...)` — it means _"may deploy a case schema"_, an
administrative permission. Gating on it would make the preview effectively admin-only and useless to
the case workers who fill in start forms, and granting it to them would also unlock Valtimo's
definition-deploy endpoints. It would additionally have required `ProcessDefinitionCaseDefinitionService`
to derive the case type, which postdates the plugin's declared `>=13.21` floor.

### Reuse `POST /preview` with an optional `taskId`

Rejected on balance. The security argument for splitting is weaker than it first appears — both
designs accept a client-supplied `documentId`, and what protects either is checking a permission on
it. But because mode is explicitly authored, a merged endpoint needs a discriminated-union body:
either Jackson polymorphic deserialization, or one DTO with both identity sets nullable. The latter
reintroduces the "both identities present in one handler" shape this design removes. Two narrow
records that reject unknown fields are simpler to type and to review.

### Start a throwaway process instance and preview from it

Rejected. It pollutes the dossier list and process history with instances representing letters that
were never sent, which is the very problem the feature exists to avoid.

## Consequences

- The preview component is no longer strictly task-bound. `docs/formio-components.md` records it as
  "task-bound by default, opt-in start-event mode", and the task-id carrier is retained in **both**
  modes because the misconfiguration guard depends on it.
- `/preview/start` is the first Epistola endpoint whose PBAC subject is client-selected. The
  reasoning that makes this sound (point 3 above) is documented in `docs/authorization.md`; future
  endpoints should not copy the pattern without the same argument.
- On a start form for a new case, `$doc` and `$pv` resolve **only** to what the override mapping
  supplies. A mapping that reads `$pv.foo` produces nothing there. Authors need to know this, so the
  resolution table is documented per flavour in `docs/document-preview.md`.
- `EpistolaGenerationResource` gains one constructor parameter (`RepositoryService`). Applications
  that override the `@ConditionalOnMissingBean` bean must widen their factory method.
- Because the mode defaults to `'task'`, every existing form is unaffected and needs no re-authoring.
