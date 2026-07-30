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

import app.epistola.valtimo.action.generate.GenerateDocumentActionConfiguration.ConfiguredScalar;
import app.epistola.valtimo.action.generate.GenerateDocumentActionConfiguration.JsonataScalar;
import app.epistola.valtimo.action.generate.GenerateDocumentActionConfiguration.LiteralScalar;
import app.epistola.valtimo.action.generate.GenerateDocumentActionConfiguration.VariantAttribute;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.dashjoin.jsonata.Jsonata.jsonata;

/**
 * Parses legacy unversioned generate-document actions.
 *
 * @deprecated Retained only for executing existing v0 process links. New and
 * resaved actions must use v1.
 */
@Deprecated(forRemoval = true)
@SuppressWarnings("removal")
final class GenerateDocumentActionV0Parser implements GenerateDocumentActionVersionParser {

    private static final Pattern JSONATA_MARKER = Pattern.compile("[$&({?\\[]");

    @Override
    public int version() {
        return 0;
    }

    @Override
    public GenerateDocumentActionConfiguration parse(GenerateDocumentActionProperties properties) {
        requireNonBlank(properties.catalogId(), "No catalog configured: catalogId must be a non-blank string");
        requireNonBlank(properties.templateId(), "No template configured: templateId must be a non-blank string");
        requireNonBlank(properties.dataMapping(), "dataMapping must be a non-blank JSONata string");
        requireNonBlank(properties.outputFormat(), "outputFormat must be a non-blank string");
        requireNonBlank(properties.filename(), "filename must be a non-blank string");
        requireNonBlank(
                properties.resultProcessVariable(),
                "resultProcessVariable must be a non-blank string");
        validateDataMapping(properties.dataMapping());

        List<VariantAttribute> attributes = parseAttributes(properties.variantAttributes());
        ConfiguredScalar variantId = scalar("variantId", properties.variantId());
        if (variantId.isConfigured() && !attributes.isEmpty()) {
            throw invalid("variantId and variantAttributes are mutually exclusive");
        }

        return new GenerateDocumentActionConfiguration(
                version(),
                properties.catalogId(),
                properties.templateId(),
                variantId,
                attributes,
                scalar("environmentId", properties.environmentId()),
                properties.dataMapping(),
                new LiteralScalar(properties.outputFormat()),
                scalar("filename", properties.filename()),
                new LiteralScalar(properties.correlationId()),
                properties.resultProcessVariable());
    }

    private ConfiguredScalar scalar(String field, String value) {
        if (value == null || (!value.startsWith("\"") && !JSONATA_MARKER.matcher(value).find())) {
            return new LiteralScalar(value);
        }
        try {
            jsonata(value);
            return new JsonataScalar(version(), field, value);
        } catch (RuntimeException ignored) {
            return new LiteralScalar(value);
        }
    }

    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw invalid(message);
        }
    }

    private void validateDataMapping(String expression) {
        try {
            jsonata(expression);
        } catch (RuntimeException exception) {
            throw invalid(
                    "dataMapping must contain valid JSONata (expression='"
                            + GenerateDocumentExpressionException.expressionSnippet(expression) + "')",
                    exception);
        }
    }

    private List<VariantAttribute> parseAttributes(Object rawAttributes) {
        if (rawAttributes == null) {
            return List.of();
        }
        if (!(rawAttributes instanceof List<?> list)) {
            throw invalid("variantAttributes must be an array");
        }

        return list.stream().map(this::parseAttribute).toList();
    }

    private VariantAttribute parseAttribute(Object item) {
        if (!(item instanceof Map<?, ?> map)) {
            throw invalid("each variantAttributes entry must be an object");
        }
        Object key = map.get("key");
        Object value = map.get("value");
        Object required = map.get("required");
        if (!(key instanceof String keyString) || keyString.isBlank()) {
            throw invalid("variantAttributes.key must be a non-blank string");
        }
        if (!(value instanceof String valueString) || valueString.isBlank()) {
            throw invalid("variantAttributes.value must be a non-blank string");
        }
        if (!(required instanceof Boolean requiredBoolean)) {
            throw invalid("variantAttributes.required must be a boolean");
        }
        return new VariantAttribute(
                keyString,
                scalar("variantAttributes." + keyString, valueString),
                requiredBoolean);
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(
                "Invalid epistola-generate-document action configuration v" + version() + ": " + message);
    }

    private IllegalArgumentException invalid(String message, RuntimeException cause) {
        return new IllegalArgumentException(
                "Invalid epistola-generate-document action configuration v" + version() + ": " + message,
                cause);
    }
}
