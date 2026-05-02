package app.epistola.valtimo.mapping;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A Map that lazily resolves process variables on individual access.
 * Each get() call resolves the specific variable, correctly traversing
 * parent execution scopes (when backed by DelegateExecution.getVariable).
 * <p>
 * When a {@code bulkLoader} is supplied, enumeration operations
 * ({@link #entrySet()}, {@link #size()}, {@link #isEmpty()}) load all
 * variables on first access and cache the result. This enables JSONata
 * expressions like {@code $keys($pv)}, {@code $each($pv, ...)}, and
 * {@code $pv.*} to see actual variables. Without a bulk loader,
 * enumeration returns empty (legacy behavior).
 */
public class LazyProcessVariableMap extends AbstractMap<String, Object> {

    private final Function<String, Object> resolver;
    private final Supplier<Map<String, Object>> bulkLoader;
    private Map<String, Object> bulkCache;

    /**
     * @param resolver function that resolves a variable by name (e.g. execution::getVariable)
     */
    public LazyProcessVariableMap(Function<String, Object> resolver) {
        this(resolver, null);
    }

    /**
     * @param resolver   function that resolves a variable by name (e.g. execution::getVariable)
     * @param bulkLoader supplier that returns all variables; called at most once on first enumeration
     */
    public LazyProcessVariableMap(Function<String, Object> resolver, Supplier<Map<String, Object>> bulkLoader) {
        this.resolver = resolver;
        this.bulkLoader = bulkLoader;
    }

    @Override
    public Object get(Object key) {
        if (key instanceof String name) {
            return resolver.apply(name);
        }
        return null;
    }

    @Override
    public boolean containsKey(Object key) {
        if (bulkCache != null) {
            return bulkCache.containsKey(key);
        }
        return get(key) != null;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return bulk().entrySet();
    }

    @Override
    public int size() {
        return bulk().size();
    }

    @Override
    public boolean isEmpty() {
        if (bulkLoader == null) {
            // Assume non-empty so JSONata doesn't short-circuit per-key resolution
            return false;
        }
        return bulk().isEmpty();
    }

    private Map<String, Object> bulk() {
        if (bulkLoader == null) {
            return Map.of();
        }
        if (bulkCache == null) {
            Map<String, Object> loaded = bulkLoader.get();
            bulkCache = loaded != null ? loaded : Map.of();
        }
        return bulkCache;
    }
}
