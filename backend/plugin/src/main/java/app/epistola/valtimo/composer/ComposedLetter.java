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

import java.util.List;
import java.util.Map;

/**
 * What a letter composer put on a process variable, read back at generation time.
 *
 * <p>The composer resolves the data while the employee is looking at it, so generation renders
 * exactly what was previewed and needs no template, mapping or catalog of its own (ADR 0006). This
 * record is that contract, and the one place that knows its shape.
 *
 * <p>It carries the schema version the composer wrote it with, because it outlives that composer:
 * a process instance can sit in the database for months, and the plugin that generates the letter
 * may not be the one that composed it. See {@link ComposerSchema} for which way that is tolerated.
 *
 * @param schemaVersion The version it was written with; 1 for anything predating the field
 * @param catalogId  The catalog the chosen template lives in
 * @param templateId The chosen template
 * @param data       Everything the letter is rendered with
 * @param inputs     Only what the employee typed, kept apart from {@code data} so a write-back
 *                   rule can distinguish a value a person supplied from one the mapping produced
 * @param writeBack  Where values from this letter also belong in the case: a value-resolver key
 *                   (such as {@code doc:/aanvrager/telefoon}) to the value resolved when the letter
 *                   was composed. Empty when the composer declared none, which is the ordinary
 *                   case. The <em>values</em> are the browser's; the <em>destinations</em> are only
 *                   honoured where the composer's stored configuration also names them — see
 *                   {@link #writeBackLimitedTo}.
 */
public record ComposedLetter(
        int schemaVersion,
        String catalogId,
        String templateId,
        Map<String, Object> data,
        Map<String, Object> inputs,
        Map<String, Object> writeBack
) {

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
        List<ComposedLetter> letters = allFrom(raw, variableName, objectMapper);
        if (letters.size() > 1) {
            throw new IllegalArgumentException(
                    "The variable '" + variableName + "' holds " + letters.size() + " letters, and this "
                            + "plugin generates one. Offer a composer per letter, each with its own pv: "
                            + "key and its own generate task.");
        }
        return letters.get(0);
    }

    /**
     * Every letter on the variable.
     *
     * <p>Two shapes are accepted: a letter on its own, which is what a composer writes today, and
     * an envelope carrying a {@code letters} array. The array form is not produced by anything yet
     * — letting an employee choose *how many* letters go out is unbuilt — but it is what that will
     * look like, and reading it here is what keeps the shape open. {@link #from} refuses more than
     * one, so the unbuilt behaviour fails with a sentence rather than by generating the first
     * letter and dropping the rest.
     */
    @SuppressWarnings("unchecked")
    public static List<ComposedLetter> allFrom(Object raw, String variableName, ObjectMapper objectMapper) {
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

        // Before anything is read out of it: a letter from a later plugin may not mean what this
        // one would take it to mean, and generating the wrong letter is worse than not generating.
        int schemaVersion = ComposerSchema.readable(
                intOrNull(value.get(ComposerSchema.FIELD)),
                "The composed letter on '" + variableName + "'");

        if (value.get("letters") instanceof List<?> letters) {
            if (letters.isEmpty()) {
                throw new IllegalArgumentException(
                        "The variable '" + variableName + "' names no letters");
            }
            return letters.stream()
                    .map(entry -> one(entry, schemaVersion, variableName))
                    .toList();
        }
        return List.of(one(value, schemaVersion, variableName));
    }

    @SuppressWarnings("unchecked")
    private static ComposedLetter one(Object entry, int schemaVersion, String variableName) {
        if (!(entry instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "The variable '" + variableName + "' holds something that is not a letter");
        }
        Map<String, Object> value = (Map<String, Object>) map;

        String templateId = text(value.get("templateId"));
        String catalogId = text(value.get("catalogId"));
        if (templateId == null || catalogId == null) {
            throw new IllegalArgumentException(
                    "The composed letter on '" + variableName + "' names no catalog and template");
        }

        Object data = value.get("data");
        Object inputs = value.get("inputs");
        Object writeBack = value.get("writeBack");
        return new ComposedLetter(
                schemaVersion,
                catalogId,
                templateId,
                data instanceof Map<?, ?> dataMap ? (Map<String, Object>) dataMap : Map.of(),
                inputs instanceof Map<?, ?> inputsMap ? Map.copyOf((Map<String, Object>) inputsMap) : Map.of(),
                writeBack instanceof Map<?, ?> writeBackMap ? Map.copyOf((Map<String, Object>) writeBackMap) : Map.of());
    }

    /**
     * The write-back entries whose destination the composer's own configuration names, and no
     * others.
     *
     * <p>This is the one place the distinction matters. The letter's values are computed in the
     * browser — that is deliberate, and it is what makes what was previewed the thing that gets
     * generated — but it means the <em>keys</em> on this map arrived from the browser too. A
     * crafted submission could otherwise name any case path at all, and writing to an arbitrary
     * {@code doc:} path is a different matter from rendering a letter with odd data: one is a
     * document nobody asked for, the other is a silent edit to the case.
     *
     * <p>So the form definition stays the authority on <em>where</em> data may go, and the browser
     * decides only <em>what</em>. An entry whose destination is not in {@code allowed} is dropped
     * rather than refused: a letter that Epistola has already accepted must not fail here, and a
     * dropped destination is reported on the result variable by the caller.
     *
     * @param allowed the destinations the composer's stored {@code writeBack} map declares
     */
    public Map<String, Object> writeBackLimitedTo(java.util.Set<String> allowed) {
        if (writeBack.isEmpty() || allowed.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> permitted = new java.util.LinkedHashMap<>();
        writeBack.forEach((destination, value) -> {
            if (allowed.contains(destination)) {
                permitted.put(destination, value);
            }
        });
        return java.util.Collections.unmodifiableMap(permitted);
    }

    /** The destinations this letter asks for that {@code allowed} does not name. */
    public java.util.List<String> writeBackRefused(java.util.Set<String> allowed) {
        return writeBack.keySet().stream().filter(destination -> !allowed.contains(destination)).sorted().toList();
    }

    /** Operaton hands numbers back as Integer, Long or (from JSON) whatever Jackson chose. */
    private static Integer intOrNull(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Integer.valueOf(string.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "A composed letter declares a non-numeric " + ComposerSchema.FIELD + ": " + string, e);
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (!(value instanceof String string) || string.isBlank()) {
            return null;
        }
        return string;
    }
}
