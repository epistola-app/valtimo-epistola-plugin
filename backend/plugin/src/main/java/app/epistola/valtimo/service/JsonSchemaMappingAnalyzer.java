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
package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.SimpleMappingSupport;
import app.epistola.valtimo.domain.TemplateField;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the structural subset of JSON Schema used by the Simple data mapper.
 * Validation-only keywords remain available through the raw schema and are deliberately ignored here.
 */
final class JsonSchemaMappingAnalyzer {

    private static final int MAX_DEPTH = 32;
    private static final Set<String> CONDITIONAL_KEYWORDS = Set.of(
            "if", "then", "else", "dependentSchemas", "dependentRequired"
    );

    Analysis analyze(Object schema) {
        if (!(schema instanceof Map<?, ?> schemaMap)) {
            return new Analysis(
                    Collections.emptyList(),
                    SimpleMappingSupport.unsupported("The template does not expose an object JSON Schema.")
            );
        }

        Map<String, Object> root = stringMap(schemaMap);
        ResolvedSchema resolvedRoot = resolve(root, root, new HashSet<>(), 0);
        if (resolvedRoot.complex() || !isObjectShape(resolvedRoot.schema())) {
            String reason = resolvedRoot.reason() != null
                    ? resolvedRoot.reason()
                    : "The schema root is not a deterministic object.";
            return new Analysis(Collections.emptyList(), SimpleMappingSupport.unsupported(reason));
        }
        if (containsConditionalKeyword(resolvedRoot.schema())) {
            return new Analysis(
                    Collections.emptyList(),
                    SimpleMappingSupport.unsupported(
                            "Conditional root schemas require the Advanced JSONata editor."
                    )
            );
        }

        List<TemplateField> fields = extractFields(resolvedRoot.schema(), root, "", new HashSet<>(), 0);
        boolean partial = fields.stream().anyMatch(this::containsComplexField)
                || hasDynamicProperties(resolvedRoot.schema());
        SimpleMappingSupport support = partial
                ? SimpleMappingSupport.partial(
                        "Complex fields must be mapped as complete values with one JSONata expression."
                )
                : SimpleMappingSupport.full();
        return new Analysis(fields, support);
    }

    private List<TemplateField> extractFields(
            Map<String, Object> schema,
            Map<String, Object> root,
            String parentPath,
            Set<String> referenceStack,
            int depth
    ) {
        Object properties = schema.get("properties");
        if (!(properties instanceof Map<?, ?> propertiesMap)) {
            return Collections.emptyList();
        }

        Set<String> required = requiredNames(schema.get("required"));
        List<TemplateField> fields = new ArrayList<>();
        for (Map.Entry<?, ?> entry : propertiesMap.entrySet()) {
            String name = String.valueOf(entry.getKey());
            String path = parentPath.isEmpty() ? name : parentPath + "." + name;
            fields.add(buildField(
                    name,
                    path,
                    entry.getValue(),
                    required.contains(name),
                    root,
                    referenceStack,
                    depth + 1
            ));
        }
        return fields;
    }

    private TemplateField buildField(
            String name,
            String path,
            Object rawSchema,
            boolean required,
            Map<String, Object> root,
            Set<String> referenceStack,
            int depth
    ) {
        if (!(rawSchema instanceof Map<?, ?> rawMap)) {
            return complexField(
                    name,
                    path,
                    "value",
                    TemplateField.FieldType.SCALAR,
                    required,
                    null,
                    "Boolean and empty schemas must be mapped as a complete value.",
                    false
            );
        }

        Map<String, Object> fieldSchema = stringMap(rawMap);
        String originalDescription = text(fieldSchema.get("description"));
        ResolvedSchema resolved = resolve(fieldSchema, root, referenceStack, depth);
        String description = originalDescription != null
                ? originalDescription
                : text(resolved.schema().get("description"));

        if (resolved.complex() || containsConditionalKeyword(resolved.schema())) {
            String reason = resolved.reason() != null
                    ? resolved.reason()
                    : "Conditional schemas must be mapped as a complete value.";
            return complexField(
                    name,
                    path,
                    resolved.typeLabel(),
                    inferFieldType(resolved.schema()),
                    required,
                    description,
                    reason,
                    resolved.nullable()
            );
        }

        if (isObjectShape(resolved.schema())) {
            List<TemplateField> children = extractFields(
                    resolved.schema(), root, path, resolved.referenceStack(), depth
            );
            return new TemplateField(
                    name,
                    path,
                    "object",
                    TemplateField.FieldType.OBJECT,
                    required,
                    description,
                    children,
                    false,
                    null,
                    resolved.nullable()
            );
        }

        if (isArraySchema(resolved.schema())) {
            return buildArrayField(name, path, required, description, resolved, root, depth);
        }

        return new TemplateField(
                name,
                path,
                resolved.typeLabel(),
                TemplateField.FieldType.SCALAR,
                required,
                description,
                Collections.emptyList(),
                false,
                null,
                resolved.nullable()
        );
    }

