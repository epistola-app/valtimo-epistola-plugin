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
     */
    public record FieldHints(
            String title,
            String format,
            List<Object> allowedValues,
            Object defaultValue
    ) {
        public boolean isEmpty() {
            return title == null && format == null
                    && (allowedValues == null || allowedValues.isEmpty())
                    && defaultValue == null;
        }
    }

    public enum FieldType {
        SCALAR,
        OBJECT,
        ARRAY
    }
}
