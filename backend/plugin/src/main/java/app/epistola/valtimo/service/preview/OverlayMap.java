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
package app.epistola.valtimo.service.preview;

import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A Map that layers an overlay on top of a base map.
 * Lookups check the overlay first; if the key is not present there,
 * the base map is consulted. When both overlay and base contain a Map
 * for the same key, a recursive OverlayMap is returned so that nested
 * overrides work naturally with JSONata path navigation.
 * <p>
 * This enables input-level overrides for document previews: the overlay
 * contains user-supplied values while the base is a lazy-loading map
 * that only loads from the database when a non-overridden path is accessed.
 */
public class OverlayMap extends AbstractMap<String, Object> {

    private final Map<String, Object> overlay;
    private final Map<String, Object> base;

    public OverlayMap(Map<String, Object> overlay, Map<String, Object> base) {
        this.overlay = overlay != null ? overlay : Map.of();
        this.base = base != null ? base : Map.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object get(Object key) {
        if (!overlay.containsKey(key)) {
            return base.get(key);
        }
        Object overlayValue = overlay.get(key);
        if (overlayValue instanceof Map) {
            Object baseValue = base.get(key);
            if (baseValue instanceof Map) {
                return new OverlayMap((Map<String, Object>) overlayValue, (Map<String, Object>) baseValue);
            }
        }
        return overlayValue;
    }

    @Override
    public boolean containsKey(Object key) {
        return overlay.containsKey(key) || base.containsKey(key);
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        Set<Entry<String, Object>> entries = new LinkedHashSet<>();
        for (Entry<String, Object> entry : base.entrySet()) {
            if (overlay.containsKey(entry.getKey())) {
                entries.add(new SimpleImmutableEntry<>(entry.getKey(), get(entry.getKey())));
            } else {
                entries.add(entry);
            }
        }
        for (Entry<String, Object> entry : overlay.entrySet()) {
            if (!base.containsKey(entry.getKey())) {
                entries.add(entry);
            }
        }
        return entries;
    }

    @Override
    public int size() {
        return entrySet().size();
    }

    @Override
    public boolean isEmpty() {
        return overlay.isEmpty() && base.isEmpty();
    }
}
