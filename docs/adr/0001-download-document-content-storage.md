# ADR 0001 — Where `download-document` puts the generated PDF (`storageTarget`)

- **Status:** Proposed
- **Date:** 2026-06-14
- **Deciders:** Epistola plugin maintainers
- **Related:** PR #52 (catch-event stall fix + interim `byte[]` storage), `EpistolaPlugin.downloadDocument`, `docs/document-component.md`, `docs/async.md`

## Context and problem statement

The `download-document` plugin action fetches a generated PDF from Epistola and makes it available to the rest of the BPMN process. It originally stored the PDF as a **Base64 string process variable** (`contentVariable`). Putting a real document into a process variable is wrong in three independent ways:

1. **`varchar(4000)` overflow.** Operaton stores string variables in `ACT_RU_VARIABLE.TEXT_` / `ACT_HI_VARINST.TEXT_`, both `varchar(4000)`. Any real document's Base64 exceeds that, so the historic-variable INSERT fails with `value too long for type character varying(4000)`. Because the download service task ran in the same transaction as the message correlation that resumed the process, the failure rolled the correlation back and the `EpistolaDocumentGenerated` intermediate catch event never completed — the process stalled. (Mitigated in PR #52 by `camunda:asyncAfter` on the catch events, which commits the correlation before the download task runs.)

2. **The whole document leaks into the task HTTP response.** Valtimo's task-detail endpoint serializes the **entire** variable map: `AbstractTaskResource.createCustomTaskDto` → `operatonTaskService.getVariables(id)` → `CustomTaskDto.getVariables()` → JSON. So any persisted variable the task can see is shipped to the browser. A `byte[]` variable serializes as Base64 (works, but ships the full document on every task open); a `FileValue` variable exposes a `ByteArrayInputStream` that Jackson cannot serialize at all (`No serializer found for ByteArrayInputStream` → HTTP 500 on task open).

3. **There is no "private persisted variable" in Valtimo/Operaton.** `getVariables(id)` returns every persisted variable; there is no per-variable "do not expose" flag. A variable cannot be both persisted _and_ hidden from the task API.

Two facts shape the durable answer:

- **Epistola is the system of record, but not forever.** The small Epistola `documentId` is already persisted (in the `epistolaResult` rich-result object) and the UI streams the PDF on demand via the authorized `GET /documents/download` endpoint. Epistola retains documents for a while (longer than the temporary-storage TTL below), so a materialized copy can be **re-derived by re-downloading** — but Epistola does **not** retain indefinitely, so "always fetch from Epistola" is not a durable strategy and is inefficient (a network round-trip per use).
- **The Documenten API (ZGW) is where most case documents belong — but it is not universal.** Many customers run a Documenten API backend; many do not. The plugin must **not require** ZGW. Valtimo already ships the bridge: `DocumentenApiPlugin.store-temp-document` reads a **temporary-resource-storage id** from a process variable, uploads the bytes to the Documenten API, and writes back the durable `documentUrl`. So ZGW is reached by _composition_, not by a target this plugin owns.

## Decision drivers

- **No required infrastructure.** No host should be forced to run S3, a resource backend, or a Documenten API to use `download-document`.
- **No private content in the task-detail HTTP payload.**
- **No `varchar(4000)` limit.**
- **Durability matched to the deployment** — including surviving long-lived human tasks, restarts, and cluster nodes when the customer needs it.
- **Composes with what the customer already has** (notably the Documenten API plugin) rather than re-implementing it.
- **Clear, target-specific configuration.** The output variable a process-link configures should match the chosen target (a resource id vs inline bytes). Backward compatibility with the old single `contentVariable` is explicitly **not** required (pre-1.0; no external consumers depend on it).

## Decision outcome

A _download_ action must download — i.e. it always **materializes** the bytes somewhere. "Store nothing / fetch on demand" is therefore **not a mode of this action**; it is the choice to _not use this action at all_ (use the persisted `documentId` + the streaming endpoint instead). See "When not to use download-document" below.

