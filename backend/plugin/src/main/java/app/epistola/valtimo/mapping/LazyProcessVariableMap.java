package app.epistola.valtimo.mapping;

import java.util.AbstractMap;
import java.util.Set;
import java.util.function.Function;

/**
 * A Map that lazily resolves process variables on individual access.
 * Each get() call resolves the specific variable, correctly traversing
 * parent execution scopes (when backed by DelegateExecution.getVariable).
 */
public class LazyProcessVariableMap extends AbstractMap<String, Object> {

    private final Function<String, Object> resolver;

    /**
     * @param resolver function that resolves a variable by name (e.g. execution::getVariable)
     */
    public LazyProcessVariableMap(Function<String, Object> resolver) {
        this.resolver = resolver;
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
        return get(key) != null;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        // Cannot enumerate — only supports individual lookups
        return Set.of();
    }

    @Override
    public int size() {
        return 0; // Unknown size — lazy resolution only
    }

    @Override
    public boolean isEmpty() {
        return false; // Assume non-empty to not short-circuit JSONata
    }
}
