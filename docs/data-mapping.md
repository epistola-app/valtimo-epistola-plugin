# Data Mapping

Epistola templates expect a JSON payload. In Valtimo, the `generate-document`
action builds that payload by evaluating a JSONata expression against case,
process, and case-management context.

## Overview

The current data mapping pipeline is:

1. **Template schema discovery** — the backend fetches the selected template's
   JSON Schema from Epistola and exposes the expected field tree to the frontend.
2. **JSONata authoring** — the frontend editor helps users build a JSONata
   expression that returns the template payload. Simple mode presents one guided
   expression editor per schema field; Advanced mode exposes the complete
   mapping.
3. **Save-time validation** — Simple-compatible mappings must provide every
   template field marked as required. Dynamic mappings whose outer structure is
   only representable in Advanced mode cannot be checked statically, so they
   require valid, nonblank JSONata. `POST /api/v1/plugin/epistola/validate-jsonata`
   parses the mapping and scalar expressions before the process link is saved.
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

## Guided Expression Editing

Each simple mapping field uses the same guided editor as the generate action's
filename, correlation ID, and variant-attribute fields. The visual mode is
intended for non-programmers:

- text typed directly into the field is always a string literal;
- typing `@` searches document, process, and case variables inline, while the
  `+` button opens the complete insert menu; selected variables become
  semantic chips;
- any unlisted name can still be inserted by typing it. For example,
  `@paymentReference` offers `$case.paymentReference`,
  `$pv.paymentReference`, `$doc.paymentReference`, and the independent JSONata
  variable `$paymentReference`; an explicitly scoped entry such as
  `@pv.paymentReference` is kept as `$pv.paymentReference`;
- numbers, booleans, and `null` are explicit typed chips rather than inferred
  from ambiguous input;
- simple concatenations combine text and chips while still producing JSONata.

For example, typing `invoice-`, inserting `$case.sequence`, and typing `.pdf`
stores this expression:

```jsonata
'invoice-' & $case.sequence & '.pdf'
```

Existing quote style and expression source are retained while the visual value
is untouched. Editing a visual expression serializes it to a canonical JSONata
form.

The inline mode button cycles through the views that can represent the current
expression without changing it. Ordinary fields cycle between Visual and
Advanced. A field supplied with resource options, such as variant or
environment, can additionally use Select. Unsupported or complex expressions
open in Advanced automatically and are edited in a growing textarea as
validated raw JSONata. The mode action is disabled when no other lossless view
is available:

```jsonata
$doc.language = "nl" ? "nederlands" : "engels"
```

The complete mapping can use Simple mode whenever its outer structure is a
statically keyed object. Individual values may still be arbitrarily complex;
their field editor opens in Advanced mode without reconstructing or rewriting
the value source. Mappings whose structure is itself dynamic, such as a
top-level `$merge(...)`, remain in the whole-mapping Advanced editor, which
uses Monaco for syntax highlighting and completion.

Simple-compatible mappings are saveable only after every schema-required field
has a value. Required nested paths are checked recursively; mapping an entire
object or array with one expression satisfies its required subtree. Optional
fields may remain empty. A new or historically blank mapping opens as `{}`;
that empty object is saveable only when the selected template has no required
fields.

## Scalar Expressions

In action configuration v1, filename, correlation ID, environment, variant, and
variant-attribute values are JSONata expressions evaluated with the same
context. Values that identify an Epistola resource must evaluate to a string;
filename and correlation ID must produce their documented scalar runtime types.
Variant and environment dropdown selections are serialized as JSONata string
literals. An exact string literal matching an available variant or environment
prefers Select and can cycle through Select, Visual, and Advanced. Simple
dynamic expressions and unmatched literals cycle between Visual and Advanced,
skipping Select. Complex JSONata remains in Advanced. All three are views over
the same expression; switching views never rewrites it. Output format is fixed
to the JSONata string `"PDF"`.

In process-link JSON, JSONata remains an escaped JSON string:

```json
{
  "filename": "'invoice-' & $case.sequence & '.pdf'"
}
```

The visual editor validates field syntax locally. Saving the process link also
validates all configured expressions through the backend. Missing variables and
runtime type errors remain runtime concerns.

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

See [Custom expression functions](custom-expression-functions.md) for registration,
typed arguments, automatically supplied evaluation context, external data access,
result schemas, and evaluation-scoped caching.

## Key Files

| File                                                                 | Role                                               |
| -------------------------------------------------------------------- | -------------------------------------------------- |
| `JsonataMappingService`                                              | Evaluates data mappings and scalar expressions.    |
| `DefaultExpressionContext`                                           | Provides `$doc`, `$pv`, and `$case`.               |
| `EpistolaToolingResource`                                            | Save-time JSONata syntax validation.               |
| `generate-document-configuration.component.ts`                       | Stores `dataMapping` as a JSONata string.          |
| `components/smart-expression-editor/`                                | Guided per-field JSONata authoring and validation. |
| `components/jsonata-editor/jsonata-editor.component.ts`              | Whole-mapping Advanced editor.                     |
| `service/preview/PreviewService` and `service/form/RetryFormService` | Apply preview/retry overrides before evaluation.   |
