package app.epistola.valtimo.mapping;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A Map that lazily loads the full document content on first access.
 * If the JSONata expression never accesses $doc, the document is never loaded.
 */
public class LazyDocumentMap extends AbstractMap<String, Object> {

    private final Supplier<Map<String, Object>> loader;
    private Map<String, Object> delegate;

    public LazyDocumentMap(Supplier<Map<String, Object>> loader) {
        this.loader = loader;
    }

    @Override
    public Object get(Object key) {
        return ensureLoaded().get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return ensureLoaded().containsKey(key);
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return ensureLoaded().entrySet();
    }

    @Override
    public int size() {
        return ensureLoaded().size();
    }

    @Override
    public boolean isEmpty() {
        return ensureLoaded().isEmpty();
    }

    private Map<String, Object> ensureLoaded() {
        if (delegate == null) {
            delegate = loader.get();
            if (delegate == null) {
                delegate = Map.of();
            }
        }
        return delegate;
    }
}
