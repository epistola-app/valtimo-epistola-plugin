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
            if (value == null) {
                throw GenerateDocumentExpressionException.noValue(version, "versionRange", versionRange.source(),
                        "Write \"\" for no range, or fall back with ?: \"^1.0.0\".");
            }
        }
        try {
            return VersionRanges.ofExpressionResult(value).orElse(null);
        } catch (IllegalArgumentException refused) {
            throw GenerateDocumentExpressionException.invalidResult(
                    version, "versionRange", versionRange.source(), refused.getMessage());
        }
    }

    /**
     * The environment generation renders in (ADR 0009, second addendum):
     * <ul>
     *   <li>none configured: the plugin configuration's default — the usual route to it;</li>
     *   <li>an expression producing a non-empty string: that environment;</li>
     *   <li>one producing {@code ""}: deliberately none, the catalog's highest release;</li>
     *   <li>one producing no value: refused, because that is a typo or a variable the case lacks,
     *       never a choice — falling back silently rendered in another environment unnoticed.</li>
     * </ul>
     * An author who wants a fallback writes it, with the default bound as
     * {@code $plugin.defaultEnvironmentId}: {@code $pv.omgeving ?: $plugin.defaultEnvironmentId}.
     * A v0 link keeps its frozen behaviour: anything blank falls back to the default.
     *
     * @return the environment, or null for none
     */
    public String resolveEnvironment(
            JsonataMappingService mappingService, EvaluationContext context, String defaultEnvironmentId) {
        String fallback = defaultEnvironmentId == null || defaultEnvironmentId.isBlank() ? null : defaultEnvironmentId;
        if (environmentId == null || !environmentId.isConfigured()) {
            return fallback;
        }
        if (environmentId instanceof LiteralScalar || version < 1) {
            String resolved = environmentId.resolve(mappingService, context);
            return resolved == null || resolved.isBlank() ? fallback : resolved;
        }
        Object value;
        try {
            value = mappingService.evaluateValue(context.withExpression(environmentId.source()));
        } catch (RuntimeException exception) {
            throw GenerateDocumentExpressionException.evaluationFailed(
                    version, "environmentId", environmentId.source(), context, exception);
        }
        if (value == null) {
            throw GenerateDocumentExpressionException.noValue(version, "environmentId", environmentId.source(),
                    "Write \"\" for no environment, or fall back with ?: $plugin.defaultEnvironmentId.");
        }
        if (!(value instanceof String text)) {
            throw GenerateDocumentExpressionException.invalidResult(version, "environmentId", environmentId.source(),
                    "must evaluate to a string, such as \"productie\"");
        }
        return text.isBlank() ? null : text.strip();
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