    private TemplateField buildArrayField(
            String name,
            String path,
            boolean required,
            String description,
            ResolvedSchema arraySchema,
            Map<String, Object> root,
            int depth
    ) {
        Object rawItems = arraySchema.schema().get("items");
        if (!(rawItems instanceof Map<?, ?> itemsMap)) {
            return new TemplateField(
                    name,
                    path,
                    "array<value>",
                    TemplateField.FieldType.SCALAR,
                    required,
                    description,
                    Collections.emptyList(),
                    false,
                    null,
                    arraySchema.nullable()
            );
        }

        ResolvedSchema items = resolve(
                stringMap(itemsMap), root, arraySchema.referenceStack(), depth + 1
        );
        String type = "array<" + items.typeLabel() + ">";
        if (items.complex()) {
            return complexField(
                    name,
                    path,
                    type,
                    TemplateField.FieldType.ARRAY,
                    required,
                    description,
                    "This array has alternative or unsupported item shapes and must be mapped as a complete value.",
                    arraySchema.nullable()
            );
        }
        if (isArraySchema(items.schema())) {
            return complexField(
                    name,
                    path,
                    type,
                    TemplateField.FieldType.ARRAY,
                    required,
                    description,
                    "Nested arrays must be mapped as a complete value.",
                    arraySchema.nullable()
            );
        }
        if (isObjectShape(items.schema())) {
            List<TemplateField> children = extractFields(
                    items.schema(), root, path + "[]", items.referenceStack(), depth + 1
            );
            return new TemplateField(
                    name,
                    path,
                    type,
                    TemplateField.FieldType.ARRAY,
                    required,
                    description,
                    children,
                    true,
                    "Arrays of objects must be mapped as a complete value.",
                    arraySchema.nullable()
            );
        }
        return new TemplateField(
                name,
                path,
                type,
                TemplateField.FieldType.SCALAR,
                required,
                description,
                Collections.emptyList(),
                false,
                null,
                arraySchema.nullable()
        );
    }

