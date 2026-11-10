---
name: valtimo-editing
description: Create, tweak and remove example cases (dossiers) in a running Valtimo test-app (local or a deployed environment such as the demo) — document schema, BPMN, forms, process links (including Epistola actions) — over Valtimo's REST API with the `vt` helper script. Use when the user wants to quickly try out a case, form, process or process-link configuration, copy an existing demo case to experiment on, make a small change to a case's form/BPMN/link without restarting, or clean up such experiments.
---

# Valtimo editing

Drive a running Valtimo test-app — local or deployed, such as the demo — to build throwaway example cases. Everything goes
through `vt` (next to this file), a bash wrapper around Valtimo's own management REST API — the same
endpoints the admin UI uses. Nothing is added to the backend.

```bash
VT=.claude/skills/valtimo-editing/vt
$VT help
```

## Environments

`vt` talks to any deployment of the test-app. Pick one with `-e NAME` (first argument) or `VT_ENV`;
without either it uses `local`.

```bash
$VT env add demo https://valtimo.demo.epistola.app   # once; reads the app's /assets/config.js
$VT -e demo login                                    # opens a browser; waits up to 5 minutes
$VT -e demo cases
$VT env list
```

- **`local`** is built in: backend `http://localhost:8080`, password grant against Keycloak on 8081
  (`valtimo-console`, `admin`/`admin`). Overrides: `VALTIMO_URL`, `KEYCLOAK_URL`, `VALTIMO_USER`,
  `VALTIMO_PASSWORD`. Needs the backend running
  (`./gradlew :test-app:backend:bootRun --args='--spring.profiles.active=dev'`).
- **Any other environment** is registered by its **frontend URL**: `vt` reads the same OIDC
  settings the Angular app logs in with (Keycloak or authentik) and logs in through the browser —
  authorization code + PKCE with a loopback redirect to `http://localhost:8765/callback`
  (`VT_CALLBACK_PORT`). The IdP client must allow that redirect URI, and issue refresh tokens
  (`offline_access`), or the login lasts only as long as one access token. Add `--password` to
  `env add` for an environment whose IdP allows the password grant.
- Tokens are cached per environment in `~/.cache/vt/` (mode 600) and refreshed automatically.
  Registered environments live in `~/.config/vt/environments.json`. `VT_TOKEN=<bearer>` bypasses
  all of this — paste a token from the browser in a pinch.
- **An API call never starts a login.** When a session is missing or expired it fails with
  `not logged in to 'demo'` — then run `vt -e demo login` (as an agent: in the background, and tell
  the user a browser tab is waiting for them).
- Token errors on `local` right after the laptop slept are usually Podman clock drift (every token
  arrives expired): `podman machine ssh sudo chronyc makestep` — not a `vt` bug.

**Shared environments (the demo):** other people use them. Every admin sees `sandbox-…` cases in
the case list, so delete what you made when you are done, and never touch a case you did not create
— `vt delete` only accepts `sandbox-` keys, but on a shared environment that is not proof the case is
yours.

## Rules

- **Sandbox cases are keyed `sandbox-…`.** `vt copy` and `vt delete` refuse any other key, so a slip
  cannot remove a demo case. Don't work around this with raw `vt api` calls on demo cases.
- **Never finalize a sandbox case.** An imported case is a **draft** (`final=false`) and stays
  editable and deletable; Valtimo refuses to delete a final one. Cases can be started on a draft.
- Version is `1.0.0` unless you have a reason; every command takes it as the last optional argument.
- Changes are live immediately — no restart. They are **not** in the repo: to keep an example, export
  it and copy its files into `test-app/backend/src/main/resources/config/case/<key>/1.0.0/`.

## Recipes

**Copy a demo case and experiment on it** (fastest start — begins from configuration that works):

```bash
$VT copy example sandbox-try "Try-out"          # export + import under a new key
$VT processes sandbox-try
$VT start sandbox-try single-document '{"firstName":"Jan"}'   # → documentId, processInstanceId
$VT tasks <processInstanceId>
```

**Change a form:**

