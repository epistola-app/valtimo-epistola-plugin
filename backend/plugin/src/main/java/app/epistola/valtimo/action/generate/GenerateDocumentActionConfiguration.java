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

import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.mapping.EvaluationContext;
import app.epistola.valtimo.mapping.JsonataMappingService;

import java.util.List;

public record GenerateDocumentActionConfiguration(
        int version,
        String catalogId,
        String templateId,
        ConfiguredScalar variantId,
        List<VariantAttribute> variantAttributes,
        ConfiguredScalar environmentId,
        String dataMapping,
        FileFormat outputFormat,
        ConfiguredScalar filename,
        String correlationId,
        String resultProcessVariable
) {

    public record VariantAttribute(String key, ConfiguredScalar value, boolean required) {}

    public sealed interface ConfiguredScalar permits LiteralScalar, JsonataScalar {

        String source();

        String resolve(JsonataMappingService mappingService, EvaluationContext context);

        default boolean isConfigured() {
            return source() != null && !source().isBlank();
        }
    }

    public record LiteralScalar(String source) implements ConfiguredScalar {

        @Override
        public String resolve(JsonataMappingService mappingService, EvaluationContext context) {
            return source;
        }
    }

    public record JsonataScalar(String source) implements ConfiguredScalar {

        @Override
        public String resolve(JsonataMappingService mappingService, EvaluationContext context) {
            return mappingService.evaluateScalar(context.withExpression(source));
        }
    }
}
