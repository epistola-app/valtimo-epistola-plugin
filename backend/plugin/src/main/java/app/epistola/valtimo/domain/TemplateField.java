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

import java.util.List;

/**
 * Represents a field in an Epistola template that can be mapped to data.
 * Supports nested structures through the children property.
 *
 * @param name        The leaf name of the field (e.g., "total")
 * @param path        The dot-notation path (e.g., "invoice.lineItems[].total")
 * @param type        The JSON Schema type (e.g., "string", "number", "object", "array")
 * @param fieldType   Whether this is a SCALAR, OBJECT, or ARRAY field
 * @param required    Whether this field is required for document generation
 * @param description Optional description of the field's purpose
 * @param children    Child fields for OBJECT and ARRAY-of-object types (empty list for SCALAR)
 * @param complex     Whether Simple mode must map this entire value with one expression
 * @param complexityReason Diagnostic explanation of why the field is mapped as a whole value
 * @param nullable    Whether the schema permits a null value
 * @param hints       Presentation hints read straight from the schema (title, format, allowed
 *                    values, default), used to generate input fields. Null when unknown.
 */
public record TemplateField(
        String name,
        String path,
        String type,
        FieldType fieldType,
        boolean required,
        String description,
        List<TemplateField> children,
        boolean complex,
        String complexityReason,
        boolean nullable,
        FieldHints hints
) {
    public TemplateField(
            String name,
            String path,
            String type,
            TemplateField.FieldType fieldType,
            boolean required,
            String description,
            List<TemplateField> children,
            boolean complex,
            String complexityReason,
            boolean nullable
    ) {
        this(name, path, type, fieldType, required, description, children, complex, complexityReason, nullable, null);
    }

    public TemplateField(
            String name,
            String path,
            String type,
            FieldType fieldType,
            boolean required,
            String description,
            List<TemplateField> children
    ) {
        this(name, path, type, fieldType, required, description, children, false, null, false, null);
    }

    /**
     * What the schema says about presenting a field. Kept separate from the mapping-oriented
     * components above: these carry no meaning for a data mapping, only for a generated input.
     *
     * @param title         The schema's {@code title}, preferred over a humanized field name
     * @param format        The schema's string {@code format} (e.g. {@code date}, {@code email})
     * @param allowedValues The schema's {@code enum} values, if it constrains the field to a set
     * @param defaultValue  The schema's {@code default}, used when the mapping produced no value
     * @param constraints   The schema's size and range keywords, or null when it states none
     * @param example       One valid value for the field, from the schema's {@code examples} or
     *                      {@code example}, as text; null when it offers none. Text rather than
     *                      {@code Object} because it is only ever shown to a person: the analyzer
     *                      accepts a string, number or boolean and renders it, and an example of
     *                      any other shape is not an example of a value. Unlike
     *                      {@code defaultValue}, which is written back into the submission as JSON
     *                      and so has to keep its type.
     * @param richText      Which Epistola rich-text schema the field references, or null when it
     *                      is not rich text. Its value is a ProseMirror document, never text,
     *                      whatever format the case keeps it in; see {@code RichTextConverter}.
     */
    public record FieldHints(
            String title,
            String format,
            List<Object> allowedValues,
            Object defaultValue,
            Constraints constraints,
            String example,
            RichTextKind richText
    ) {
        /** Hints for a field that is not rich text. */
        public FieldHints(
                String title,
                String format,
                List<Object> allowedValues,
                Object defaultValue,
                Constraints constraints,
                String example
        ) {
            this(title, format, allowedValues, defaultValue, constraints, example, null);
        }

        /** Hints from a schema that offers no example. */
        public FieldHints(
                String title,
                String format,
                List<Object> allowedValues,
                Object defaultValue,
                Constraints constraints
        ) {
            this(title, format, allowedValues, defaultValue, constraints, null, null);
        }

        /** Hints from a schema that states no constraints. */
        public FieldHints(String title, String format, List<Object> allowedValues, Object defaultValue) {
            this(title, format, allowedValues, defaultValue, null, null, null);
        }

        public boolean isEmpty() {
            return title == null && format == null
                    && (allowedValues == null || allowedValues.isEmpty())
                    && defaultValue == null
                    && (constraints == null || constraints.isEmpty())
                    && example == null
                    && richText == null;
        }
    }

    /**
     * What the schema says a value must satisfy, beyond being present and of the right type.
     *
     * <p>Carried so a generated input can refuse a value before it is sent, rather than leaving
     * every rule to Epistola and surfacing the complaint next to the letter instead of under the
     * field. Only the keywords a Formio validator understands are kept: {@code exclusiveMinimum},
     * {@code exclusiveMaximum} and {@code multipleOf} would each need hand-written validation
     * JavaScript, are rare, and are caught at render time anyway.
     *
     * @param minLength Minimum string length
     * @param maxLength Maximum string length
     * @param pattern   A regular expression the string must match, in the schema's own semantics
     * @param minimum   Smallest permitted number, inclusive
     * @param maximum   Largest permitted number, inclusive
     * @param minItems  Fewest permitted array items
     * @param maxItems  Most permitted array items
     */
    public record Constraints(
            Integer minLength,
            Integer maxLength,
            String pattern,
            java.math.BigDecimal minimum,
            java.math.BigDecimal maximum,
            Integer minItems,
            Integer maxItems
    ) {
        public boolean isEmpty() {
            return minLength == null && maxLength == null && pattern == null
                    && minimum == null && maximum == null
                    && minItems == null && maxItems == null;
        }
    }

    public enum FieldType {
        SCALAR,
        OBJECT,
        ARRAY
    }

    /**
     * The two shapes of Epistola rich text, each published as a JSON Schema that a template's data
     * contract references by its canonical URL. The URL is the marker: the contract keeps these
     * references un-inlined when it serves a template's schema, so a field is rich text exactly
     * when its {@code $ref} is one of them.
     */
    public enum RichTextKind {
        /** One paragraph of text and hard breaks, spliced into a sentence of the template. */
        INLINE("https://epistola.app/schemas/richtext-inline-v1.json"),
        /** Paragraphs and (nested) bullet and ordered lists. */
        BLOCK("https://epistola.app/schemas/richtext-block-v1.json");

        private final String schemaUrl;

        RichTextKind(String schemaUrl) {
            this.schemaUrl = schemaUrl;
        }

        public String schemaUrl() {
            return schemaUrl;
        }

        /** The kind a {@code $ref} names, or null when it names neither rich-text schema. */
        public static RichTextKind fromSchemaRef(String reference) {
            for (RichTextKind kind : values()) {
                if (kind.schemaUrl.equals(reference)) {
                    return kind;
                }
            }
            return null;
        }
    }
}
