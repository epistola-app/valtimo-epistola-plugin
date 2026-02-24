# Use Cases

This document describes four demo scenarios that showcase the Epistola plugin integration with GZAC/Valtimo. Each scenario is a standalone case type targeting a distinct integration pattern.

**Related documentation:**
- [Data Mapping](data-mapping.md) — how case/process data flows into Epistola templates
- [Async Document Generation](async.md) — the centralized polling and message correlation architecture
- [Data Collection Strategies](data-collection-strategies.md) — strategies for assembling template input data

## Overview

| # | Case type | Key pattern | Plugin actions used |
|---|-----------|-------------|---------------------|
| 1 | [Vergunningsaanvraag](#1-vergunningsaanvraag-permit-application) | Rich data mapping, user review | generate, wait, download |
| 2 | [Bezwaarprocedure](#2-bezwaarprocedure-objection-handling) | Multiple templates, conditional logic | generate (x2), wait (x2), download (x2) |
| 3 | [Massale correspondentie](#3-massale-correspondentie-bulk-document-generation) | Bulk generation (1000 docs) | generate (xN), wait (xN), download (xN) |
| 4 | [Subsidie zaakdossier](#4-subsidie-zaakdossier-case-file-with-openzaak-archival) | Parallel generation, OpenZaak archival | generate (x3), wait, download (x3) + Documenten/Zaken API |

---

## 1. Vergunningsaanvraag (Permit Application)

### Scenario

A citizen applies for a building permit. The system automatically generates a confirmation letter, mapping applicant data, property details, and a dynamic list of requested activities. A case worker reviews the generated PDF before it is sent.

### Focus

- **Rich data mapping**: nested objects (`doc:applicant.address.street`), arrays with per-item field renaming (`_source` pattern), mixed value resolvers (`doc:`, `pv:`)
- **Environment and variant selection**: action-level overrides
- **Full async lifecycle**: generate, message catch, download
- **User task with download link**: case worker reviews the document before the process continues

### Process: `permit-confirmation`

```
Start Form
  │  (applicant details, property, activities[])
  ▼
Service Task: "Generate Confirmation Letter"
  │  Action: generate-document
  │  Parameters:
  │    templateId:     <bevestigingsbrief template>
  │    variantId:      <formal variant>
  │    environmentId:  <production>
  │    outputFormat:   PDF
  │    filename:       "pv:confirmationFilename"
  │    resultProcessVariable: "requestId"
  │    dataMapping:
  │      applicantName:      "doc:applicant.firstName"
  │      applicantLastName:  "doc:applicant.lastName"
  │      applicantBsn:       "doc:applicant.bsn"
  │      applicantAddress:
  │        street:            "doc:applicant.address.street"
  │        houseNumber:       "doc:applicant.address.houseNumber"
  │        postalCode:        "doc:applicant.address.postalCode"
  │        city:              "doc:applicant.address.city"
  │      propertyAddress:
  │        street:            "doc:property.address.street"
  │        houseNumber:       "doc:property.address.houseNumber"
  │        postalCode:        "doc:property.address.postalCode"
  │        city:              "doc:property.address.city"
  │      kadastraalNummer:   "doc:property.kadastraalNummer"
  │      requestDate:        "pv:requestDate"
  │      activities:                              ◄── array per-item mapping
  │        _source:           "doc:activities"
  │        type:              "type"
  │        description:       "description"
  │        cost:              "estimatedCost"     ◄── field rename
  │
  │  Error boundary → Error End Event
  ▼
Message Catch Event: "EpistolaDocumentGenerated"
  │  Correlation: epistolaRequestId
  │  Receives: epistolaStatus, epistolaDocumentId, epistolaErrorMessage
  │
  │  Error boundary → Error End Event
  ▼
Exclusive Gateway: epistolaStatus == "COMPLETED"?
  ├─ No  → Error End Event
  ▼
Service Task: "Download Confirmation Letter"
  │  Action: download-document
  │  Parameters:
  │    documentIdVariable: "epistolaDocumentId"
  │    contentVariable:    "documentContent"
  ▼
User Task: "Controleer bevestigingsbrief"
  │  Form shows document metadata + download button
  │  (Base64 from pv:documentContent rendered as PDF download)
  ▼
End Event
```

### Case document schema

```json
{
  "type": "object",
  "required": ["applicant", "property"],
  "properties": {
    "applicant": {
      "type": "object",
      "required": ["firstName", "lastName", "bsn", "address"],
      "properties": {
        "firstName":  { "type": "string", "minLength": 1, "maxLength": 100 },
        "lastName":   { "type": "string", "minLength": 1, "maxLength": 100 },
        "bsn":        { "type": "string", "pattern": "^[0-9]{9}$" },
        "address": {
          "type": "object",
          "required": ["street", "houseNumber", "postalCode", "city"],
          "properties": {
            "street":      { "type": "string" },
            "houseNumber": { "type": "string" },
            "postalCode":  { "type": "string", "pattern": "^[0-9]{4}[A-Z]{2}$" },
            "city":        { "type": "string" }
          }
        }
      }
    },
    "property": {
      "type": "object",
      "required": ["address", "kadastraalNummer"],
      "properties": {
        "address": {
          "type": "object",
          "required": ["street", "houseNumber", "postalCode", "city"],
          "properties": {
            "street":      { "type": "string" },
            "houseNumber": { "type": "string" },
            "postalCode":  { "type": "string" },
            "city":        { "type": "string" }
          }
        },
        "kadastraalNummer": { "type": "string" }
      }
    },
    "activities": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "required": ["type", "description", "estimatedCost"],
        "properties": {
          "type":          { "type": "string" },
          "description":   { "type": "string" },
          "estimatedCost": { "type": "number", "minimum": 0 }
        }
      }
    }
  }
}
```

### What this demonstrates

| Capability | How it's shown |
|------------|---------------|
| Nested object mapping | `doc:applicant.address.street` resolves through 2 levels |
| Array per-item mapping | `activities` uses `_source` + field rename (`estimatedCost` → `cost`) |
| Environment override | Action-level `environmentId` overrides the plugin default |
| Variant selection | Specific variant chosen for the formal letter style |
| Async completion | Message Catch Event waits for centralized poller to correlate |
| Download + review | `download-document` stores Base64 in pv, user task renders download |
| Error handling | Boundary error events on both generate and receive tasks |

---

## 2. Bezwaarprocedure (Objection Handling)

### Scenario

A citizen files an objection against a municipal decision. The system generates an acknowledgment letter immediately. A case worker then assesses the objection, and based on their ruling (upheld, rejected, or partially upheld), the system generates the appropriate decision letter from a different template.

### Focus

- **Multiple templates** in a single process (acknowledgment + decision)
- **Conditional template selection** via exclusive gateway
- **Sequential generation**: first document completes before human review, then second document
- **Documents tab storage**: generated PDFs stored on the case via Valtimo's resource storage

### Process: `objection-handling`

```
Start Form
  │  (objector details, original decision reference, objection grounds)
  ▼
Service Task: "Genereer Ontvangstbevestiging"
  │  Action: generate-document
  │  Template: "Ontvangstbevestiging Bezwaar"
  │  dataMapping:
  │    objectorName:      "doc:objector.firstName"
  │    objectorLastName:  "doc:objector.lastName"
  │    objectorAddress:
  │      street:           "doc:objector.address.street"
  │      houseNumber:      "doc:objector.address.houseNumber"
  │      postalCode:       "doc:objector.address.postalCode"
  │      city:             "doc:objector.address.city"
  │    originalDecisionRef:   "doc:originalDecision.reference"
  │    originalDecisionDate:  "doc:originalDecision.date"
  │    originalDecisionSubject: "doc:originalDecision.subject"
  │    receivedDate:          "doc:objection.receivedDate"
  │  resultProcessVariable: "ackRequestId"
  │
  │  Error boundary → Error End Event
  ▼
Message Catch Event: "EpistolaDocumentGenerated"
  │  (waits for acknowledgment letter to complete)
  ▼
Service Task: "Download Ontvangstbevestiging"
  │  Action: download-document
  │  documentIdVariable: "epistolaDocumentId"
  │  contentVariable:    "ackDocumentContent"
  ▼
Service Task: "Store on case"                    ◄── Valtimo documents tab
  │  (stores Base64 PDF as case resource)
  ▼
User Task: "Beoordeel bezwaar"
  │  Form fields:
  │    - decision (dropdown): gegrond / ongegrond / deels_gegrond
  │    - motivation (textarea)
  │  Sets: pv:decision, pv:motivation
  ▼
Exclusive Gateway: pv:decision
  ├─ "gegrond"       → Service Task: generate-document (Template: "Besluit Bezwaar Gegrond")
  ├─ "ongegrond"     → Service Task: generate-document (Template: "Besluit Bezwaar Ongegrond")
  └─ "deels_gegrond" → Service Task: generate-document (Template: "Besluit Bezwaar Deels Gegrond")
  │
  │  Each branch maps: objector details + original decision + motivation + decision
  │  resultProcessVariable: "decisionRequestId"
  ▼
(merge)
  ▼
Message Catch Event: "EpistolaDocumentGenerated"
  │  (waits for decision letter to complete)
  ▼
Service Task: "Download Besluit"
  │  Action: download-document
  │  documentIdVariable: "epistolaDocumentId"
  │  contentVariable:    "decisionDocumentContent"
  ▼
Service Task: "Store on case"
  │  (stores decision letter as case resource)
  ▼
End Event
```

### Case document schema

```json
{
  "type": "object",
  "required": ["objector", "originalDecision", "objection"],
  "properties": {
    "objector": {
      "type": "object",
      "required": ["firstName", "lastName", "address"],
      "properties": {
        "firstName": { "type": "string" },
        "lastName":  { "type": "string" },
        "address": {
          "type": "object",
          "required": ["street", "houseNumber", "postalCode", "city"],
          "properties": {
            "street":      { "type": "string" },
            "houseNumber": { "type": "string" },
            "postalCode":  { "type": "string" },
            "city":        { "type": "string" }
          }
        }
      }
    },
    "originalDecision": {
      "type": "object",
      "required": ["reference", "date", "subject"],
      "properties": {
        "reference": { "type": "string" },
        "date":      { "type": "string", "format": "date" },
        "subject":   { "type": "string" }
      }
    },
    "objection": {
      "type": "object",
      "required": ["grounds", "receivedDate"],
      "properties": {
        "grounds":      { "type": "string" },
        "receivedDate": { "type": "string", "format": "date" }
      }
    }
  }
}
```

### What this demonstrates

| Capability | How it's shown |
|------------|---------------|
| Multiple templates | Acknowledgment letter and decision letter use different templates |
| Conditional logic | Exclusive gateway selects template based on case worker's decision |
| Sequential generation | Acknowledgment must complete before human review, decision generated after |
| Human-in-the-loop | Case worker's ruling drives which template is used |
| Documents tab | Both generated PDFs stored as case resources, visible in Valtimo's Documents tab |
| Reusable pattern | Same generate → wait → download → store cycle used twice in one process |

---

## 3. Massale Correspondentie (Bulk Document Generation)

### Scenario

A municipality needs to send personalized tax assessment letters to all property owners in a district. An administrator provides a collection of taxpayer records (10, 100, or 1000 items) and selects a template. The system generates a letter for each taxpayer in parallel, tracking successes and failures, and presents a summary when done.

### Focus

- **Multi-instance subprocess** with Camunda's `collection` variable
- **Scale**: designed for hundreds or thousands of documents
- **Per-instance error handling**: one failure doesn't stop the batch
- **Completion tracking**: success/failure counters
- **Centralized poller under load**: many concurrent waiting processes

### Process: `bulk-tax-letters`

```
Start Form
  │  (template selection, taxpayer data as JSON array)
  │  Sets: pv:taxpayers (collection), pv:selectedTemplateId
  ▼
Script Task: "Initialize counters"
  │  Sets: pv:successCount = 0, pv:failCount = 0, pv:totalCount = taxpayers.size()
  ▼
Multi-Instance Subprocess (parallel)
  │  Collection:       pv:taxpayers
  │  Element variable: taxpayer
  │  ┌────────────────────────────────────────────────────────────┐
  │  │                                                            │
  │  │  Service Task: "Generate Tax Letter"                       │
  │  │    Action: generate-document                               │
  │  │    templateId: pv:selectedTemplateId                       │
  │  │    dataMapping:                                            │
  │  │      name:          "pv:taxpayer.name"                     │
  │  │      address:                                              │
  │  │        street:      "pv:taxpayer.address.street"           │
  │  │        postalCode:  "pv:taxpayer.address.postalCode"       │
  │  │        city:        "pv:taxpayer.address.city"             │
  │  │      propertyId:    "pv:taxpayer.propertyId"               │
  │  │      assessedValue: "pv:taxpayer.assessedValue"            │
  │  │      taxAmount:     "pv:taxpayer.taxAmount"                │
  │  │    resultProcessVariable: "requestId"                      │
  │  │                                                            │
  │  │    Error boundary ─────────────────────────┐               │
  │  │  ▼                                         ▼               │
  │  │  Message Catch: "EpistolaDocumentGenerated" │               │
  │  │  ▼                                         │               │
  │  │  Service Task: "Download Tax Letter"        │               │
  │  │    Action: download-document                │               │
  │  │  ▼                                         │               │
  │  │  Script Task: increment successCount        │               │
  │  │  ▼                                         ▼               │
  │  │  End Event (success)          Script Task: increment failCount
  │  │                               ▼                            │
  │  │                               End Event (continue)         │
  │  └────────────────────────────────────────────────────────────┘
  ▼
User Task: "Resultaatoverzicht"
  │  Form displays:
  │    - Total: pv:totalCount
  │    - Succeeded: pv:successCount
  │    - Failed: pv:failCount
  ▼
End Event
```

### Taxpayer data format

The `pv:taxpayers` process variable is a JSON array provided at start:

```json
[
  {
    "name": "J. de Vries",
    "address": { "street": "Kerkstraat 1", "postalCode": "1234AB", "city": "Amsterdam" },
    "propertyId": "AMS-2024-001",
    "assessedValue": 350000,
    "taxAmount": 1250.00
  },
  {
    "name": "A. Bakker",
    "address": { "street": "Dorpsweg 42", "postalCode": "5678CD", "city": "Rotterdam" },
    "propertyId": "RTD-2024-002",
    "assessedValue": 280000,
    "taxAmount": 980.00
  }
]
```

### Design considerations

**Multi-instance + message correlation**: Each subprocess instance gets its own `epistolaJobPath` process variable (encoding both tenant and request ID), so the centralized poller can correlate each completion independently. The `correlateAllWithResult()` call in `EpistolaMessageCorrelationService` handles multiple matching executions safely.

**Centralized poller efficiency**: The poller queries all executions waiting on `"EpistolaDocumentGenerated"` in a single database query, extracts tenant and request ID from the composite `epistolaJobPath` variable, groups by tenant, and checks job status in batches. With 1000 concurrent waiting instances, this is one scheduled task making N API calls — not 1000 timer loops in the engine. See [async.md](async.md) for details.

**Throttling options**: For very large batches, consider:
- **Sequential multi-instance** instead of parallel (set `isSequential="true"`) to limit concurrent API load
- **Camunda `completionCondition`** to stop early (e.g., if failure rate exceeds threshold)
- **Poller interval tuning** via `epistola.poller.interval` — shorter interval for faster batch completion

**Error isolation**: The boundary error event on the generate task catches failures per instance. The subprocess continues for other taxpayers even if one generation fails.

### What this demonstrates

| Capability | How it's shown |
|------------|---------------|
| Multi-instance subprocess | Camunda `collection` variable iterates over taxpayer array |
| Scale | Works with 10, 100, or 1000 items — same pattern |
| Per-instance data mapping | Each iteration maps from `pv:taxpayer.*` (element variable) |
| Error resilience | Boundary error per instance; failure counter; batch continues |
| Centralized poller at load | Many concurrent waiting processes, single poller handles all |
| Completion summary | User task shows success/failure breakdown |

---

## 4. Subsidie Zaakdossier (Case File with OpenZaak Archival)

### Scenario

When a subsidy case reaches a decision milestone, the system generates a complete document package: a decision letter, a financial overview, and a conditions appendix. All three are generated in parallel. Once complete, each document is downloaded, stored in the Documenten API (OpenZaak), and linked to the Zaak. The documents then appear automatically in Valtimo's Documents tab.

### Focus

- **Parallel document generation**: 3 documents generated simultaneously (parallel gateway)
- **OpenZaak Documenten API archival**: using Valtimo's built-in `documenten-api` plugin
- **Zaak-Informatieobject linking**: using Valtimo's built-in `zaken-api` plugin
- **Multi-plugin orchestration**: Epistola + Documenten API + Zaken API in one process
- **Documents tab**: archived documents visible through zaak-informatieobject link

### Process: `subsidy-decision-package`

```
Start Form
  │  (subsidy details, beneficiary, decision, financial data)
  ▼
Parallel Gateway (fork)
  ├──────────────────────┬──────────────────────┐
  ▼                      ▼                      ▼
Service Task:          Service Task:          Service Task:
"Genereer              "Genereer              "Genereer
 Subsidiebesluit"       Financieel Overzicht"   Voorwaarden Bijlage"
  │ generate-document    │ generate-document    │ generate-document
  │ Template: decision   │ Template: financial  │ Template: conditions
  │ resultPV: reqId1     │ resultPV: reqId2     │ resultPV: reqId3
  ▼                      ▼                      ▼
Message Catch:         Message Catch:         Message Catch:
"EpistolaDocument      "EpistolaDocument      "EpistolaDocument
 Generated"             Generated"             Generated"
  ▼                      ▼                      ▼
Service Task:          Service Task:          Service Task:
"Download              "Download              "Download
 Subsidiebesluit"       Financieel Overzicht"   Voorwaarden Bijlage"
  │ download-document    │ download-document    │ download-document
  │ contentPV: doc1      │ contentPV: doc2      │ contentPV: doc3
  ├──────────────────────┴──────────────────────┘
  ▼
Parallel Gateway (join) — waits for all 3
  ▼
Service Task: "Archiveer Subsidiebesluit"
  │  Action: documenten-api → store-temp-document
  │  (stores pv:doc1 as Enkelvoudig Informatieobject)
  ▼
Service Task: "Link Subsidiebesluit aan Zaak"
  │  Action: zaken-api → link-document-to-zaak
  ▼
Service Task: "Archiveer Financieel Overzicht"
  │  Action: documenten-api → store-temp-document
  ▼
Service Task: "Link Financieel Overzicht aan Zaak"
  │  Action: zaken-api → link-document-to-zaak
  ▼
Service Task: "Archiveer Voorwaarden Bijlage"
  │  Action: documenten-api → store-temp-document
  ▼
Service Task: "Link Voorwaarden Bijlage aan Zaak"
  │  Action: zaken-api → link-document-to-zaak
  ▼
End Event
```

### Infrastructure requirements

This use case requires additional Valtimo plugins and an OpenZaak instance.

**Backend dependencies** (add to `libs.versions.toml` and `test-app/backend/build.gradle.kts`):

```toml
# libs.versions.toml
valtimo-documenten-api = { module = "com.ritense.valtimo:documenten-api" }
valtimo-catalogi-api   = { module = "com.ritense.valtimo:catalogi-api" }
valtimo-open-zaak      = { module = "com.ritense.valtimo:open-zaak" }
```

```kotlin
// build.gradle.kts
implementation(libs.valtimo.documenten.api)
implementation(libs.valtimo.catalogi.api)
implementation(libs.valtimo.open.zaak)
```

**Frontend plugin registrations** (add to `app.module.ts`):

```typescript
import {
  documentenApiPluginSpecification,
  zakenApiPluginSpecification,
  openZaakPluginSpecification,
  catalogiApiPluginSpecification,
} from '@valtimo/plugin';

// In PLUGINS_TOKEN:
{
  provide: PLUGINS_TOKEN,
  useValue: [
    objectenApiPluginSpecification,
    objecttypenApiPluginSpecification,
    objectTokenAuthenticationPluginSpecification,
    epistolaPluginSpecification,
    openZaakPluginSpecification,        // new
    documentenApiPluginSpecification,   // new
    zakenApiPluginSpecification,        // new
    catalogiApiPluginSpecification,     // new
  ]
}
```

**Docker services**: OpenZaak instance with its dependencies (PostgreSQL, Redis, OpenNotificaties). Either add to the existing `docker/docker-compose.yml` or use the GZAC docker-compose with `--profile zgw`.

**Plugin configurations** (add to `app.pluginconfig.json`):
- OpenZaak: client ID + secret for authentication
- Documenten API: base URL + informatieobjecttype URL
- Zaken API: base URL + zaaktype URL
- Catalogi API: base URL + catalogus URL

### What this demonstrates

| Capability | How it's shown |
|------------|---------------|
| Parallel generation | 3 documents generated simultaneously via parallel gateway |
| Parallel join | All 3 must complete before archival begins |
| Documenten API archival | Each PDF stored as Enkelvoudig Informatieobject |
| Zaak linking | Each document linked to the zaak as ZaakInformatieobject |
| Multi-plugin orchestration | Epistola + OpenZaak + Zaken API in one process |
| Documents tab | Archived documents visible via zaak-informatieobject link |
| Complete lifecycle | generate → wait → download → archive → link |

---

## Implementation Order

These use cases should be implemented incrementally, each building on patterns established by the previous:

| Phase | Use case | Prerequisites |
|-------|----------|---------------|
| 0 | (infrastructure) | Frontend config components for `check-job-status` and `download-document` actions |
| 1 | Vergunningsaanvraag | Establishes: case definition, BPMN with message catch, data mapping, download + user task |
| 2 | Bezwaarprocedure | Adds: multi-template, conditional gateway, documents tab storage |
| 3 | Massale correspondentie | Adds: multi-instance subprocess, error counters, bulk poller load |
| 4 | Subsidie zaakdossier | Adds: parallel gateway, OpenZaak infrastructure, multi-plugin orchestration |

## Test-App File Structure

Each use case creates a separate case definition under `test-app/backend/src/main/resources/config/case/`:

```
config/case/
  permit/1.0.0/
    permit.case-definition.json
    permit.schema.document-definition.json
    permit.case-tab.json
    bpmn/permit-confirmation.bpmn
    form/permit-start.form.json
    form/review-document.form.json
    process-document-link/permit.process-document-link.json
    process-link/permit-confirmation.process-link.json
  objection/1.0.0/
    objection.case-definition.json
    objection.schema.document-definition.json
    objection.case-tab.json
    bpmn/objection-handling.bpmn
    form/objection-start.form.json
    form/assess-objection.form.json
    process-document-link/objection.process-document-link.json
    process-link/objection-handling.process-link.json
  bulk-letters/1.0.0/
    bulk-letters.case-definition.json
    bulk-letters.schema.document-definition.json
    bulk-letters.case-tab.json
    bpmn/bulk-tax-letters.bpmn
    form/bulk-start.form.json
    form/bulk-result.form.json
    process-document-link/bulk-letters.process-document-link.json
    process-link/bulk-tax-letters.process-link.json
  subsidy/1.0.0/
    subsidy.case-definition.json
    subsidy.schema.document-definition.json
    subsidy.case-tab.json
    bpmn/subsidy-decision-package.bpmn
    form/subsidy-start.form.json
    process-document-link/subsidy.process-document-link.json
    process-link/subsidy-decision-package.process-link.json
```