```bash
$VT forms sandbox-try
$VT form-get sandbox-try single-document-start > /tmp/f.json   # edit the file
$VT form-put sandbox-try single-document-start /tmp/f.json     # creates the form if it is new
```

**Change BPMN, or a process link** (`bpmn-get` writes `<process>.bpmn` and `<process>.links.json`):

```bash
$VT bpmn-get sandbox-try single-document /tmp/sd
# edit /tmp/sd/single-document.bpmn and/or the links in single-document.links.json, then:
$VT bpmn-put sandbox-try single-document /tmp/sd      # redeploys BPMN + ALL links in the file
```

To change only one link (e.g. an Epistola action's `actionProperties`), extract that element of
`processLinks` into its own file and `$VT link-put that.json`. A link's ids change on every BPMN
redeploy; `bpmn-put` refreshes the directory for that reason — always edit from a fresh `bpmn-get`.

**Build a case from scratch:** write a directory and `$VT import <dir>`. Layout (version folder uses
dashes, like Valtimo's own export):

```
config/case/sandbox-hello/1-0-0/
  case/definition/sandbox-hello.case-definition.json
  document/definition/sandbox-hello.schema.document-definition.json
  bpmn/sandbox-hello.bpmn
  form/sandbox-hello-start.form.json
  process-link/sandbox-hello.process-link.json
  process-document-link/sandbox-hello.process-document-link.json
```

A minimal set that was verified to import, start and produce a user task:

- case definition: `{"key":"sandbox-hello","versionTag":"1.0.0","name":"Hello","canHaveAssignee":true,"autoAssignTasks":false}`
- document definition: a JSON schema with `"$id":"sandbox-hello.schema"` and `"additionalProperties":false`
- BPMN: process `id` = the key you start with, `isExecutable="true"`, `camunda:historyTimeToLive` set
- process links (an array): `{"activityId":"start","activityType":"bpmn:StartEvent:start","processLinkType":"form","formDefinitionName":"sandbox-hello-start"}` — user tasks use `bpmn:UserTask:create`
- process-document link: `[{"processDefinitionKey":"sandbox-hello","canInitializeDocument":true,"startableByUser":true}]`
- forms bind to the document with `"properties":{"sourceKey":"doc:/name"}`

For anything richer, copy the shape from a demo case under
`test-app/backend/src/main/resources/config/case/` (e.g. Epistola plugin links in
`example/1.0.0/process-link/single-document.process-link.json`) or from `vt export`. Plugin links
reference a plugin configuration id: `$VT api GET "/api/v1/plugin/configuration?pluginDefinitionKey=epistola"`.
Import a zip from another environment under a sandbox key with `vt import file.zip sandbox-x "Name"`.

**Clean up:**

```bash
$VT delete sandbox-try     # deletes the case's documents first, then the case definition
```

Deleting only the case definition leaves its documents behind as orphans — that is why `delete`
searches and removes them first. Process definitions stay in the engine's history; harmless.

## API notes (verified against Valtimo 13.50)

Easy to get wrong when calling the API directly with `vt api METHOD PATH [curl args]`:

- Starting a case: `request` needs `definition` **and** `caseDefinitionKey` + `caseDefinitionVersionTag`.
- `PUT /api/v1/process-link` picks its DTO by the fields present: send the link as read but **without**
  `processDefinitionId`, `activityId`, `activityType`, or it fails with "Cannot deduce unique subtype".
- Redeploying BPMN (`PUT …/process-definition`, multipart) **replaces all links** with the
  `processLinks` part; each link keeps `processDefinitionId` but drops `id`.
- `GET …/process-definition/key/{key}` returns BPMN XML with raw control characters: `jq` rejects it,
  Python's `json.loads(..., strict=False)` reads it.
- `formDefinition` is an object when read and a JSON **string** when written.
- Valtimo also ships a Deployer API (`/api/deployer/v1/case-definition/import|export`, role
  `ROLE_DEPLOYER`, OpenAPI at `/api/deployer/v1/openapi.json`) meant for pipelines; `vt` uses the
  management endpoints because only those cover the small edits.
