# Data Mapping

This document explains how data flows from Valtimo case/process data to an Epistola template during document generation.

## Overview

The data mapping pipeline has four stages:

1. **Schema parsing** — Epistola template JSON Schema is parsed into a `TemplateField` tree
2. **User mapping** — the frontend tree form lets users map each template field to a data source
3. **Validation** — `TemplateMappingValidator` checks all required fields are mapped
4. **Resolution** — `EpistolaPlugin` resolves value expressions and sends concrete data to Epistola

## 1. Template Field Schema

When a template is selected, the backend fetches its JSON Schema from Epistola and parses it into a tree of `TemplateField` records (`EpistolaServiceImpl.extractFieldsFromSchema()`).

Each field has a `fieldType`:

| FieldType | Description                                 | Example                        |
| --------- | ------------------------------------------- | ------------------------------ |
| `SCALAR`  | Primitive value (string, number, boolean)   | `customerName`, `invoice.date` |
| `OBJECT`  | Nested group of fields                      | `invoice`, `customer.address`  |
| `ARRAY`   | Collection of items (objects or primitives) | `lineItems`                    |

### Example schema

```json
{
  "type": "object",
  "required": ["customerName", "invoice"],
  "properties": {
    "customerName": { "type": "string" },
    "invoice": {
      "type": "object",
      "required": ["date"],
      "properties": {
        "date": { "type": "string" },
        "total": { "type": "number" },
        "lineItems": {
          "type": "array",
          "items": {
            "type": "object",
            "required": ["product", "price"],
            "properties": {
              "product": { "type": "string" },
              "price": { "type": "number" },
              "quantity": { "type": "number" }
            }
          }
        }
      }
    }
  }
}
```

Parsed into:

```
customerName (string, required)          SCALAR
invoice (object, required)               OBJECT
  ├── date (string, required)              SCALAR
  ├── total (number)                       SCALAR
  └── lineItems (array)                    ARRAY
        ├── product (string, required)       SCALAR (child)
        ├── price (number, required)         SCALAR (child)
        └── quantity (number)                SCALAR (child)
```

Path notation uses dots for nesting (`invoice.date`) and brackets for array items (`lineItems[].product`).

## 2. Mapping Format

The data mapping is a nested object that mirrors the template schema. Each leaf value is either a **value expression** (resolved at runtime) or a **literal**.

### Value expression prefixes

| Prefix      | Source                | Example                      |
| ----------- | --------------------- | ---------------------------- |
| `doc:`      | Document (case) field | `doc:customer.name`          |
| `case:`     | Case field            | `case:order.total`           |
| `pv:`       | Process variable      | `pv:invoiceDate`             |
| `template:` | Template expression   | `template:{{amount * 1.21}}` |

Strings without a recognised prefix are passed through as literal values.

### Scalar fields

Map directly to a value expression:

```json
{
  "customerName": "doc:customer.fullName"
}
```

### Object fields

Nest child mappings inside an object:

```json
{
  "invoice": {
    "date": "pv:invoiceDate",
    "total": "doc:order.totalAmount"
  }
}
```

### Array fields — direct mode

When the source collection already has the same field names as the template expects, map the array to a single collection expression:

```json
{
  "lineItems": "doc:order.items"
}
```

The resolved list is sent to Epistola as-is.

### Array fields — per-item field mapping

When source item field names differ from what the template expects, use the `_source` format to rename fields per item:

```json
{
  "lineItems": {
    "_source": "doc:order.items",
    "product": "productName",
    "price": "unitPrice"
  }
}
```

- `_source` — the collection expression (resolved via ValueResolverService)
- All other entries — `templateFieldName: sourceFieldName` pairs

Each item in the resolved source list is transformed: only the mapped fields are extracted and renamed. Unmapped source fields are dropped.

### Complete example

```json
{
  "customerName": "doc:customer.fullName",
  "invoice": {
    "date": "pv:invoiceDate",
    "total": "doc:order.totalAmount",
    "lineItems": {
      "_source": "doc:order.items",
      "product": "productName",
      "price": "unitPrice"
    }
  }
}
```

## 3. Validation

`TemplateMappingValidator.findMissingRequiredFields()` walks the template field tree and the mapping tree in parallel, checking that every required field has a non-empty value.

