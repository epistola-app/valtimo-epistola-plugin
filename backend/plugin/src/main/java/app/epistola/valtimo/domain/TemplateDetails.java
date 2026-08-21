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
 * Detailed information about an Epistola template including its fields.
 *
 * @param id     The unique identifier of the template
 * @param name   The display name of the template
 * @param fields The list of fields that can be mapped for this template
 * @param schema The original JSON Schema received from Epistola
 * @param simpleMappingSupport Whether and how the schema can be represented by the Simple mapper
 */
public record TemplateDetails(
        String id,
        String name,
        List<TemplateField> fields,
        Object schema,
        SimpleMappingSupport simpleMappingSupport
) {
    public TemplateDetails(String id, String name, List<TemplateField> fields) {
        this(id, name, fields, null, SimpleMappingSupport.full());
    }
}
