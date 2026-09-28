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

import app.epistola.valtimo.domain.TemplateField;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Works out what the letter composer still has to ask the employee.
 *
 * <p>The answer cannot be read from the data mapping: it may be opaque ({@code $doc.someObject}) or
 * computed, so which template field an expression feeds is not knowable from its text. It can be
 * read from the <i>outcome</i>: evaluate the mapping against the real case, and whatever the
 * template needs but the mapping did not produce is what the employee supplies.
 *
 * <p>Fields the mapping did fill are deliberately not offered. Those are case data, and correcting
 * them belongs in a case form rather than in one letter.
 *
 * <p>Some required fields cannot be asked for at all: an object or array the schema analyzer could
 * not decompose has no parts to generate inputs from — a recursive {@code $ref}, a {@code oneOf},
 * {@code patternProperties}, and whatever else it labels as needing "a complete-value mapping".
 * Those are reported separately rather than dropped. Dropping them is what used to happen, and it
 * read as success: the composer announced that the letter needed no further input, then Epistola
 * refused to render it for a field nobody was asked about.
 */
public final class MissingFieldSelector {

    private MissingFieldSelector() {
    }

    /**
     * What the composer still needs for this letter.
     *
     * @param askable     the fields to ask the employee for, in schema order, nested as the
     *                    template's data nests; empty when the mapping covered everything
     * @param unsupported required fields the mapping did not fill and the composer cannot generate
     *                    an input for; a letter with any of these cannot be composed
     */
    public record Selection(List<TemplateField> askable, List<TemplateField> unsupported) {
        public boolean complete() {
            return askable.isEmpty();
        }
    }

    /**
     * Filter a template's field tree down to the fields with no value in {@code resolvedData},
     * keeping the tree shape so the generated form still nests the way the template data does.
     *
     * @param fields       the template's fields
     * @param resolvedData the data the mapping produced
     * @param requiredOnly when true, only fields the schema marks required are asked for
     */
    public static Selection select(
            List<TemplateField> fields,
            Map<String, Object> resolvedData,
            boolean requiredOnly
    ) {
        List<TemplateField> unsupported = new ArrayList<>();
        return new Selection(selectMissing(fields, resolvedData, requiredOnly, unsupported), unsupported);
    }

    private static List<TemplateField> selectMissing(
            List<TemplateField> fields,
            Map<String, Object> resolvedData,
            boolean requiredOnly,
            List<TemplateField> unsupported
    ) {
        List<TemplateField> missing = new ArrayList<>();
        if (fields == null) {
            return missing;
        }

        for (TemplateField field : fields) {
            Object value = resolvedData != null ? resolvedData.get(field.name()) : null;

            // Checked for every field type, not just the structured ones: an external $ref infers
            // SCALAR, because the schema this plugin sees is just {"$ref": "…"} — no type, no
            // properties. Left to the branches below it would become a single-line text box for a
            // value that is not text at all.
            if (isEmpty(value) && cannotBeAsked(field)) {
                // Only worth refusing the letter over when it actually needs the value; an
                // optional one is simply never offered, as it never was.
                if (field.required()) {
                    unsupported.add(field);
                }
                continue;
            }

            switch (field.fieldType()) {
                case OBJECT -> {
                    // An absent optional object is left alone entirely: asking for the parts of a
                    // block the letter does not use would be noise.
                    if (value == null && requiredOnly && !field.required()) {
                        continue;
                    }
                    List<TemplateField> children =
                            selectMissing(field.children(), asMap(value), requiredOnly, unsupported);
                    if (!children.isEmpty()) {
                        missing.add(withChildren(field, children));
                    }
                }
                case ARRAY, SCALAR -> {
                    if (isEmpty(value) && (!requiredOnly || field.required())) {
                        missing.add(field);
                    }
                }
            }
        }
        return missing;
    }

    /**
     * Whether there is nothing here to build a usable input from.
     *
     * <p>Note what this deliberately does <i>not</i> ask: {@code complex()}. That flag belongs to
     * the mapping builder — "Simple mode must map this as one expression" — and an ordinary array
     * of objects carries it while decomposing perfectly well into a data grid. Rendering asks a
     * different question: are there parts to make inputs from?
     *
     * <ul>
     *   <li>A structure with no children decomposed to nothing, so there is nothing to render.</li>
     *   <li>A scalar is a real scalar unless the analyzer could not see one. An external
     *       {@code $ref} reaches this plugin as {@code {"$ref": "…"}} — no type, no properties —
     *       so it infers SCALAR and is only distinguishable by {@code complex()}. Without this it
     *       would become a single-line text box for a value that is not text. Same for a recursive
     *       reference, a {@code oneOf} of shapes and an empty schema.</li>
     * </ul>
     *
     * <p>Rich text carried as an external {@code $ref} lands in the second case. Inlined into the
     * contract instead, it is indistinguishable from any other object and is <i>not</i> caught
     * here; see docs/letter-composer.md.
     */
    private static boolean cannotBeAsked(TemplateField field) {
        if (field.fieldType() == TemplateField.FieldType.SCALAR) {
            return field.complex();
        }
        return field.children() == null || field.children().isEmpty();
    }

    /**
     * A value counts as missing when the mapping produced nothing usable: absent, null, an empty
     * string (what a JSONata path over an absent field commonly yields once serialized), or an
     * empty collection.
     */
    private static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String string) {
            return string.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static TemplateField withChildren(TemplateField field, List<TemplateField> children) {
        return new TemplateField(
                field.name(),
                field.path(),
                field.type(),
                field.fieldType(),
                field.required(),
                field.description(),
                children,
                field.complex(),
                field.complexityReason(),
                field.nullable(),
                field.hints()
        );
    }
}