Rules per field type:

| FieldType        | Valid when                                                                          |
| ---------------- | ----------------------------------------------------------------------------------- |
| SCALAR           | Non-empty string value present                                                      |
| OBJECT           | All required children valid (recursive)                                             |
| ARRAY (direct)   | Non-empty string value present                                                      |
| ARRAY (\_source) | `_source` is non-empty **and** all required children have non-empty mapping strings |

Returns a list of missing field paths (e.g. `["customerName", "invoice.date", "lineItems[].price"]`).

The frontend uses the same logic (in `DataMappingTreeComponent` and `FieldTreeComponent`) to show completeness badges and block saving until all required fields are mapped.

## 4. Resolution

`EpistolaPlugin.resolveNestedMapping()` runs a two-pass algorithm:

### Pass 1: Collect

Recursively walks the mapping tree and collects all resolvable value expressions into a flat list for batch resolution.

For `_source` array mappings, only the `_source` value is collected — the other entries are plain field name strings, not expressions.

```
Input mapping:
  customerName → "doc:customer.fullName"        ← collected
  invoice.date → "pv:invoiceDate"               ← collected
  invoice.lineItems._source → "doc:order.items" ← collected
  invoice.lineItems.product → "productName"     ← NOT collected (plain field name)
  invoice.lineItems.price → "unitPrice"         ← NOT collected (plain field name)

Collected: ["doc:customer.fullName", "pv:invoiceDate", "doc:order.items"]
```

### Pass 2: Apply

Batch-resolves all expressions via `ValueResolverService.resolveValues()` (single call), then rebuilds the tree:

- **String expressions** → replaced with resolved value
- **Nested objects** → recursively applied
- **`_source` array mappings** → source expression resolved to a `List<Map>`, then `DataMappingResolver.mapArrayItems()` renames fields per the mapping

### End-to-end example

**Batch resolution returns:**

```
"doc:customer.fullName" → "Acme Corporation"
"pv:invoiceDate"        → "2024-01-15"
"doc:order.items"       → [
    {"productName": "Services", "unitPrice": 1500, "hours": 10},
    {"productName": "License",  "unitPrice": 299,  "qty": 1}
  ]
```

**Final resolved data sent to Epistola:**

```json
{
  "customerName": "Acme Corporation",
  "invoice": {
    "date": "2024-01-15",
    "lineItems": [
      { "product": "Services", "price": 1500 },
      { "product": "License", "price": 299 }
    ]
  }
}
```

Note how `mapArrayItems()` extracted only the `product` and `price` fields from each source item, renaming `productName` → `product` and `unitPrice` → `price`. The `hours` and `qty` fields were dropped because they weren't in the mapping.

## 5. Frontend Input Modes

Each SCALAR and ARRAY field has a 3-mode input selector:

| Mode       | Button | Input control                                       | Emitted value                      |
| ---------- | ------ | --------------------------------------------------- | ---------------------------------- |
| Browse     | `⊞`    | ValuePathSelector (doc:/case: dropdown with search) | `doc:path.to.field`                |
| PV         | `pv`   | Dropdown of discovered process variables            | `pv:variableName`                  |
| Expression | `fx`   | Free text input                                     | Any string (literal or expression) |

The active mode is auto-detected from prefilled values:

- Starts with `doc:` or `case:` → Browse
- Starts with `pv:` → PV
- Anything else → Expression

## Key Files

| File                                            | Role                                                        |
| ----------------------------------------------- | ----------------------------------------------------------- |
| `EpistolaServiceImpl.extractFieldsFromSchema()` | Parse JSON Schema → TemplateField tree                      |
| `TemplateMappingValidator`                      | Validate required fields are mapped                         |
| `EpistolaPlugin.resolveNestedMapping()`         | Two-pass value resolution                                   |
| `DataMappingResolver.mapArrayItems()`           | Per-item field name transformation                          |
| `DataMappingResolver.toNestedStructure()`       | Dot-notation → nested conversion (legacy, currently unused) |
| `FieldTreeComponent`                            | Recursive tree form per field                               |
| `DataMappingTreeComponent`                      | Top-level wrapper managing the full mapping                 |
