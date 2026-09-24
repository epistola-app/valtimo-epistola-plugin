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
package app.epistola.valtimo.service.composer;

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
 */
public final class MissingFieldSelector {

    private MissingFieldSelector() {
    }

    /**
     * Filter a template's field tree down to the fields with no value in {@code resolvedData},
     * keeping the tree shape so the generated form still nests the way the template data does.
     *
     * @param fields       the template's fields
     * @param resolvedData the data the mapping produced
     * @param requiredOnly when true, only fields the schema marks required are asked for
     * @return the fields to ask for, in schema order; empty when the mapping covered everything
     */
    public static List<TemplateField> selectMissing(
            List<TemplateField> fields,
            Map<String, Object> resolvedData,
            boolean requiredOnly
    ) {
        List<TemplateField> missing = new ArrayList<>();
        if (fields == null) {
            return missing;
        }

        for (TemplateField field : fields) {
            Object value = resolvedData != null ? resolvedData.get(field.name()) : null;

            switch (field.fieldType()) {
                case OBJECT -> {
                    // An absent optional object is left alone entirely: asking for the parts of a
                    // block the letter does not use would be noise.
                    if (value == null && requiredOnly && !field.required()) {
                        continue;
                    }
                    List<TemplateField> children =
                            selectMissing(field.children(), asMap(value), requiredOnly);
                    if (!children.isEmpty()) {
                        missing.add(withChildren(field, children));
                    }
                }
                case ARRAY -> {
                    if (isEmpty(value) && (!requiredOnly || field.required())) {
                        missing.add(field);
                    }
                }
                case SCALAR -> {
                    if (isEmpty(value) && (!requiredOnly || field.required())) {
                        missing.add(field);
                    }
                }
            }
        }
        return missing;
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
