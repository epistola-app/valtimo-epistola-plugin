# Data Mapping

Epistola templates expect a JSON payload. In Valtimo, the `generate-document`
action builds that payload by evaluating a JSONata expression against case,
process, and case-management context.

## Overview

The current data mapping pipeline is:

1. **Template schema discovery** — the backend fetches the selected template's
   JSON Schema from Epistola and exposes the expected field tree to the frontend.
2. **JSONata authoring** — the frontend editor helps users build a JSONata
   expression that returns the template payload.
3. **Save-time syntax validation** — `POST /api/v1/plugin/epistola/validate-jsonata`
   parses the data mapping and scalar expressions before the process link is
   saved.
4. **Runtime evaluation** — `JsonataMappingService` evaluates the expression and
   sends the resulting object to Epistola.

The process-link property is `dataMapping: string`. In process-link JSON files
the expression is stored as a JSON string, but the expression text itself is a
JSONata object constructor. Legacy object-shaped mappings are not the current
model.

## Evaluation Context

The JSONata expression receives these variables:

| Variable | Source                                                   |
| -------- | -------------------------------------------------------- |
| `$doc`   | The Valtimo document/case data, lazily resolved by path. |
| `$pv`    | Process variables from the current execution.            |
| `$case`  | Case metadata exposed by the plugin's context provider.  |

The expression must evaluate to an object matching the Epistola template schema.
This example matches the permit confirmation process link in the test app:

```jsonata
{
  "applicant": {
    "firstName": $doc.applicant.firstName,
    "lastName": $doc.applicant.lastName,
    "bsn": $doc.applicant.bsn,
    "address": {
      "street": $doc.applicant.address.street,
      "houseNumber": $doc.applicant.address.houseNumber,
      "postalCode": $doc.applicant.address.postalCode,
      "city": $doc.applicant.address.city
    }
  },
  "property": {
    "address": {
      "street": $doc.property.address.street,
      "houseNumber": $doc.property.address.houseNumber,
      "postalCode": $doc.property.address.postalCode,
      "city": $doc.property.address.city
    },
    "kadastraalNummer": $doc.property.kadastraalNummer
  },
  "activities": $doc.activities
}
```

## Template Schema

When a template is selected, the backend fetches its JSON Schema and parses it
into a `TemplateField` tree. The frontend uses that tree to show the expected
shape while the JSONata editor remains the source of truth for the stored
mapping.

Example schema:

```json
{
  "type": "object",
  "required": ["applicant", "property", "activities"],
  "properties": {
    "applicant": {
      "type": "object",
      "required": ["firstName", "lastName", "bsn", "address"],
      "properties": {
        "firstName": { "type": "string" },
        "lastName": { "type": "string" },
        "bsn": { "type": "string" },
        "address": {
          "type": "object",
          "properties": {
            "street": { "type": "string" },
            "houseNumber": { "type": "string" },
            "postalCode": { "type": "string" },
            "city": { "type": "string" }
          }
        }
      }
    },
    "property": {
      "type": "object",
      "properties": {
        "address": { "type": "object" },
        "kadastraalNummer": { "type": "string" }
      }
    },
    "activities": {
      "type": "array",
      "items": { "type": "object" }
    }
  }
}
```

A valid mapping for that schema returns:

```json
{
  "applicant": {
    "firstName": "Jan",
    "lastName": "de Vries",
    "bsn": "123456789",
    "address": {
      "street": "Kerkstraat",
      "houseNumber": "1",
      "postalCode": "1234AB",
      "city": "Amsterdam"
    }
  },
  "property": {
    "address": {
      "street": "Dorpsweg",
      "houseNumber": "42",
      "postalCode": "5678CD",
      "city": "Rotterdam"
    },
    "kadastraalNummer": "AMS01-A-1234"
  },
  "activities": [{ "type": "build", "description": "Uitbouw plaatsen" }]
}
```

## Scalar Expressions

Expression-mode `filename`, expression-mode `variantId`, and expression-mode
variant attribute values are evaluated with the same JSONata context, but they
must produce a scalar string value. Plain filename and variant fields are stored
directly, as shown by the test-app process links.

```jsonata
"invoice-" & $case.sequence & ".pdf"
```

In process-link JSON this scalar expression is escaped as a JSON string:

```json
{
  "filename": "\"invoice-\" & $case.sequence & \".pdf\""
}
```

```jsonata
$doc.language = "nl" ? "nederlands" : "engels"
```

Save-time validation parses these scalar expressions when their `fx` mode is
enabled. Missing variables and runtime type errors are still runtime concerns.

## Input Overrides

Preview and retry flows can overlay input values before JSONata evaluation. This
lets a Formio form feed unsaved values into `$doc` or `$pv` while keeping the
stored process-link mapping unchanged.

```json
{
  "doc": {
    "objector": {
      "motivation": "Updated motivation from the form"
    }
  },
  "pv": {
    "decision": "gegrond"
  }
}
```

At runtime, overridden paths win over the base document/process values.

## Custom Functions

`JsonataMappingService` registers functions from `ExpressionFunctionRegistry`.
These functions are available in mappings alongside native JSONata functions.
Failures from custom functions are surfaced as expression evaluation errors so
wrong template data is not silently generated.

## Key Files

| File                                                                 | Role                                             |
| -------------------------------------------------------------------- | ------------------------------------------------ |
| `JsonataMappingService`                                              | Evaluates data mappings and scalar expressions.  |
| `DefaultExpressionContext`                                           | Provides `$doc`, `$pv`, and `$case`.             |
| `EpistolaToolingResource`                                            | Save-time JSONata syntax validation.             |
| `generate-document-configuration.component.ts`                       | Stores `dataMapping` as a JSONata string.        |
| `components/jsonata-editor/jsonata-editor.component.ts`              | Frontend JSONata editor and local syntax checks. |
| `service/preview/PreviewService` and `service/form/RetryFormService` | Apply preview/retry overrides before evaluation. |