    private ResolvedSchema resolve(
            Map<String, Object> schema,
            Map<String, Object> root,
            Set<String> referenceStack,
            int depth
    ) {
        if (depth > MAX_DEPTH) {
            return complex(schema, "value", "The schema nesting limit was exceeded.", false, referenceStack);
        }

        Map<String, Object> working = new LinkedHashMap<>(schema);
        boolean nullable = false;
        Set<String> nextReferenceStack = new HashSet<>(referenceStack);

        Object referenceValue = working.get("$ref");
        if (referenceValue instanceof String reference) {
            if (!reference.startsWith("#/")) {
                return complex(
                        working,
                        referenceLabel(reference),
                        "External JSON Schema references require a complete-value mapping.",
                        false,
                        nextReferenceStack
                );
            }
            if (!nextReferenceStack.add(reference)) {
                return complex(
                        working,
                        referenceLabel(reference),
                        "Recursive JSON Schema references require a complete-value mapping.",
                        false,
                        nextReferenceStack
                );
            }
            Map<String, Object> referenced = resolvePointer(root, reference);
            if (referenced == null) {
                return complex(
                        working,
                        referenceLabel(reference),
                        "The local JSON Schema reference could not be resolved.",
                        false,
                        nextReferenceStack
                );
            }
            Map<String, Object> siblings = new LinkedHashMap<>(working);
            siblings.remove("$ref");
            ResolvedSchema referencedSchema = resolve(referenced, root, nextReferenceStack, depth + 1);
            if (referencedSchema.complex()) {
                return referencedSchema.withTypeLabel(referenceLabel(reference));
            }
            working = mergeConjunctive(referencedSchema.schema(), siblings);
            nullable = referencedSchema.nullable();
        }

        String unionKeyword = working.containsKey("oneOf") ? "oneOf"
                : working.containsKey("anyOf") ? "anyOf" : null;
        if (unionKeyword != null && working.get(unionKeyword) instanceof List<?> variants) {
            List<Map<String, Object>> nonNullVariants = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            boolean hasNullVariant = false;
            for (Object variant : variants) {
                if (!(variant instanceof Map<?, ?> variantMap)) {
                    continue;
                }
                Map<String, Object> mappedVariant = stringMap(variantMap);
                if (isNullSchema(mappedVariant)) {
                    hasNullVariant = true;
                } else {
                    nonNullVariants.add(mappedVariant);
                    labels.add(schemaLabel(mappedVariant));
                }
            }
            if (nonNullVariants.size() == 1 && hasNullVariant) {
                Map<String, Object> siblings = new LinkedHashMap<>(working);
                siblings.remove(unionKeyword);
                ResolvedSchema nonNull = resolve(
                        nonNullVariants.get(0), root, nextReferenceStack, depth + 1
                );
                if (nonNull.complex()) {
                    return nonNull.withNullable(true);
                }
                working = mergeConjunctive(nonNull.schema(), siblings);
                nullable = true;
            } else if (nonNullVariants.size() > 1) {
                return complex(
                        working,
                        unionKeyword + "<" + String.join(" | ", labels) + ">",
                        "Multiple schema alternatives must be mapped as a complete value.",
                        hasNullVariant,
                        nextReferenceStack
                );
            }
        }

        Object typeValue = working.get("type");
        if (typeValue instanceof List<?> types) {
            List<String> nonNullTypes = types.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(type -> !"null".equals(type))
                    .toList();
            nullable = nullable || types.contains("null");
            if (nonNullTypes.size() == 1) {
                working.put("type", nonNullTypes.get(0));
            } else if (nonNullTypes.size() > 1) {
                return complex(
                        working,
                        String.join(" | ", nonNullTypes),
                        "Multiple possible JSON value types must be mapped as a complete value.",
                        nullable,
                        nextReferenceStack
                );
            }
        }

        Object allOfValue = working.remove("allOf");
        if (allOfValue instanceof List<?> variants) {
            for (Object variant : variants) {
                if (!(variant instanceof Map<?, ?> variantMap)) {
                    return complex(
                            working,
                            primaryType(working),
                            "A boolean allOf branch cannot be expanded by the Simple mapper.",
                            nullable,
                            nextReferenceStack
                    );
                }
                ResolvedSchema resolvedVariant = resolve(
                        stringMap(variantMap), root, nextReferenceStack, depth + 1
                );
                if (resolvedVariant.complex()) {
                    return resolvedVariant.withNullable(nullable || resolvedVariant.nullable());
                }
                if (!compatibleConjunctiveShapes(working, resolvedVariant.schema())) {
                    return complex(
                            working,
                            primaryType(working),
                            "Conflicting allOf branches require a complete-value mapping.",
                            nullable,
                            nextReferenceStack
                    );
                }
                working = mergeConjunctive(working, resolvedVariant.schema());
                nullable = nullable || resolvedVariant.nullable();
            }
        }

        return new ResolvedSchema(
                working,
                nullable,
                false,
                null,
                primaryType(working),
                nextReferenceStack
        );
    }

    private Map<String, Object> mergeConjunctive(
            Map<String, Object> left,
            Map<String, Object> right
    ) {
        Map<String, Object> merged = new LinkedHashMap<>(left);
        for (Map.Entry<String, Object> entry : right.entrySet()) {
            String key = entry.getKey();
            if ("properties".equals(key)
                    && merged.get(key) instanceof Map<?, ?> leftProperties
                    && entry.getValue() instanceof Map<?, ?> rightProperties) {
                Map<String, Object> properties = stringMap(leftProperties);
                for (Map.Entry<?, ?> property : rightProperties.entrySet()) {
                    String propertyName = String.valueOf(property.getKey());
                    Object existing = properties.get(propertyName);
                    if (existing instanceof Map<?, ?> existingMap && property.getValue() instanceof Map<?, ?> newMap) {
                        properties.put(
                                propertyName,
                                Map.of("allOf", List.of(stringMap(existingMap), stringMap(newMap)))
                        );
                    } else {
                        properties.put(propertyName, property.getValue());
                    }
                }
                merged.put(key, properties);
            } else if ("required".equals(key)) {
                LinkedHashSet<String> required = new LinkedHashSet<>(requiredNames(merged.get(key)));
                required.addAll(requiredNames(entry.getValue()));
                merged.put(key, new ArrayList<>(required));
            } else if (entry.getValue() != null) {
                merged.put(key, entry.getValue());
            }
        }
        return merged;
    }

