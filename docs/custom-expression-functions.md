# Custom expression functions

Host applications can expose Spring beans as functions in Epistola JSONata mappings. A function
can accept values from the mapping, inspect the current evaluation context, call application
services, and return either a scalar or structured data.

For example, a mapping can load a resident from an external system and reuse fields from the
result:

```jsonata
{
  "name": $resident($doc.bsn, false).name,
  "activities": $resident($doc.bsn, false).activities
}
```

## Register a function

Implement `EpistolaExpressionFunction` in a Spring bean. Every callable overload must be named
`execute`, take `ExpressionContext` as its first parameter, and declare mapping-supplied arguments
after the context.

```java
@Component
public class ResidentFunction implements EpistolaExpressionFunction {

    private final ResidentClient residentClient;

    public ResidentFunction(ResidentClient residentClient) {
        this.residentClient = residentClient;
    }

    @Override
    public String name() {
        return "resident";
    }

    @Override
    public String description() {
        return "Loads current resident data";
    }

    @CacheResultForEvaluation
    @ExpressionFunctionResultSchema("schemas/resident-result-v1.schema.json")
    public Resident execute(
            ExpressionContext context,
            String bsn,
            boolean includeInactive) {
        return residentClient.load(context.getDocumentId(), bsn, includeInactive);
    }
}
```

The function is a normal Spring bean. Constructor-inject repositories, HTTP clients, or domain
services instead of creating infrastructure clients inside `execute`.

Function names must be valid Java identifiers. The registry discovers public `execute` methods and
publishes their argument names and types as authoring metadata. Multiple overloads are supported:

```java
public Resident execute(ExpressionContext context, String bsn) {
    return execute(context, bsn, false);
}

public Resident execute(ExpressionContext context, String bsn, boolean includeInactive) {
    // ...
}
```

The mapping supplies only `bsn` and `includeInactive`; the plugin always inserts
`ExpressionContext` as the first argument. Runtime overload matching uses argument count, Java type
assignability, and primitive boxing. It does not parse strings or perform arbitrary numeric or DTO
conversions. Prefer parameter types that match the values produced by JSONata, and test every
published overload with representative expressions.

## Evaluation context

Every invocation receives the context for the current mapping evaluation:

| Method                  | Value                                                                        |
| ----------------------- | ---------------------------------------------------------------------------- |
| `getDocumentId()`       | Valtimo document instance ID, or `null` when unavailable.                    |
| `getDocumentData()`     | Resolved document data available through `$doc` and `$case`.                 |
| `getProcessVariables()` | Current process variables, or an empty map outside a process.                |
| `getResolvedMapping()`  | Mapping values resolved before custom JSONata expressions run.               |
| `getExecution()`        | Operaton `DelegateExecution`, or `null` outside an active process execution. |

Generation normally has process and document context. Preview, validation, and other REST-driven
evaluation paths may not have a `DelegateExecution` or document ID. Treat those values as optional
unless the function is intentionally limited to process execution, and return an actionable error
when required context is missing.

Prefer the stable document and process accessors over direct `DelegateExecution` use. This keeps a
function usable in generation, preview, and retry flows and reduces coupling to the process engine.

## Structured results and editor discovery

Annotate an overload with `ExpressionFunctionResultSchema` to expose a classpath JSON Schema for
its result:

```java
@ExpressionFunctionResultSchema("schemas/resident-result-v1.schema.json")
public Resident execute(ExpressionContext context, String bsn) {
    // ...
}
```

The schema is authoring metadata. The plugin loads and validates it without invoking the function,
and the expression editor uses it to offer paths such as `$resident($doc.bsn).address.street`.
Schema metadata does not validate runtime results automatically. Keep schema resources versioned
and add a contract test that validates representative function output against the published
schema.

See [Data collection strategies](data-collection-strategies.md#strategy-2-custom-jsonata-functions)
for the supported authoring-schema subset and versioning guidance.

## Evaluation-scoped caching

Functions execute every time by default. Add `CacheResultForEvaluation` to an overload only when
equal calls are safe to reuse during one JSONata evaluation:

```java
@CacheResultForEvaluation
public Resident execute(ExpressionContext context, String bsn) {
    return residentClient.load(bsn);
}
```

The cache:

- is created separately for each mapping or scalar evaluation;
- never crosses document generations, previews, retries, process instances, requests, or threads;
- keys entries by function overload and immutable snapshots of JSON-like arguments;
- caches successful results, including `null`, but never exceptions; and
- returns the same result object without making a defensive copy.

An annotated function therefore promises that its result is stable for the evaluation and will
not be mutated in a way that makes reuse unsafe. Longer-lived caching, including TTL, eviction,
authorization scoping, and distributed consistency, belongs in the injected application service.
See [ADR 0003](adr/0003-expression-function-result-caching.md) for the full decision.

## Errors, security, and proxies

Function failures become expression-evaluation errors and can fail generation. Make messages
actionable, but do not include access tokens, complete upstream payloads, or sensitive personal
data. Apply authentication and authorization in the injected service just as for any other
application entry point.

Class-based Spring proxies are supported. JDK dynamic proxies cannot expose implementation-only
`execute` overloads and are rejected at startup with guidance to enable class-based proxying.

## Testing checklist

For each custom function, test:

- every overload with representative JSONata argument types;
- behavior with missing process or document context;
- upstream failures and sanitized error messages;
- runtime output against every published result schema;
- repeated equal and different calls when caching is enabled;
- isolation between separate and concurrent evaluations; and
- generation or preview integration through `JsonataMappingService`, not only direct Java calls.
