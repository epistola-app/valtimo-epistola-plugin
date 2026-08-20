# ADR 0003 — How custom expression functions opt into result caching

- **Status:** Accepted
- **Date:** 2026-08-20
- **Deciders:** Epistola plugin maintainers
- **Related:** `EpistolaExpressionFunction`, `ExpressionFunctionRegistry`, `JsonataMappingService`, `docs/data-collection-strategies.md`, issue #100

## Context and problem statement

Custom JSONata functions may call external systems and return structured data. A mapping can
reference several fields from the same invocation:

```jsonata
{
  "name": $brpPerson($doc.bsn).name,
  "street": $brpPerson($doc.bsn).address.street
}
```

Today every reference invokes the function again. That is correct for dynamic functions such as
random-number or clock functions, but wasteful and potentially inconsistent for an external data
lookup. The function author knows whether repeated equal invocations may share a result; the
mapping service must own the cache lifecycle so results cannot leak into another evaluation,
document generation, process instance, or thread.

This choice is part of the public expression-function SPI. It affects return types, overload
discovery, schema metadata, runtime behavior, and backward compatibility, so it should be explicit
rather than emerging from an implementation detail in the test application.

## Decision drivers

- **Function-owned policy.** A host-defined function decides whether its result is safe to reuse.
- **Evaluation isolation.** No cached case or process data may survive a single call to
  `JsonataMappingService.evaluate` or `evaluateScalar`.
- **Backward compatibility.** Existing functions must continue to run on every reference.
- **Honest return types.** An `execute` method returning `Person` or `Map` should retain that type
  for Java/Kotlin callers, reflection, overload metadata, and result-schema tooling.
- **Overload precision.** Different `execute` overloads on one function may have different cost or
  determinism.
- **Predictable keys.** Only calls to the same function overload with equal evaluated arguments
  may share a value.
- **Small safe surface.** Cross-evaluation, time-based, global, or distributed caching have
  invalidation and data-isolation concerns that this plugin should not own implicitly.

## Decision outcome

Add a runtime method annotation named `@CacheResultForEvaluation`, which a
function author can place on an individual `execute` overload:

```java
@ExpressionFunctionResultSchema("schemas/brp-person-result-v1.schema.json")
@CacheResultForEvaluation
public Person execute(ExpressionContext context, String bsn) {
    return brpClient.getPerson(bsn);
}
```

The annotation has one deliberately narrow meaning: cache the unmodified return value for the
remainder of the current JSONata evaluation. An unannotated overload remains uncached. This makes
the default behavior identical to the current implementation and lets overloads opt in
independently.

`JsonataMappingService` owns a fresh invocation cache for every public `evaluate` or
`evaluateScalar` call. A cache key consists of the registered function, matched `execute` method,
and immutable snapshots of evaluated JSON-like arguments. Maps, iterables, and arrays are copied
recursively; unknown object types use identity semantics so they can cause a safe cache miss but
never an unsafe cache hit. The first successful invocation stores its value; subsequent matching
invocations in that evaluation reuse it. `null` is a valid cached value. Exceptions are never
cached. A new mapper call always starts with an empty cache.

The cache returns the same object instance and does not defensively copy it. An annotated function
therefore promises that its returned value is stable for the evaluation and will not be mutated in
a way that makes reuse unsafe.

Only evaluation-scoped caching is introduced. Longer-lived caching remains the responsibility of
the host function and its service client, where authorization, TTL, eviction, observability, and
distributed consistency can be handled with application-specific knowledge.

## Alternatives considered

### Return a value-and-cache-strategy wrapper

For example, an `execute` method could return
`ExpressionFunctionResult<Person>(person, PER_EVALUATION)`. This allows the function to decide
dynamically after each invocation.

**Not preferred:** the wrapper replaces the business return type seen by Java/Kotlin callers and
reflection. Generic erasure makes the underlying type less reliable for overload metadata, while
schema-backed functions would declare a schema for a value whose reflected type is actually the
framework wrapper. Every function that wants caching must also couple its normal return path to an
execution-policy carrier, and the mapper still cannot know the policy until after paying for the
first call. Dynamic per-result policy has no demonstrated use case yet.

If conditional caching becomes necessary, introduce it later as a separate policy hook that does
not change `execute` return types.

### Put a cache policy method on `EpistolaExpressionFunction`

A default method such as `cachePolicy()` preserves return types and can remain backward compatible.

**Not preferred:** the policy applies to the whole bean, despite the SPI supporting multiple
`execute` overloads. It also separates the behavior from the method whose result is cached. An
annotation is more cohesive with the existing overload-specific `ExpressionFunctionResultSchema`.

### Always cache custom functions

**Rejected:** this silently changes existing behavior and breaks intentionally dynamic or
side-effecting functions.

### Require functions to implement their own evaluation cache

**Rejected:** functions do not own the mapper's evaluation lifecycle, so they would need fragile
context-keyed state or risk retaining data across process instances. It would duplicate keying and
concurrency logic in every host application.

### Ask mapping authors to bind a local JSONata variable

Mapping authors can already call a function once and reuse the value with a JSONata variable.

**Retained as an explicit option, but insufficient as the only mechanism:** it avoids duplicate
calls in carefully authored expressions, but caching safety is a property of the function and
should not depend on every mapping author noticing repeated invocations.

## Consequences

- Existing functions remain uncached and source compatible.
- Expensive deterministic overloads can eliminate duplicate calls without changing mappings or
  return types.
- Cache behavior is fixed for an overload rather than selected dynamically per returned value.
- Cache lifetime and failure behavior are deterministic and testable.
- Focused tests cover uncached defaults, overload-specific behavior, object and scalar evaluations,
  repeated equal calls, distinct arguments, separate evaluations, `null` values, and exceptions.
- Documentation should tell authors to opt in only for values stable within one evaluation and to
  use application-level caching for longer lifetimes.