    private boolean compatibleConjunctiveShapes(
            Map<String, Object> left,
            Map<String, Object> right
    ) {
        String leftType = explicitType(left);
        String rightType = explicitType(right);
        return leftType == null || rightType == null || leftType.equals(rightType);
    }

    private Map<String, Object> resolvePointer(Map<String, Object> root, String reference) {
        Object current = root;
        for (String rawSegment : reference.substring(2).split("/")) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return null;
            }
            String segment = rawSegment.replace("~1", "/").replace("~0", "~");
            current = currentMap.get(segment);
        }
        return current instanceof Map<?, ?> resolved ? stringMap(resolved) : null;
    }

    private TemplateField complexField(
            String name,
            String path,
            String type,
            TemplateField.FieldType fieldType,
            boolean required,
            String description,
            String reason,
            boolean nullable
    ) {
        return new TemplateField(
                name,
                path,
                type,
                fieldType,
                required,
                description,
                Collections.emptyList(),
                true,
                reason,
                nullable
        );
    }

    private ResolvedSchema complex(
            Map<String, Object> schema,
            String typeLabel,
            String reason,
            boolean nullable,
            Set<String> referenceStack
    ) {
        return new ResolvedSchema(schema, nullable, true, reason, typeLabel, referenceStack);
    }

    private boolean containsComplexField(TemplateField field) {
        return field.complex() || field.children().stream().anyMatch(this::containsComplexField);
    }

    private boolean containsConditionalKeyword(Map<String, Object> schema) {
        return CONDITIONAL_KEYWORDS.stream().anyMatch(schema::containsKey);
    }

    private boolean hasDynamicProperties(Map<String, Object> schema) {
        return schema.containsKey("patternProperties")
                || schema.get("additionalProperties") instanceof Map<?, ?>;
    }

    private TemplateField.FieldType inferFieldType(Map<String, Object> schema) {
        if (isArraySchema(schema)) {
            return TemplateField.FieldType.ARRAY;
        }
        if (isObjectShape(schema)) {
            return TemplateField.FieldType.OBJECT;
        }
        return TemplateField.FieldType.SCALAR;
    }

    private boolean isObjectShape(Map<String, Object> schema) {
        return "object".equals(explicitType(schema)) || schema.get("properties") instanceof Map<?, ?>;
    }

    private boolean isArraySchema(Map<String, Object> schema) {
        return "array".equals(explicitType(schema));
    }

    private boolean isNullSchema(Map<String, Object> schema) {
        Object type = schema.get("type");
        return "null".equals(type)
                || type instanceof List<?> types && types.size() == 1 && types.contains("null");
    }

    private String primaryType(Map<String, Object> schema) {
        String explicitType = explicitType(schema);
        if (explicitType != null) {
            return explicitType;
        }
        if (schema.get("properties") instanceof Map<?, ?>) {
            return "object";
        }
        return "value";
    }

    private String explicitType(Map<String, Object> schema) {
        return schema.get("type") instanceof String type ? type : null;
    }

    private String schemaLabel(Map<String, Object> schema) {
        if (schema.get("$ref") instanceof String reference) {
            return referenceLabel(reference);
        }
        return primaryType(schema);
    }

    private String referenceLabel(String reference) {
        int separator = reference.lastIndexOf('/');
        String label = separator >= 0 ? reference.substring(separator + 1) : reference;
        return label.replace("~1", "/").replace("~0", "~");
    }

    private Set<String> requiredNames(Object value) {
        if (!(value instanceof List<?> list)) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        list.stream().filter(String.class::isInstance).map(String.class::cast).forEach(names::add);
        return names;
    }

    private Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        source.forEach((key, value) -> mapped.put(String.valueOf(key), value));
        return mapped;
    }

    private String text(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    record Analysis(List<TemplateField> fields, SimpleMappingSupport support) {
    }

    private record ResolvedSchema(
            Map<String, Object> schema,
            boolean nullable,
            boolean complex,
            String reason,
            String typeLabel,
            Set<String> referenceStack
    ) {
        ResolvedSchema withNullable(boolean nextNullable) {
            return new ResolvedSchema(
                    schema, nextNullable, complex, reason, typeLabel, referenceStack
            );
        }

        ResolvedSchema withTypeLabel(String nextTypeLabel) {
            return new ResolvedSchema(
                    schema, nullable, complex, reason, nextTypeLabel, referenceStack
            );
        }
    }
}
