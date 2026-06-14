# ADR 0001 — How `download-document` makes generated document content available to a process

- **Status:** Proposed
- **Date:** 2026-06-14
- **Deciders:** Epistola plugin maintainers
- **Related:** PR #52 (catch-event stall fix), `EpistolaPlugin.downloadDocument`, `docs/document-component.md`, `docs/async.md`

## Context and problem statement

The `download-document` plugin action fetches a generated PDF from Epistola and is meant to make it available to the rest of the BPMN process. It originally stored the PDF as a **Base64 string process variable** (`contentVariable`). Putting a real document into a process variable turned out to be wrong in three independent ways:

1. **`varchar(4000)` overflow.** Operaton stores string variables in `ACT_RU_VARIABLE.TEXT_` / `ACT_HI_VARINST.TEXT_`, both `varchar(4000)`. Any real document's Base64 exceeds that, so the historic-variable INSERT fails with `value too long for type character varying(4000)`. Because the download service task ran in the same transaction as the message correlation that resumed the process, the failure rolled the correlation back and the `EpistolaDocumentGenerated` intermediate catch event was never completed — the process stalled. (Mitigated separately in PR #52 by `camunda:asyncAfter` on the catch events, which commits the correlation before the download task runs.)

2. **The whole document leaks into the task HTTP response.** Valtimo's task-detail endpoint serializes the **entire** variable map: `AbstractTaskResource.createCustomTaskDto` → `operatonTaskService.getVariables(id)` → `CustomTaskDto.getVariables()` → JSON. So any persisted variable that the task can see is shipped to the browser. A `byte[]` variable serializes as Base64 (works, but ships the full document on every task open); a `FileValue` variable exposes a `ByteArrayInputStream` that Jackson cannot serialize at all (`No serializer found for ByteArrayInputStream` → HTTP 500 when opening the task).

3. **There is no "private persisted variable" in Valtimo/Operaton.** `getVariables(id)` returns every persisted variable; there is no per-variable "do not expose" flag. So a variable cannot be both persisted _and_ hidden from the task API.

The document's durable system of record is **Epistola itself**. The small Epistola `documentId` is already persisted (in the `epistolaResult` rich-result object), and the UI already streams the PDF on demand via the authorized `GET /documents/download` endpoint (`docs/document-component.md`) rather than reading any content variable.

We need a way to make the content available to **downstream BPMN steps** that may run **after a long-lived human task** (e.g. "Controleer Bevestigingsbrief", which can sit for hours or days), **without** leaking the document into the task response, and **without** the `varchar(4000)` limit.

## Decision drivers

- **Durability across long waits, restarts, and cluster nodes.** A human task may pause the process for days; any node may resume it.
- **No private content in the task-detail HTTP payload.**
- **No `varchar(4000)` limit.**
- **Minimal coupling / optional infrastructure.** Not every host app configures an S3 resource backend or a Documenten API.
- **Backward compatibility.** Existing process-links use `contentVariable` and (historically) read Base64.

## Considered options

| Option                                                                                                           | Durable across long wait / restart / cluster                                                                                         | In task response?                                 | `varchar` limit  | Extra infra needed            | Notes                                                                                                                                                                                                             |
| ---------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------- | ---------------- | ----------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **A. Base64 string variable** (original)                                                                         | persisted, but…                                                                                                                      | **yes** (Base64)                                  | **fails > 4000** | none                          | The original bug. Disqualified for real documents.                                                                                                                                                                |
| **B. `byte[]` / `FileValue` variable**                                                                           | persisted                                                                                                                            | **yes** (`byte[]`→Base64) / **500** (`FileValue`) | no               | none                          | `byte[]` "works" but leaks the document on every task open; `FileValue` 500s. Current interim state on the PR branch is `byte[]`.                                                                                 |
| **C. Temporary resource storage** (`TemporaryResourceStorageService`)                                            | **no** — ~60 min retention (`valtimo.temporaryResourceStorage.retentionInMinutes`, default 60), node-local temp dir, lost on restart | no (only an id)                                   | no               | none                          | Designed for short upload→submit flows. **Unsuitable** when a human task sits between download and use.                                                                                                           |
| **D. Durable resource storage** (`ResourceService` + `local-resource` on shared volume, or S3/openzaak-resource) | **yes** (with S3 / shared volume)                                                                                                    | no (only a resource id)                           | no               | a configured resource backend | Idiomatic Valtimo file-by-reference. Survives long waits.                                                                                                                                                         |
| **E. Case document** (Documenten API / Valtimo document storage)                                                 | **yes**                                                                                                                              | no (document is in the dossier)                   | no               | a documents backend           | Proper home for case-related documents; visible in the case file. Heaviest integration.                                                                                                                           |
| **F. Fetch from Epistola on demand** (no storage in Valtimo)                                                     | **yes** (Epistola is the system of record; only the small `documentId` is persisted)                                                 | no                                                | no               | none                          | The consuming step downloads the bytes from Epistola when it needs them, using the persisted `documentId` — the same pattern the UI already uses. The `download-document` "content variable" becomes unnecessary. |

## Decision outcome

**Make the storage target of `download-document` a configurable strategy rather than hard-coding one**, because different deployments have different durability needs and available infrastructure ("we may need to support multiple ways"). Introduce a `contentTarget` (working name) action property selecting one of:

- `epistola-on-demand` — **recommended default.** Store nothing; ensure the `documentId`/tenant are persisted (already true via `epistolaResult` + `epistolaTenantId`). Downstream steps fetch bytes via the plugin/`EpistolaService` when needed. Zero leak, zero retention/size concerns, no extra infra.
- `durable-resource` — store via `ResourceService` and put the **resource id** in `contentVariable`. For hosts that have a durable resource backend and genuinely need the bytes materialized in Valtimo across long waits.
- `case-document` — write the PDF into the case file via the Documenten API. For when the document belongs in the dossier.
- `process-variable` — legacy inline `byte[]`/Base64. **Deprecated**; retained only for tiny documents and backward compatibility, with a documented warning that it leaks into the task response and is capped by `varchar(4000)`.

Temporary resource storage (option C) is **rejected** as a target because its ~60-minute, node-local, non-durable lifetime does not survive a human task.

### Phasing

1. **Now (PR #52):** keep the catch-event stall fix (`asyncAfter` on the `EpistolaDocumentGenerated` catch events) — it is correct and independent of the storage choice. Treat the current `byte[]` storage as a **temporary interim** that unblocks generation; flag in the PR that the storage strategy is governed by this ADR and that the default will move to `epistola-on-demand`.
2. **Next:** implement `epistola-on-demand` (default) + keep `process-variable` (deprecated) behind the new `contentTarget` property. Provide a small helper / action for downstream steps to obtain the bytes from Epistola by `documentId`.
3. **Later, on demand:** implement `durable-resource` and `case-document` when a concrete use case needs the bytes materialized in Valtimo.

## Consequences

- **Positive:** no private document in the task payload by default; no `varchar` limit; durability matches each deployment's infra; existing inline behavior stays available (deprecated) for backward compatibility.
- **Negative / cost:** a configurable strategy is more surface area to build, document, and test. `epistola-on-demand` changes the consumer contract — downstream steps fetch bytes rather than reading a content variable; this must be documented and the demo process-links updated. `durable-resource`/`case-document` add backend dependencies when enabled.
- **Follow-up:** update `docs/use-cases.md` and the demo `*.process-link.json` files to the chosen default; add the `contentTarget` property to the frontend `download-document` configurator; extend `EpistolaPluginDownloadDocumentTest` per strategy.
