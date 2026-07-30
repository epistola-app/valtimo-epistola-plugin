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
package app.epistola.valtimo.action.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class GenerateDocumentActionConfigurationRegistry {

    public static final int LATEST_VERSION = 1;

    private static final Map<Integer, GenerateDocumentActionVersionParser> PARSERS = createParsers();

    private GenerateDocumentActionConfigurationRegistry() {}

    @SuppressWarnings("removal")
    private static Map<Integer, GenerateDocumentActionVersionParser> createParsers() {
        return List.<GenerateDocumentActionVersionParser>of(
                        new GenerateDocumentActionV0Parser(),
                        new GenerateDocumentActionV1Parser())
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        GenerateDocumentActionVersionParser::version,
                        Function.identity()));
    }

    public static GenerateDocumentActionConfiguration parse(GenerateDocumentActionProperties properties) {
        int version = properties.actionConfigVersion() == null ? 0 : properties.actionConfigVersion();
        if (version < 0) {
            throw new IllegalArgumentException(
                    "Invalid epistola-generate-document actionConfigVersion: " + version);
        }
        GenerateDocumentActionVersionParser parser = PARSERS.get(version);
        if (parser == null) {
            throw new IllegalArgumentException(
                    "Unsupported epistola-generate-document actionConfigVersion " + version
                            + "; latest supported version is " + LATEST_VERSION);
        }
        return parser.parse(properties);
    }

    public static GenerateDocumentActionConfiguration parse(ObjectNode properties) {
        return parse(new GenerateDocumentActionProperties(
                integerOrNull(properties, "actionConfigVersion"),
                textOrNull(properties, "catalogId"),
                textOrNull(properties, "templateId"),
                textOrNull(properties, "variantId"),
                attributesOrNull(properties.get("variantAttributes")),
                textOrNull(properties, "environmentId"),
                requiredText(properties, "dataMapping"),
                textOrNull(properties, "outputFormat"),
                textOrNull(properties, "filename"),
                textOrNull(properties, "correlationId"),
                textOrNull(properties, "resultProcessVariable")));
    }

    private static Integer integerOrNull(ObjectNode properties, String field) {
        JsonNode node = properties.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isInt()) {
            throw invalidField(field, "must be an integer");
        }
        return node.intValue();
    }

    private static String requiredText(ObjectNode properties, String field) {
        String value = textOrNull(properties, field);
        if (value == null) {
            throw invalidField(field, "must be a string");
        }
        return value;
    }

    private static String textOrNull(ObjectNode properties, String field) {
        JsonNode node = properties.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalidField(field, "must be a string");
        }
        return node.textValue();
    }

    private static Object attributesOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            return node;
        }
        List<Object> attributes = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isObject()) {
                attributes.add(item);
                return;
            }
            Map<String, Object> attribute = new LinkedHashMap<>();
            item.properties().forEach(entry ->
                    attribute.put(entry.getKey(), scalarNodeValue(entry.getValue())));
            attributes.add(attribute);
        });
        return attributes;
    }

    private static Object scalarNodeValue(JsonNode node) {
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node;
    }

    private static IllegalArgumentException invalidField(String field, String message) {
        return new IllegalArgumentException(
                "Invalid epistola-generate-document action configuration: "
                        + field + " " + message);
    }
}
