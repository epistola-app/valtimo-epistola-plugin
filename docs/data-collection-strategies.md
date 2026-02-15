# Data Collection Strategies

## The Problem

Epistola templates require structured input data (see [data-mapping.md](data-mapping.md)). Simple cases work fine: the customer name lives in the case document, the invoice date is a process variable, and the mapping is straightforward.

But real-world document generation often needs data from multiple sources:

- **Case document** — core case data managed by Valtimo
- **Process variables** — transient values from the current process
- **External systems** — OpenZaak (zaken, documenten), Objects API, BRP, KVK, etc.

Not all of this data is available in `doc:` or `pv:` at the time of generation. A permit decision letter might need the applicant's address from BRP, the case status from OpenZaak, and the decision details from the case document — three different sources.

This document describes four strategies for collecting and assembling this data before it reaches the Epistola data mapping. Each has different trade-offs for complexity, reusability, and batch generation support.

**No recommendation is made here.** The right strategy depends on the use case, and multiple strategies can coexist in the same Valtimo instance.

## Strategy 1: BPMN Orchestration

Fetch all required data in preceding service tasks, store it in process variables or the case document, then map from those known sources.

### How it works

```
[Service Task: fetch-brp-data]     → pv:applicantAddress, pv:applicantName
[Service Task: fetch-zaak-status]  → pv:zaakStatus, pv:zaakDecision
[Service Task: generate-document]  → maps from pv: and doc: as usual
```

Each service task calls an external system and stores the result in process variables. By the time `generate-document` runs, all data is available via `pv:` prefixes.

### Example

A permit decision letter needs BRP data and the zaak status:

```json
{
  "applicantName": "pv:applicantName",
  "applicantAddress": {
    "street": "pv:applicantStreet",
    "city": "pv:applicantCity",
    "postalCode": "pv:applicantPostalCode"
  },
  "decision": "doc:permit.decision",
  "zaakStatus": "pv:zaakStatus"
}
```

The BPMN process handles the orchestration:

```
[Start] → [Fetch BRP] → [Fetch Zaak] → [Generate Document] → [End]
```

### Pros

- **Fully visible** — the entire data flow is in the BPMN model, easy to trace and debug
- **No plugin changes** — works with the current `pv:` and `doc:` mapping infrastructure
- **Flexible** — any service task plugin can be wired in (OpenZaak, REST, Objects API, etc.)
- **Error handling per source** — each fetch can have its own error boundary and retry logic

### Cons

- **Verbose processes** — every document type needs its own chain of fetch tasks
- **Process variable sprawl** — complex templates can require dozens of intermediary variables
- **Tight coupling** — the BPMN model must know exactly which data each template needs
- **Duplication** — similar fetch patterns are repeated across processes

### Batch implications

Poorly suited for batch generation. If you need to generate 200 letters, each with different BRP data, you'd need either:

- A multi-instance subprocess that runs the full fetch chain per recipient — 200 BRP calls in sequence (or parallel, but still 200 separate process instances)
- Pre-fetching all data in bulk before entering a multi-instance generation loop — requires custom bulk-fetch logic and temporary storage for the result set

The process variable approach fundamentally assumes a single execution context. Scaling it to N recipients means N execution contexts, each running its own fetch chain.

## Strategy 2: Custom Value Resolvers

Extend Valtimo's `ValueResolverService` with new prefixes that fetch data on-the-fly during mapping resolution.

### How it works

Valtimo's value resolution already supports `doc:`, `case:`, and `pv:` prefixes. Custom value resolvers add new prefixes like `oz:` (OpenZaak), `brp:`, or `obj:` (Objects API). During the data mapping resolution pass (see [data-mapping.md](data-mapping.md), section 4), these prefixes trigger API calls to external systems.

```json
{
  "applicantName": "brp:bsn(doc:applicant.bsn).name",
  "applicantAddress": "brp:bsn(doc:applicant.bsn).address",
  "zaakStatus": "oz:zaak(doc:zaakUrl).status",
  "decision": "doc:permit.decision"
}
```

The `brp:` resolver takes a BSN, calls the BRP API, and returns the requested field. The `oz:` resolver takes a zaak URL, calls OpenZaak, and returns the requested attribute.

### Example implementation sketch

```java
@Component
public class BrpValueResolverFactory implements ValueResolverFactory {

    @Override
    public String supportedPrefix() {
        return "brp";
    }

    @Override
    public ValueResolver createResolver(String processInstanceId) {
        return new BrpValueResolver(brpClient, processInstanceId);
    }
}
```

