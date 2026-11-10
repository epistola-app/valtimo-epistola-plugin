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

import app.epistola.valtimo.mapping.EvaluationContext;
import app.epistola.valtimo.mapping.JsonataMappingService;

import app.epistola.valtimo.service.VersionRanges;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @param versionRange the release of the catalog to render, as JSONata evaluating to a version range
 *                     ({@code app.epistola.valtimo.service.VersionRanges}) — a fixed one is the string
 *                     literal {@code "^1.4.0"} — or unset for the environment's draft, else its
 *                     highest release. An expression like the environment: the configurator designs
 *                     against one release and states which (ADR 0009). Read it through
 *                     {@link #resolveVersionRange} or {@link #literalVersionRange}
 */
public record GenerateDocumentActionConfiguration(
        int version,
        String catalogId,
        String templateId,
        ConfiguredScalar variantId,
        List<VariantAttribute> variantAttributes,
        ConfiguredScalar environmentId,
        ConfiguredScalar versionRange,
        String dataMapping,
        ConfiguredScalar outputFormat,
        ConfiguredScalar filename,
        ConfiguredScalar correlationId,
        String resultProcessVariable
) {

    public record VariantAttribute(String key, ConfiguredScalar value, boolean required) {}

    public Map<String, Object> evaluateDataMapping(
            JsonataMappingService mappingService,
            EvaluationContext context
    ) {
        try {
            return mappingService.evaluate(context.withExpression(dataMapping));
        } catch (RuntimeException exception) {
            throw GenerateDocumentExpressionException.evaluationFailed(
                    version, "dataMapping", dataMapping, context, exception);
        }
    }

    /**
     * The version range generation sends, evaluated: null when none is configured or the expression
     * produces nothing (or a blank string). Refused when it produces anything but a string that is a
     * range — a number in particular, because the bare {@code 1.0} evaluates to the number 1, which as
     * a range would mean {@code 1.x} rather than {@code 1.0.x}.
     */
    public String resolveVersionRange(JsonataMappingService mappingService, EvaluationContext context) {
        if (versionRange == null || !versionRange.isConfigured()) {
            return null;
        }
        Object value;
        if (versionRange instanceof LiteralScalar literal) {
            value = literal.source();
        } else {
            try {
                value = mappingService.evaluateValue(context.withExpression(versionRange.source()));
            } catch (RuntimeException exception) {
                throw GenerateDocumentExpressionException.evaluationFailed(
                        version, "versionRange", versionRange.source(), context, exception);
            }
        }
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw GenerateDocumentExpressionException.invalidResult(version, "versionRange", versionRange.source(),
                    "must evaluate to a string such as \"^1.4.0\", not a " + typeOf(value));
        }
        if (text.isBlank()) {
            return null;
        }
        String range = text.strip();
        if (VersionRanges.problem(range).isPresent()) {
            throw GenerateDocumentExpressionException.invalidResult(version, "versionRange", versionRange.source(),
                    "evaluated to a string that is not a version range");
        }
        return range;
    }

    /**
     * The version range when it is configured as a string literal, read without evaluating — what a
     * static check can verify. Empty when none is configured or it is an expression only known at run
     * time.
     */
    public Optional<String> literalVersionRange() {
        if (versionRange == null || !versionRange.isConfigured()) {
            return Optional.empty();
        }
        if (versionRange instanceof LiteralScalar literal) {
            return Optional.of(literal.source().strip());
        }
        return stringLiteral(versionRange.source());
    }

    /** What a JSONata string literal holds, when {@code source} is one that a version range can be. */
    static Optional<String> stringLiteral(String source) {
        Matcher literal = STRING_LITERAL.matcher(source);
        return literal.matches() && !literal.group(2).isBlank()
                ? Optional.of(literal.group(2).strip())
                : Optional.empty();
    }

    /** A JSONata string literal without escapes, which a version range never needs. */
    private static final Pattern STRING_LITERAL = Pattern.compile("^\\s*([\"'])([^\"'\\\\]*)\\1\\s*$");

    private static String typeOf(Object value) {
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof java.util.List<?>) {
            return "array";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        return value.getClass().getSimpleName();
    }

    public sealed interface ConfiguredScalar permits LiteralScalar, JsonataScalar {

        String source();

        String resolve(JsonataMappingService mappingService, EvaluationContext context);

        default boolean isConfigured() {
            return source() != null && !source().isBlank();
        }
    }

    /**
     * A scalar using the literal interpretation rules of legacy v0 actions.
     *
     * @deprecated Only v0 action configurations may contain literal scalars.
     * V1 values are JSONata expressions.
     */
    @Deprecated(forRemoval = true)
    public record LiteralScalar(String source) implements ConfiguredScalar {

        @Override
        public String resolve(JsonataMappingService mappingService, EvaluationContext context) {
            return source;
        }
    }

    public record JsonataScalar(int version, String field, String source) implements ConfiguredScalar {

        @Override
        public String resolve(JsonataMappingService mappingService, EvaluationContext context) {
            try {
                return mappingService.evaluateScalar(context.withExpression(source));
            } catch (RuntimeException exception) {
                throw GenerateDocumentExpressionException.evaluationFailed(
                        version, field, source, context, exception);
            }
        }
    }
}
