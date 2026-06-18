/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
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
