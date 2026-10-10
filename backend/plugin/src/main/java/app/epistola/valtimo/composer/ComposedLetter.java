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
import java.util.Map;

/**
 * A letter a composer put on a process variable: the document, and what only a composer knows.
 *
 * <p>The same variable value read two ways. {@link DynamicDocument} is what it takes to render —
 * which the generate action and the retry form need, and which any process can write. This adds the
 * one thing that exists only because a person filled a form: what they typed.
 *
 * <p>Two types rather than one with an optional field, because the optional field was doing the
 * explaining: a document a process prepared has no inputs, and a type called
 * {@code DynamicDocument} that carried them said otherwise every time someone read it.
 *
 * <p>Write-back is deliberately <em>not</em> here. An earlier design had the browser compute the
 * values and send them, with the stored form allowed to veto the destinations; what shipped instead
 * resolves the rules server-side when the letter is generated, so no write-back value ever crosses
 * the wire and there is nothing to veto. What the employee approved still reaches the case, because
 * the rules read it from this letter's {@code data} and {@code inputs} — only {@code $doc} and
 * {@code $pv} see the case as it is at generation time.
 *
 * @param document what is being rendered
 * @param inputs   only what the employee typed, kept apart from the document's data so a
 *                 write-back rule can tell a value a person supplied from one the mapping produced
 */
public record ComposedLetter(
        DynamicDocument document,
        Map<String, Object> inputs
) {

    /**
     * Read a composed letter from what a process variable holds.
     *
     * <p>Anything {@link DynamicDocument#from} accepts is accepted here; a value that carries no
     * {@code inputs} simply has none, which is what a document prepared by a process looks like
     * through this lens.
     */
    @SuppressWarnings("unchecked")
    public static ComposedLetter from(Object raw, String variableName, ObjectMapper objectMapper) {
        DynamicDocument document = DynamicDocument.from(raw, variableName, objectMapper);
        Map<String, Object> value = raw instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : objectMapper.convertValue(
                        objectMapper.convertValue(raw, Object.class), Map.class);
        return new ComposedLetter(document, copyOf(value.get("inputs")));
    }

    /** The data the letter renders with, which is the document's. */
    public Map<String, Object> data() {
        return document.data();
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