The data mapping stays clean — no intermediary process variables, no extra service tasks:

```
[Start] → [Generate Document] → [End]
```

### Pros

- **Clean BPMN** — no fetch tasks cluttering the process model
- **Reusable** — once a resolver is registered, any template mapping can use it
- **Declarative** — the mapping itself describes where data comes from
- **Composable** — resolvers can reference other resolved values (e.g., BSN from `doc:`)

### Cons

- **Hidden complexity** — API calls happen implicitly during resolution; failures are harder to trace
- **No per-source error handling** — all resolution happens in one batch; a single failure can block the entire mapping
- **Development effort** — each external system needs a resolver implementation
- **Caching concerns** — the same BSN might be resolved multiple times if multiple fields reference it; resolvers need internal caching to avoid redundant API calls
- **Valtimo coupling** — value resolvers are a Valtimo framework concept; implementation must follow Valtimo's `ValueResolverFactory` contract

### Batch implications

Depends heavily on resolver implementation. For batch generation:

- **Naive approach**: each of 200 documents triggers individual API calls per resolver — N documents x M external calls = potentially thousands of requests
- **Smart approach**: resolvers could accept batch hints, pre-fetching data for all recipients in a single API call and serving individual lookups from a local cache

The batch-aware resolver pattern requires careful design. The resolver needs to know it's operating in a batch context and which identifiers to pre-fetch. This is not supported by Valtimo's current `ValueResolverFactory` contract and would need extension or a wrapper layer.

## Strategy 3: Document Enrichment

Enrich the case document with all required data before generation. The case document becomes the single canonical data layer — all mappings use `doc:` exclusively.

### How it works

Before document generation, a dedicated enrichment step collects data from all external sources and writes it into the case document. This can be a service task, a scheduled job, or part of a case lifecycle event.

```
[Service Task: enrich-case-document]  → writes external data into doc
[Service Task: generate-document]     → maps exclusively from doc:
```

The enrichment task fetches BRP data, zaak status, and any other external data, then merges it into the case document:

```json
{
  "applicant": {
    "bsn": "123456789",
    "name": "Jan de Vries",
    "address": {
      "street": "Kerkstraat 1",
      "city": "Amsterdam",
      "postalCode": "1012 AB"
    }
  },
  "zaak": {
    "status": "Besluit genomen",
    "decision": "Toegekend"
  },
  "permit": {
    "decision": "approved",
    "validUntil": "2027-01-01"
  }
}
```

The mapping becomes simple and uniform:

```json
{
  "applicantName": "doc:applicant.name",
  "applicantAddress": {
    "street": "doc:applicant.address.street",
    "city": "doc:applicant.address.city",
    "postalCode": "doc:applicant.address.postalCode"
  },
  "zaakStatus": "doc:zaak.status",
  "decision": "doc:permit.decision"
}
```

### Pros

- **Single data source** — all mappings use `doc:`, simple and consistent
- **Data reuse** — enriched data is available for any future document generation or process logic
- **Auditable** — the case document is a persisted record; you can see exactly what data was used
- **Decoupled timing** — enrichment can happen well before generation (e.g., on case creation or status change)

### Cons

- **Document bloat** — the case document accumulates data from many sources, growing in size and complexity
- **Staleness** — enriched data can become stale if the source changes after enrichment; you need a strategy for re-enrichment
- **Schema management** — the case document schema must accommodate all external data structures
- **Side effects** — enrichment modifies a shared resource (the case document), which can conflict with other processes or manual edits
- **Not always appropriate** — some data is transient (e.g., "current date") and doesn't belong in the case document

### Batch implications

Moderately suited for batch generation. If the enrichment step has already run for all cases, generation can proceed without any external calls — pure `doc:` resolution is fast and local.

However, if enrichment needs to happen as part of the batch:

- **Bulk enrichment** — a single job enriches all target cases before the batch generation starts; external APIs can be called in bulk where supported
- **Stale data risk** — for large batches, there's a window between enrichment and generation where source data might change

The key advantage is that enrichment and generation are decoupled. You can enrich 200 cases in a bulk operation (potentially parallel, with smart batching of API calls), then generate 200 documents purely from local data.

## Strategy 4: Data Collection Action

A dedicated Epistola plugin action that aggregates data from multiple configured sources into a single data object, stored as a process variable, ready for the mapping step.

### How it works

