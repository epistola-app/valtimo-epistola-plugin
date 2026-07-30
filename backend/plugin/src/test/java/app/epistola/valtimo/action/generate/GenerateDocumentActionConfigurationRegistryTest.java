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

import app.epistola.valtimo.action.generate.GenerateDocumentActionConfiguration.JsonataScalar;
import app.epistola.valtimo.action.generate.GenerateDocumentActionConfiguration.LiteralScalar;
import app.epistola.valtimo.domain.FileFormat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerateDocumentActionConfigurationRegistryTest {

    @Test
    void missingVersionUsesV0LiteralOrExpressionSemantics() {
        var config = GenerateDocumentActionConfigurationRegistry.parse(raw(
                null,
                "value.pdf",
                null,
                List.of(Map.of(
                        "key", "language",
                        "value", "nl",
                        "required", true))));

        assertThat(config.version()).isZero();
        assertThat(config.filename()).isInstanceOf(LiteralScalar.class);
        assertThat(config.variantAttributes()).hasSize(1);
        assertThat(config.variantAttributes().getFirst().value()).isInstanceOf(LiteralScalar.class);
    }

    @Test
    void malformedExpressionLikeV0ValueRemainsLiteral() {
        var config = GenerateDocumentActionConfigurationRegistry.parse(raw(
                null,
                "$pv.[broken",
                null,
                null));

        assertThat(config.filename()).isInstanceOf(LiteralScalar.class);
    }

    @Test
    void v1TreatsEveryScalarAsJsonata() {
        var config = GenerateDocumentActionConfigurationRegistry.parse(raw(
                1,
                "\"value.pdf\"",
                "$pv.variant",
                null));

        assertThat(config.filename()).isInstanceOf(JsonataScalar.class);
        assertThat(config.variantId()).isInstanceOf(JsonataScalar.class);
    }

    @Test
    void rejectsUnsupportedVersion() {
        assertThatThrownBy(() -> GenerateDocumentActionConfigurationRegistry.parse(raw(
                2,
                "\"value.pdf\"",
                null,
                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actionConfigVersion 2")
                .hasMessageContaining("latest supported version is 1");
    }

    @Test
    void rejectsDeprecatedMapVariantAttributes() {
        assertThatThrownBy(() -> GenerateDocumentActionConfigurationRegistry.parse(raw(
                null,
                "value.pdf",
                null,
                Map.of("language", "nl"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("variantAttributes must be an array");
    }

    @Test
    void rejectsMissingRequiredFlag() {
        assertThatThrownBy(() -> GenerateDocumentActionConfigurationRegistry.parse(raw(
                1,
                "\"value.pdf\"",
                null,
                List.of(Map.of("key", "language", "value", "\"nl\"")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("variantAttributes.required must be a boolean");
    }

    private static RawGenerateDocumentActionConfiguration raw(
            Integer version,
            String filename,
            String variantId,
            Object variantAttributes
    ) {
        return new RawGenerateDocumentActionConfiguration(
                version,
                "catalog",
                "template",
                variantId,
                variantAttributes,
                null,
                "{}",
                FileFormat.PDF,
                filename,
                null,
                "result");
    }
}
