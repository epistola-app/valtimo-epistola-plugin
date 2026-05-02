package app.epistola.valtimo.mapping;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LazyProcessVariableMapTest {

    @Test
    void resolverOnlyConstructorReturnsEmptyEnumeration() {
        var map = new LazyProcessVariableMap(name -> "v_" + name);

        assertThat(map.entrySet()).isEmpty();
        assertThat(map.size()).isZero();
        // isEmpty lies as false to keep JSONata from short-circuiting per-key access
        assertThat(map.isEmpty()).isFalse();

        // Per-key access still works
        assertThat(map.get("foo")).isEqualTo("v_foo");
    }

    @Test
    void bulkLoaderEnablesEnumeration() {
        var map = new LazyProcessVariableMap(
                name -> "ignored",
                () -> Map.of("a", 1, "b", 2));

        assertThat(map.size()).isEqualTo(2);
        assertThat(map.isEmpty()).isFalse();
        assertThat(map.keySet()).containsExactlyInAnyOrder("a", "b");
        assertThat(map.entrySet()).hasSize(2);
    }

    @Test
    void perKeyAccessUsesResolverEvenWhenBulkLoaderProvided() {
        // The resolver may have different semantics than the bulk loader
        // (e.g., bulk loader returns a snapshot, resolver hits a live source).
        var map = new LazyProcessVariableMap(
                name -> "from-resolver-" + name,
                () -> Map.of("a", "from-bulk"));

        assertThat(map.get("a")).isEqualTo("from-resolver-a");
    }

    @Test
    void bulkLoaderIsCalledLazilyAndCached() {
        AtomicInteger calls = new AtomicInteger();
        var map = new LazyProcessVariableMap(
                name -> null,
                () -> {
                    calls.incrementAndGet();
                    return Map.of("x", 1);
                });

        // Construction does not call the loader
        assertThat(calls.get()).isZero();

        // Per-key access does not call the loader
        map.get("x");
        assertThat(calls.get()).isZero();

        // First enumeration triggers the loader
        map.entrySet();
        assertThat(calls.get()).isEqualTo(1);

        // Repeated enumeration uses the cache
        map.size();
        map.keySet();
        map.entrySet();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void containsKeyUsesCacheAfterEnumeration() {
        AtomicInteger resolverCalls = new AtomicInteger();
        var map = new LazyProcessVariableMap(
                name -> {
                    resolverCalls.incrementAndGet();
                    return null; // simulate "variable not present"
                },
                () -> Map.of("present", 1));

        // Before enumeration, containsKey delegates to resolver
        map.containsKey("present");
        assertThat(resolverCalls.get()).isEqualTo(1);

        // After enumeration, containsKey uses the cache
        map.entrySet();
        assertThat(map.containsKey("present")).isTrue();
        assertThat(map.containsKey("absent")).isFalse();
        // No additional resolver calls
        assertThat(resolverCalls.get()).isEqualTo(1);
    }

    @Test
    void nullBulkLoaderResultIsTreatedAsEmpty() {
        var map = new LazyProcessVariableMap(name -> null, () -> null);

        assertThat(map.entrySet()).isEmpty();
        assertThat(map.size()).isZero();
        assertThat(map.isEmpty()).isTrue();
    }
}
