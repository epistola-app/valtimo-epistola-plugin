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
package app.epistola.valtimo.domain;

import app.epistola.valtimo.composer.ComposerSchema;
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
 * <p>Only what it takes to render: a template, where it lives, and the data. A letter composer
 * writes more than this onto the same variable — what a person typed, and where those values also
 * belong in the case — and {@link app.epistola.valtimo.composer.ComposedLetter} is that richer
 * reading of it. Keeping them apart is what lets this type mean what its name says: a process that
 * prepares a document has no composer, and nothing here suggests it should.
 *
 * <p>It carries the schema version the composer wrote it with, because it outlives that composer:
 * a process instance can sit in the database for months, and the plugin that generates the letter
 * may not be the one that composed it. See {@link ComposerSchema} for which way that is tolerated.
 *
 * @param schemaVersion The version it was written with; 1 for anything predating the field
 * @param catalogId  The catalog the chosen template lives in
 * @param templateId The chosen template
 * @param data       Everything the letter is rendered with
 */
public record DynamicDocument(
        int schemaVersion,
        String catalogId,
        String templateId,
        Map<String, Object> data
) {

    /**
     * The input for the <b>Generate Dynamic Document</b> action, as the variable must hold it.
     *
     * <p>The generate action renders a document object; a letter composer is one way to produce
     * one, and a process that sets the variable itself is another — an integration, an earlier
     * service task, an API caller. Nothing in the action consults a composer, so this is the whole
     * contract, and it is public so that it can be built without copying a map literal out of the
     * documentation and hoping.
     *
     * <p>{@code schemaVersion} is written for the reader on the other side, which may belong to a
     * newer plugin than the writer: see {@link ComposerSchema}, which versions this shape because a
     * letter composer was the first thing to write one.
     *
     * @param catalogId  the catalog the template lives in
     * @param templateId the template to render
     * @param data       everything the document is rendered with
     * @return the value to set on the process variable the action reads
     */
    public static Map<String, Object> of(
            String catalogId, String templateId, Map<String, Object> data) {
        if (catalogId == null || catalogId.isBlank() || templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException(
                    "A document to render names a catalog and a template");
        }
        Map<String, Object> prepared = new java.util.LinkedHashMap<>();
        prepared.put("schemaVersion", ComposerSchema.CURRENT);
        prepared.put("catalogId", catalogId);
        prepared.put("templateId", templateId);
        prepared.put("data", data == null ? Map.of() : data);
        return java.util.Collections.unmodifiableMap(prepared);
    }

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
    public static DynamicDocument from(Object raw, String variableName, ObjectMapper objectMapper) {
        List<DynamicDocument> letters = allFrom(raw, variableName, objectMapper);
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
    public static List<DynamicDocument> allFrom(Object raw, String variableName, ObjectMapper objectMapper) {
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
    private static DynamicDocument one(Object entry, int schemaVersion, String variableName) {
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

        return new DynamicDocument(
                schemaVersion,
                catalogId,
                templateId,
                copyOf(value.get("data")));
    }

    /**
     * An unmodifiable snapshot that tolerates a null value.
     *
     * <p>Two things this fixes, both of which bit. {@code Map.copyOf} rejects a null value, so a
     * letter with a field someone cleared could not be read at all — and it failed inside the
     * generate action, where the only symptom is an activity throwing {@code NullPointerException}
     * with no message. And the map handed in belongs to the process variable: keeping a reference
     * to it would let a later activity change what this letter says it sent, after it was sent.
     *
     * <p>A null value is kept rather than dropped: "this field was cleared" and "this field was
     * never offered" are different things, and a write-back rule reading the first should see
     * nothing rather than see a stale value.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyOf(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        ((Map<String, Object>) map).forEach(snapshot::put);
        return java.util.Collections.unmodifiableMap(snapshot);
    }

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