A new `collect-document-data` plugin action is configured with a list of data sources and their mappings. At runtime, it fetches from each source, merges the results, and stores the combined object as a process variable.

```
[Service Task: collect-document-data]  → pv:documentData (merged object)
[Service Task: generate-document]      → maps from pv:documentData.*
```

### Example configuration

```json
{
  "outputVariable": "documentData",
  "sources": [
    {
      "type": "case-document",
      "mappings": {
        "decision": "permit.decision",
        "applicantBsn": "applicant.bsn"
      }
    },
    {
      "type": "rest-api",
      "url": "https://brp.example.com/api/persons/${applicantBsn}",
      "authentication": "plugin:brp-api-auth",
      "mappings": {
        "applicantName": "naam.volledigeNaam",
        "applicantAddress": "verblijfplaats"
      }
    },
    {
      "type": "openzaak",
      "zaakUrl": "doc:zaakUrl",
      "mappings": {
        "zaakStatus": "status.omschrijving"
      }
    }
  ]
}
```

The resulting process variable:

```json
{
  "decision": "approved",
  "applicantBsn": "123456789",
  "applicantName": "Jan de Vries",
  "applicantAddress": {
    "street": "Kerkstraat 1",
    "city": "Amsterdam",
    "postalCode": "1012 AB"
  },
  "zaakStatus": "Besluit genomen"
}
```

### Pros

- **Single action** — one service task replaces a chain of fetch tasks
- **Configurable** — sources and their mappings are defined in the action config, not in BPMN
- **Consistent output** — the merged object has a predictable structure for downstream mapping
- **Separation of concerns** — data collection logic is in the plugin, BPMN stays clean

### Cons

- **Plugin complexity** — the action must support multiple source types, each with its own authentication and error handling
- **Configuration overhead** — the action config is essentially a mini-ETL definition; can become complex
- **Opaque** — a single action does many things; harder to debug than individual service tasks
- **Source type limitations** — only source types implemented in the plugin are supported; new sources require plugin changes
- **Tight coupling to plugin** — unlike BPMN orchestration, this approach locks the data collection logic into the Epistola plugin

### Batch implications

Well-suited for batch generation if designed with batching in mind. The action could accept a list of recipients (e.g., BSN numbers) and:

- **Fan out** — fetch data for all recipients in parallel or in bulk API calls
- **Collect** — return a list of merged data objects, one per recipient
- **Generate** — pass each object to a generation request

This requires a batch-aware variant of the action (e.g., `collect-batch-document-data`) that returns a collection instead of a single object. The generate-document action would then iterate over the collection.

However, this concentrates a lot of logic in a single action. Debugging a failed batch item means tracing through the collection action's internal state rather than following a BPMN execution path.

## Comparison

| Aspect | BPMN Orchestration | Custom Value Resolvers | Document Enrichment | Data Collection Action |
|--------|-------------------|----------------------|---------------------|----------------------|
| BPMN complexity | High (many tasks) | Low (one task) | Medium (enrich + generate) | Low (collect + generate) |
| Implementation effort | Low (existing plugins) | High (resolver per source) | Medium (enrichment logic) | High (multi-source action) |
| Debuggability | Excellent (visible flow) | Poor (implicit calls) | Good (data in document) | Medium (single action) |
| Reusability | Low (per-process) | High (any mapping) | High (data in document) | Medium (per-action config) |
| Batch suitability | Poor | Medium (needs extensions) | Good (decouple enrich/generate) | Good (if batch-designed) |
| Data freshness | Excellent (fetched at runtime) | Excellent (fetched at runtime) | Risk of staleness | Excellent (fetched at runtime) |
| Valtimo coupling | Low | High | Low | Medium |

## Combining Strategies

These strategies are not mutually exclusive. A pragmatic approach might combine them:

- **Simple cases**: BPMN orchestration — when a template only needs one or two external values, a couple of fetch tasks are the simplest solution
- **Recurring patterns**: Custom value resolvers — when the same external lookup appears across many templates (e.g., BRP data by BSN), a resolver eliminates repetitive BPMN tasks
- **Data-heavy cases**: Document enrichment — when a case naturally accumulates rich data over its lifecycle, use the enriched document as the generation source
- **Batch generation**: Data collection action or bulk enrichment — purpose-built for collecting data at scale

The data mapping layer (see [data-mapping.md](data-mapping.md)) is agnostic to how data arrives in `doc:` or `pv:` — it just resolves expressions. This means the strategy choice is purely an upstream concern and can vary per process or even per template.