**Introduce a `storageTarget` action property** that selects _where_ the materialized PDF goes. Each target only touches the backend it needs and fails clearly if that backend is absent; none is a hard dependency. The `documentId` is always retained, so re-download from Epistola is the universal recovery path within Epistola's retention window.

| `storageTarget`                | Writes to the variable    | Backend needed                                  | When to use                                                                                                                                                                                           |
| ------------------------------ | ------------------------- | ----------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `temporary-resource` (default) | a **temp resource id**    | `temporary-resource-storage` (ubiquitous)       | The bridge to ZGW (`store-temp-document`) and any temp consumer. Small id, no leak. Re-downloadable from Epistola if the ~60 min TTL passes before it is consumed.                                    |
| `durable-resource`             | a **durable resource id** | a `ResourceService` backend (S3 / shared local) | Non-ZGW apps that still want the bytes durably in Valtimo across long waits.                                                                                                                          |
| `process-variable`             | inline `byte[]`           | none                                            | Supported, not default. Small / non-sensitive documents — the bytes are serialized into the task response and persist in process state. (`byte[]`, not a String, to avoid the `varchar(4000)` limit.) |

### How ZGW / Documenten API fits (composition, not a target)

`download-document(storageTarget = temporary-resource)` → process variable holds a temp resource id → chain Valtimo's `documenten-api: store-temp-document` (pass that variable as `localDocumentLocation`) → durable zaakdocument + `documentUrl`. Upload as **concept** before a review task and flip to **definitief** on approval, so the human reviews against the durable Documenten API copy and the temp resource is consumed within its TTL.

### When not to use download-document (view-only / no materialization)

If nothing in the process needs the bytes materialized — e.g. a user task just needs to _view_ the PDF — do **not** add a `download-document` step. The `documentId` on `epistolaResult` plus the authorized `GET /documents/download` streaming endpoint already serve the PDF on demand (`docs/document-component.md`). This avoids storing anything and keeps the document out of the variable map entirely.

### Rejected

- **Temporary storage as a final resting place** across a human task — its ~60 min, node-local, non-durable lifetime (`valtimo.temporaryResourceStorage.retentionInMinutes`, default 60; `Files.createTempDirectory`) does not survive long waits/restarts/cluster. It is only used here as an _immediately consumed bridge_.
- **Epistola on-demand as a durable strategy** — Epistola retention is finite and per-use fetches are inefficient. Re-download remains a _recovery_ path, not the primary store.
- **A built-in `case-document`/Documenten-API target** — would couple the plugin to ZGW and duplicate `DocumentenApiPlugin`. Use composition instead.

## Phasing

1. **Done (PR #52, merged):** `asyncAfter` on the `EpistolaDocumentGenerated` catch events (correct regardless of storage) + interim inline `byte[]` storage that unblocks generation. Flagged in code/CHANGELOG as interim, governed by this ADR.
2. **This work:** add the `storageTarget` property; implement **`temporary-resource` (new default)** and keep **`process-variable`** as a supported non-default option (with caveats). Backend action + frontend configurator + tests + update the demo `*.process-link.json` and `docs/use-cases.md` to show the temp-resource → `store-temp-document` recipe.
3. **Later, on demand:** implement `durable-resource` when a concrete non-ZGW durability use case appears.

## Consequences

- **Positive:** no required infra; default keeps private bytes out of the task payload and out of the `varchar` limit; ZGW reached by composition with the platform plugin; durable option available when needed; inline path kept as a supported option for small documents; `documentId` retained as a universal re-download fallback.
- **Negative / cost:** a configurable strategy is more surface to build, document, and test. The action now has target-specific output variables (`resourceIdVariable` for `temporary-resource`, `contentVariable` for `process-variable`) and a new default — existing process-links must be updated to the new shape (no back-compat shim; the demo links were migrated in this change).
- **Follow-up:** frontend `download-document` configurator gains a `storageTarget` selector; `EpistolaPluginDownloadDocumentTest` extended per target; consider a `fileName`/metadata property so `store-temp-document` gets a sensible filename.
