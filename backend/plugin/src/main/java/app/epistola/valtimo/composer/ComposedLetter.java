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
package app.epistola.valtimo.composer;

import app.epistola.valtimo.domain.DynamicDocument;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A letter a composer put on a process variable: the document, and what only a composer knows.
 *
 * <p>The same variable value read two ways. {@link DynamicDocument} is what it takes to render —
 * which the generate action and the retry form need, and which any process can write. This adds the
 * two things that exist only because a person filled a form: what they typed, and where the
 * composer says those values also belong in the case.
 *
 * <p>Two types rather than one with optional fields, because the optional fields were doing the
 * explaining: a document a process prepared has no inputs and no write-back, and a type called
 * {@code DynamicDocument} that carried them said otherwise every time someone read it.
 *
 * @param document  what is being rendered
 * @param inputs    only what the employee typed, kept apart from the document's data so a
 *                  write-back rule can tell a value a person supplied from one the mapping produced
 * @param writeBack where values from this letter also belong in the case: a value-resolver key
 *                  (such as {@code doc:/aanvrager/telefoon}) to the value resolved when the letter
 *                  was composed. Empty when the composer declared none, which is the ordinary case.
 *                  The <em>values</em> are the browser's; the <em>destinations</em> are only
 *                  honoured where the composer's stored configuration also names them — see
 *                  {@link #writeBackLimitedTo}.
 */
public record ComposedLetter(
        DynamicDocument document,
        Map<String, Object> inputs,
        Map<String, Object> writeBack
) {

    /**
     * Read a composed letter from what a process variable holds.
     *
     * <p>Anything {@link DynamicDocument#from} accepts is accepted here; a value that carries no
     * {@code inputs} or {@code writeBack} simply has none, which is what a document prepared by a
     * process looks like through this lens.
     */
    @SuppressWarnings("unchecked")
    public static ComposedLetter from(Object raw, String variableName, ObjectMapper objectMapper) {
        DynamicDocument document = DynamicDocument.from(raw, variableName, objectMapper);
        Map<String, Object> value = raw instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : objectMapper.convertValue(
                        objectMapper.convertValue(raw, Object.class), Map.class);
        return new ComposedLetter(document, copyOf(value.get("inputs")), copyOf(value.get("writeBack")));
    }

    /** The data the letter renders with, which is the document's. */
    public Map<String, Object> data() {
        return document.data();
    }

    /**
     * The write-back entries whose destination the composer's own configuration names, and no
     * others.
     *
     * <p>This is the one place the distinction matters. The letter's values are computed in the
     * browser — that is deliberate, and it is what makes what was previewed the thing that gets
     * sent — but a destination is where a value lands in the case, and that may only come from the
     * stored form.
     */
    public Map<String, Object> writeBackLimitedTo(Set<String> allowed) {
        if (writeBack.isEmpty() || allowed.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> permitted = new LinkedHashMap<>();
        writeBack.forEach((destination, value) -> {
            if (allowed.contains(destination)) {
                permitted.put(destination, value);
            }
        });
        return Collections.unmodifiableMap(permitted);
    }

    /** The destinations this letter named that its composer does not, worth saying out loud. */
    public List<String> writeBackRefused(Set<String> allowed) {
        return writeBack.keySet().stream()
                .filter(destination -> !allowed.contains(destination))
                .sorted()
                .toList();
    }

    /** An unmodifiable snapshot that tolerates a null value; see {@link DynamicDocument}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyOf(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        ((Map<String, Object>) map).forEach(snapshot::put);
        return Collections.unmodifiableMap(snapshot);
    }
}
