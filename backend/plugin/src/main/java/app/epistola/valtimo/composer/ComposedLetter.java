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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * What a letter composer put on a process variable, read back at generation time.
 *
 * <p>The composer resolves the data while the employee is looking at it, so generation renders
 * exactly what was previewed and needs no template, mapping or catalog of its own (ADR 0006). This
 * record is that contract, and the one place that knows its shape.
 *
 * @param catalogId  The catalog the chosen template lives in
 * @param templateId The chosen template
 * @param data       Everything the letter is rendered with
 */
public record ComposedLetter(String catalogId, String templateId, Map<String, Object> data) {

    /**
     * Read a composed letter from a process variable.
     *
     * <p>Operaton hands back what the value resolver stored — a Map for an object variable, or the
     * raw JSON when a process wrote a string — so both are accepted. Anything else, or a letter
     * missing its template, is a configuration error worth failing loudly: silently generating
     * nothing would leave a process that looks like it sent a letter.
     *
     * @throws IllegalArgumentException when the variable does not hold a usable letter
     */
    @SuppressWarnings("unchecked")
    public static ComposedLetter from(Object raw, String variableName, ObjectMapper objectMapper) {
        Map<String, Object> value;
        if (raw instanceof Map<?, ?> map) {
            value = (Map<String, Object>) map;
        } else if (raw instanceof String json && !json.isBlank()) {
            try {
                value = objectMapper.readValue(json, Map.class);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Process variable '" + variableName + "' does not hold valid JSON for a composed letter", e);
            }
        } else if (raw == null) {
            throw new IllegalArgumentException(
                    "No composed letter on process variable '" + variableName
                            + "'. A letter composer writes it when the form is submitted.");
        } else {
            throw new IllegalArgumentException(
                    "Process variable '" + variableName + "' holds a "
                            + raw.getClass().getSimpleName() + ", not a composed letter");
        }

        String templateId = text(value.get("templateId"));
        String catalogId = text(value.get("catalogId"));
        if (templateId == null || catalogId == null) {
            throw new IllegalArgumentException(
                    "The composed letter on '" + variableName + "' names no catalog and template");
        }

        Object data = value.get("data");
        return new ComposedLetter(
                catalogId,
                templateId,
                data instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of());
    }

    private static String text(Object value) {
        if (!(value instanceof String string) || string.isBlank()) {
            return null;
        }
        return string;
    }
}
