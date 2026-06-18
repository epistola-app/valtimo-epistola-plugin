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
 */
public record TemplateField(
        String name,
        String path,
        String type,
        FieldType fieldType,
        boolean required,
        String description,
        List<TemplateField> children
) {
    public enum FieldType {
        SCALAR,
        OBJECT,
        ARRAY
    }
}
