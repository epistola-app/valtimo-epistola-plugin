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

import java.util.List;
import java.util.Map;

public record GenerateDocumentActionConfiguration(
        int version,
        ConfiguredScalar catalogId,
        ConfiguredScalar templateId,
        ConfiguredScalar variantId,
        List<VariantAttribute> variantAttributes,
        ConfiguredScalar environmentId,
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

    public sealed interface ConfiguredScalar permits LiteralScalar, JsonataScalar {

        String source();

        String resolve(JsonataMappingService mappingService, EvaluationContext context);

        default boolean isConfigured() {
            return source() != null && !source().isBlank();
        }
    }

    /**
     * A scalar that is taken exactly as written, with no expression evaluation.
     *
     * <p>Two things use it. Legacy v0 actions, whose scalars were literal by definition; and the
     * catalog and template of a v0/v1 action, which are ids picked from a dropdown rather than
     * expressions — evaluating {@code besluit-bezwaar} as JSONata would read it as a path and
     * resolve to nothing.
     *
     * @deprecated For v0 scalars only. A v1 scalar is a JSONata expression, and from v2 the
     * catalog and template are expressions too.
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
