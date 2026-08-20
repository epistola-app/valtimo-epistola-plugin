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
package app.epistola.valtimo.expression;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rejects valid JSON Schema features that the expression-source tree cannot represent faithfully.
 */
final class ExpressionFunctionAuthoringSchemaValidator {

    private static final Map<String, String> UNSUPPORTED_KEYWORDS = Map.ofEntries(
            Map.entry("$dynamicRef", "dynamic references"),
            Map.entry("prefixItems", "tuple arrays"),
            Map.entry("patternProperties", "pattern properties"),
            Map.entry("dependentRequired", "dependent required fields"),
            Map.entry("dependentSchemas", "dependent schemas"),
            Map.entry("dependencies", "schema dependencies"),
            Map.entry("if", "conditional schemas"),
            Map.entry("then", "conditional schemas"),
            Map.entry("else", "conditional schemas")
    );
    private static final List<String> SCHEMA_PROPERTIES = List.of(
            "items", "contains", "additionalProperties", "propertyNames", "not", "unevaluatedProperties"
    );
    private static final List<String> SCHEMA_ARRAYS = List.of("allOf", "anyOf", "oneOf");
    private static final List<String> SCHEMA_MAPS = List.of("properties", "$defs", "definitions");

    private ExpressionFunctionAuthoringSchemaValidator() {
    }

    static Optional<Problem> validate(JsonNode schema) {
        return validate(schema, "#");
    }

    private static Optional<Problem> validate(JsonNode schema, String path) {
        if (!schema.isObject()) {
            return Optional.empty();
        }

        JsonNode reference = schema.get("$ref");
        if (reference != null && reference.isTextual() && !reference.asText().startsWith("#/")) {
            return Optional.of(new Problem(path + "/$ref", "external references"));
        }
        JsonNode items = schema.get("items");
        if (items != null && items.isArray()) {
            return Optional.of(new Problem(path + "/items", "tuple arrays"));
        }
        for (var entry : UNSUPPORTED_KEYWORDS.entrySet()) {
            if (schema.has(entry.getKey())) {
                return Optional.of(new Problem(path + "/" + entry.getKey(), entry.getValue()));
            }
        }

        for (String property : SCHEMA_PROPERTIES) {
            JsonNode child = schema.get(property);
            if (child != null) {
                Optional<Problem> problem = validate(child, path + "/" + property);
                if (problem.isPresent()) {
                    return problem;
                }
            }
        }
        for (String property : SCHEMA_ARRAYS) {
            JsonNode children = schema.get(property);
            if (children != null && children.isArray()) {
                for (int index = 0; index < children.size(); index++) {
                    Optional<Problem> problem = validate(children.get(index), path + "/" + property + "/" + index);
                    if (problem.isPresent()) {
                        return problem;
                    }
                }
            }
        }
        for (String property : SCHEMA_MAPS) {
            JsonNode children = schema.get(property);
            if (children != null && children.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = children.properties().iterator();
                while (fields.hasNext()) {
                    var field = fields.next();
                    Optional<Problem> problem = validate(
                            field.getValue(), path + "/" + property + "/" + escape(field.getKey()));
                    if (problem.isPresent()) {
                        return problem;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    record Problem(String path, String feature) {
    }
}
