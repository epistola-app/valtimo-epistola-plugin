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

import app.epistola.valtimo.mapping.LazyDocumentMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class OverlayMapTest {

    @Nested
    class BasicOverlay {

        @Test
        void overlayValueTakesPrecedence() {
            var base = Map.<String, Object>of("key", "base-value");
            var overlay = Map.<String, Object>of("key", "overlay-value");

            var map = new OverlayMap(overlay, base);

            assertThat(map.get("key")).isEqualTo("overlay-value");
        }

        @Test
        void nonOverriddenKeyFallsThroughToBase() {
            var base = Map.<String, Object>of("a", "from-base", "b", "also-base");
            var overlay = Map.<String, Object>of("a", "overridden");

            var map = new OverlayMap(overlay, base);

            assertThat(map.get("a")).isEqualTo("overridden");
            assertThat(map.get("b")).isEqualTo("also-base");
        }

        @Test
        void missingKeyReturnsNull() {
            var base = Map.<String, Object>of("a", "value");
            var overlay = Map.<String, Object>of();

            var map = new OverlayMap(overlay, base);

            assertThat(map.get("nonexistent")).isNull();
            assertThat(map.containsKey("nonexistent")).isFalse();
        }

        @Test
        void overlayCanAddNewKeys() {
            var base = Map.<String, Object>of("existing", "value");
            var overlay = Map.<String, Object>of("newKey", "new-value");

            var map = new OverlayMap(overlay, base);

            assertThat(map.get("existing")).isEqualTo("value");
            assertThat(map.get("newKey")).isEqualTo("new-value");
            assertThat(map.containsKey("newKey")).isTrue();
        }

        @Test
        void overlayCanSetNullValue() {
            var base = Map.<String, Object>of("key", "base-value");
            var overlay = new HashMap<String, Object>();
            overlay.put("key", null);

            var map = new OverlayMap(overlay, base);

            assertThat(map.get("key")).isNull();
            assertThat(map.containsKey("key")).isTrue();
        }
    }

    @Nested
    class RecursiveOverlay {

        @Test
        void nestedMapsAreRecursivelyOverlaid() {
            var base = Map.<String, Object>of(
                    "beslissing", Map.of("tekst", "original", "datum", "2024-01-01")
            );
            var overlay = Map.<String, Object>of(
                    "beslissing", Map.of("tekst", "overridden")
            );

            var map = new OverlayMap(overlay, base);

            @SuppressWarnings("unchecked")
            var beslissing = (Map<String, Object>) map.get("beslissing");
            assertThat(beslissing.get("tekst")).isEqualTo("overridden");
            assertThat(beslissing.get("datum")).isEqualTo("2024-01-01");
        }

        @Test
        void deeplyNestedOverlay() {
            var base = Map.<String, Object>of(
                    "level1", Map.of("level2", Map.of("deep", "base-deep", "other", "base-other"))
            );
            var overlay = Map.<String, Object>of(
                    "level1", Map.of("level2", Map.of("deep", "overlay-deep"))
            );

            var map = new OverlayMap(overlay, base);

            @SuppressWarnings("unchecked")
            var level2 = (Map<String, Object>) ((Map<String, Object>) map.get("level1")).get("level2");
            assertThat(level2.get("deep")).isEqualTo("overlay-deep");
            assertThat(level2.get("other")).isEqualTo("base-other");
        }

        @Test
        void overlayScalarReplacesBaseMap() {
            var base = Map.<String, Object>of("field", Map.of("nested", "value"));
            var overlay = Map.<String, Object>of("field", "scalar-override");

            var map = new OverlayMap(overlay, base);

            assertThat(map.get("field")).isEqualTo("scalar-override");
        }

        @Test
        void overlayMapReplacesBaseScalar() {
            var base = Map.<String, Object>of("field", "scalar");
            var overlay = Map.<String, Object>of("field", Map.of("nested", "value"));

            var map = new OverlayMap(overlay, base);

            assertThat(map.get("field")).isEqualTo(Map.of("nested", "value"));
        }
    }

    @Nested
    class LazyBaseAccess {

        @Test
        void baseIsNotAccessedWhenOverlayHasAllKeys() {
            var accessed = new AtomicBoolean(false);
            var base = new LazyDocumentMap(() -> {
                accessed.set(true);
                return Map.of("key", "base-value");
            });
            var overlay = Map.<String, Object>of("key", "overlay-value");

            var map = new OverlayMap(overlay, base);
            Object result = map.get("key");

            assertThat(result).isEqualTo("overlay-value");
            assertThat(accessed).isFalse();
        }

        @Test
        void baseIsLoadedWhenNonOverriddenKeyAccessed() {
            var accessed = new AtomicBoolean(false);
            var base = new LazyDocumentMap(() -> {
                accessed.set(true);
                return Map.of("fallback", "loaded-value");
            });
            var overlay = Map.<String, Object>of("other", "overlay-value");

            var map = new OverlayMap(overlay, base);
            Object result = map.get("fallback");

            assertThat(result).isEqualTo("loaded-value");
            assertThat(accessed).isTrue();
        }
    }

    @Nested
    class EntrySet {

        @Test
        void mergesEntriesFromBothMaps() {
            var base = Map.<String, Object>of("a", "base-a", "b", "base-b");
            var overlay = Map.<String, Object>of("b", "overlay-b", "c", "overlay-c");

            var map = new OverlayMap(overlay, base);

            assertThat(map).hasSize(3);
            assertThat(map).containsEntry("a", "base-a");
            assertThat(map).containsEntry("b", "overlay-b");
            assertThat(map).containsEntry("c", "overlay-c");
        }

        @Test
        void emptyOverlayReturnsBaseEntries() {
            var base = Map.<String, Object>of("a", "1", "b", "2");
            var overlay = Map.<String, Object>of();

            var map = new OverlayMap(overlay, base);

            assertThat(map).hasSize(2);
            assertThat(map).containsEntry("a", "1");
            assertThat(map).containsEntry("b", "2");
        }

        @Test
        void emptyBaseReturnsOverlayEntries() {
            var base = Map.<String, Object>of();
            var overlay = Map.<String, Object>of("x", "y");

            var map = new OverlayMap(overlay, base);

            assertThat(map).hasSize(1);
            assertThat(map).containsEntry("x", "y");
        }
    }
}
