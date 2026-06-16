# Formio components

The authoritative index of every Formio component the plugin registers. Keep it in sync when adding,
removing, or renaming a component — it exists so a change that affects "all the components" (e.g. how the
task id is delivered) doesn't silently miss one.

All components are registered in one place: `frontend/plugin/src/lib/epistola.module.ts`
(`registerEpistola*Component(injector)` calls). Each component lives in
`frontend/plugin/src/lib/components/<name>/` with a `*.component.ts` (the Angular component) and a
`*.formio.ts` (the Formio wrapper: registration, palette/editForm config, and the wrapper subclass that
bridges Formio ↔ Angular).

## Overview

| Type (`type:`)                   | Purpose                                                        | Palette\* | Task-bound\*\* | Backend call                          |
| -------------------------------- | -------------------------------------------------------------- | --------- | -------------- | ------------------------------------- |
| `epistola-document-preview`      | Live "what would be generated" PDF preview (dry-run, no job)   | visible   | **yes**        | `POST /preview`                       |
| `epistola-document`              | After-generation PDF: inline view and/or download button       | visible   | **yes**        | `GET /documents/download`             |
| `epistola-retry-form`            | Dynamic form to retry a failed generation (+ embedded preview) | hidden    | **yes**        | `GET /retry-form` (+ `POST /preview`) |
| `epistola-override-builder`      | editForm widget: map form fields → input overrides             | hidden    | no             | — (builder UI)                        |
| `epistola-process-link-selector` | editForm widget: pick the generate-document process link       | hidden    | no             | — (builder UI)                        |

\* **Palette** — `visible`: an author can drag it onto a form from the builder's component palette.
`hidden`: removed from the palette via `hideFormioComponentFromBuilder` (`components/formio-builder-utils.ts`)
because it is either part of the plugin's own auto-deployed form or an editForm-only widget. Hidden
components still render wherever they already exist and inside other components' `editForm`s.

\*\* **Task-bound** — reads the active user task's `taskInstanceId`, delivered by **server-side form
prefill** (the `epistola:taskId` value resolver fills the hidden `PREFILLED_TASK_ID_CARRIER` embedded in
each component's `schema`; the wrapper reads it back with `readPrefilledTaskId` and forwards it to the
Angular component as `@Input() taskInstanceId`). See [authorization.md](authorization.md) and
`services/prefilled-task-id.ts`. **This is the category that needs care**: any change to how the task id is
delivered, or to the late-arrival handling below, must be applied to **all three** task-bound components.

## Task-bound components: the late-`taskInstanceId` contract

The Formio wrapper sets `taskInstanceId` on the Angular element **after** `super.attach()`, so it can land
_after_ the component's first render. Every task-bound component must therefore (re)act when the task id
arrives, or it will sit on an "only available from within a user task" error until a manual refresh:

- **preview** (`epistola-document-preview.component.ts`) — `ngOnChanges` re-runs the preview on
  `changes['taskInstanceId']` (and on `value` changes).
- **download** (`epistola-document.component.ts`) — `ngOnChanges` (re)loads the inline document on
  `changes['taskInstanceId']`. (For `display="button"` the download click reads the id on demand.)
- **retry-form** (`epistola-retry-form.component.ts`) — `ngOnChanges` retries `loadForm()` when the task
  id arrives and the form hasn't loaded yet.

Outside a user task (Formio builder / design mode) the task id never arrives and the components fail
closed. When adding a new task-bound component, embed `PREFILLED_TASK_ID_CARRIER`, forward the id in the
wrapper, and add the same late-arrival handling.

## Component details

### `epistola-document-preview` — Document preview (author-facing)

Live preview of the document a `generate-document` link would produce, rendered by dry-running the link
(`POST /preview`) without creating a job. Override-driven: when an input-override mapping is configured it
waits for the mapped form data before firing (shows a "complete the form" placeholder until then). Its
`editForm` embeds `epistola-process-link-selector` (pick the link) and `epistola-override-builder` (map
fields → overrides). See [document-preview.md](document-preview.md).

### `epistola-document` — Document view/download (author-facing)

The after-generation UX for an already-generated PDF. Resolves the Epistola PDF id and tenant id from
named process variables on the caller's task (`documentVariable`/`tenantIdVariable`) via
`GET /documents/download` — the raw PDF id never crosses the wire. Three presentations via `display`:
`inline`, `button`, or `both` (default). See [document-component.md](document-component.md).

### `epistola-retry-form` — Retry a failed generation

Part of the plugin's auto-deployed `epistola-retry-document` form (hidden from the palette). Fetches a
dynamically generated Formio form for correcting and resubmitting a failed generation (`GET /retry-form`)
and shows an embedded live preview (`POST /preview`) of the corrected document.

### `epistola-override-builder` — Input-override mapping (editForm-only)

A builder-UI widget used **inside the preview component's `editForm`**. Lets the author map form field
values onto the preview's input overrides (`{doc, pv}`). Hidden from the palette; not a form field.

### `epistola-process-link-selector` — Process-link picker (editForm-only)

A builder-UI widget used **inside the preview component's `editForm`** to select which
`generate-document` process link (process definition + activity) the preview targets. Hidden from the
palette; not a form field.

## Configuration components (not Formio components)

The action **configurators** (`generate-document-configuration`, `check-job-status-configuration`,
`download-document-configuration`, `epistola-configuration`) and the admin page (`epistola-admin-page`)
are plain Angular components for the plugin-management / process-link-authoring UI, not Formio components,
and are out of scope for the task-id mechanism above.
