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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merging of resolved template data. One implementation so a preview, a retry and a composed
 * letter all layer values the same way — a difference here would show up as a preview that does
 * not match the document that follows.
 */
public final class MapMerge {

    private MapMerge() {
    }

    /**
     * Deep-merge {@code overlay} onto {@code base}. Nested maps are merged key by key; every other
     * value (including a list) is replaced wholesale, because a partial list merge has no
     * meaningful semantics for template data.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> result = new LinkedHashMap<>(base == null ? Map.of() : base);
        if (overlay == null) {
            return result;
        }
        for (var entry : overlay.entrySet()) {
            Object baseValue = result.get(entry.getKey());
            Object overlayValue = entry.getValue();
            if (baseValue instanceof Map && overlayValue instanceof Map) {
                result.put(entry.getKey(), deepMerge(
                        (Map<String, Object>) baseValue,
                        (Map<String, Object>) overlayValue));
            } else {
                result.put(entry.getKey(), overlayValue);
            }
        }
        return result;
    }
}
