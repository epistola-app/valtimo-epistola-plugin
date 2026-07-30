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
import app.epistola.valtimo.action.generate.GenerateDocumentActionConfiguration.VariantAttribute;

import java.util.List;
import java.util.Map;

abstract class AbstractGenerateDocumentActionVersionHandler implements GenerateDocumentActionVersionHandler {

    @Override
    public GenerateDocumentActionConfiguration parse(RawGenerateDocumentActionConfiguration raw) {
        requireNonBlank(raw.catalogId(), "No catalog configured: catalogId must be a non-blank string");
        requireNonBlank(raw.templateId(), "No template configured: templateId must be a non-blank string");
        requireNonBlank(raw.dataMapping(), "dataMapping must be a non-blank JSONata string");
        requireNonBlank(raw.outputFormat(), "outputFormat must be a non-blank string");
        requireNonBlank(raw.filename(), "filename must be a non-blank string");
        requireNonBlank(raw.resultProcessVariable(), "resultProcessVariable must be a non-blank string");

        List<VariantAttribute> attributes = parseAttributes(raw.variantAttributes());
        ConfiguredScalar variantId = scalar("variantId", raw.variantId());
        if (variantId.isConfigured() && !attributes.isEmpty()) {
            throw invalid("variantId and variantAttributes are mutually exclusive");
        }

        return new GenerateDocumentActionConfiguration(
                version(),
                raw.catalogId(),
                raw.templateId(),
                variantId,
                attributes,
                scalar("environmentId", raw.environmentId()),
                raw.dataMapping(),
                outputFormat(raw.outputFormat()),
                scalar("filename", raw.filename()),
                correlationId(raw.correlationId()),
                raw.resultProcessVariable());
    }

    protected abstract ConfiguredScalar scalar(String field, String value);

    protected ConfiguredScalar outputFormat(String value) {
        return scalar("outputFormat", value);
    }

    protected ConfiguredScalar correlationId(String value) {
        return scalar("correlationId", value);
    }

    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw invalid(message);
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
        return new VariantAttribute(keyString, scalar("variantAttributes." + keyString, valueString), requiredBoolean);
    }

    protected IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(
                "Invalid epistola-generate-document action configuration v" + version() + ": " + message);
    }
}
