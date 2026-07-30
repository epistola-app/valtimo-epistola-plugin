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
import app.epistola.valtimo.mapping.EvaluationContext;
import app.epistola.valtimo.mapping.JsonataMappingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.delegate.DelegateExecution;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("removal")
class GenerateDocumentActionConfigurationRegistryTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void frontendAndBackendShareV0ScalarCompatibilitySemantics() throws IOException {
        V0ScalarCompatibilityFixture[] fixtures;
        try (var input = getClass().getResourceAsStream(
                "/compatibility-fixtures/generate-document-v0-scalar-compatibility.json")) {
            assertThat(input).as("shared v0 scalar compatibility fixture").isNotNull();
            fixtures = OBJECT_MAPPER.readValue(input, V0ScalarCompatibilityFixture[].class);
        }

        for (V0ScalarCompatibilityFixture fixture : fixtures) {
            var config = GenerateDocumentActionConfigurationRegistry.parse(properties(
                    null,
                    fixture.value(),
                    null,
                    null));
            Class<?> expectedType = fixture.interpretation().equals("expression")
                    ? JsonataScalar.class
                    : LiteralScalar.class;

            assertThat(config.filename())
                    .as(fixture.name())
                    .isInstanceOf(expectedType)
                    .extracting(GenerateDocumentActionConfiguration.ConfiguredScalar::source)
                    .isEqualTo(fixture.value());
        }
    }

    @Test
    void missingVersionUsesV0LiteralOrExpressionSemantics() {
        var config = GenerateDocumentActionConfigurationRegistry.parse(properties(
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
        var config = GenerateDocumentActionConfigurationRegistry.parse(properties(
                null,
                "$pv.[broken",
                null,
                null));

        assertThat(config.filename()).isInstanceOf(LiteralScalar.class);
    }

    @Test
    void v1TreatsEveryScalarAsJsonata() {
        var config = GenerateDocumentActionConfigurationRegistry.parse(properties(
                1,
                "\"value.pdf\"",
                "$pv.variant",
                null));

        assertThat(config.filename()).isInstanceOf(JsonataScalar.class);
        assertThat(config.variantId()).isInstanceOf(JsonataScalar.class);
        assertThat(config.outputFormat()).isInstanceOf(JsonataScalar.class);
        assertThat(config.correlationId()).isInstanceOf(JsonataScalar.class);
    }

    @Test
    void propertiesAndObjectNodeEntryPointsProduceEquivalentConfiguration() {
        var properties = properties(1, "\"value.pdf\"", "$pv.variant", null);
        var objectNode = JsonNodeFactory.instance.objectNode()
                .put("actionConfigVersion", 1)
                .put("catalogId", "catalog")
                .put("templateId", "template")
                .put("variantId", "$pv.variant")
                .put("dataMapping", "{}")
                .put("outputFormat", "\"PDF\"")
                .put("filename", "\"value.pdf\"")
                .put("correlationId", "$pv.correlationId")
                .put("resultProcessVariable", "result");

        assertThat(GenerateDocumentActionConfigurationRegistry.parse(objectNode))
                .isEqualTo(GenerateDocumentActionConfigurationRegistry.parse(properties));
    }

    @Test
    void rejectsUnsupportedVersion() {
        assertThatThrownBy(() -> GenerateDocumentActionConfigurationRegistry.parse(properties(
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
        assertThatThrownBy(() -> GenerateDocumentActionConfigurationRegistry.parse(properties(
                null,
                "value.pdf",
                null,
                Map.of("language", "nl"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("variantAttributes must be an array");
    }

    @Test
    void rejectsMissingRequiredFlag() {
        assertThatThrownBy(() -> GenerateDocumentActionConfigurationRegistry.parse(properties(
                1,
                "\"value.pdf\"",
                null,
                List.of(Map.of("key", "language", "value", "\"nl\"")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("variantAttributes.required must be a boolean");
    }

    @Test
    void eachVersionRejectsVariantIdTogetherWithVariantAttributes() {
        for (int version : List.of(0, 1)) {
            String variantId = version == 0 ? "variant" : "\"variant\"";

            assertThatThrownBy(() -> GenerateDocumentActionConfigurationRegistry.parse(properties(
                    version,
                    version == 0 ? "value.pdf" : "\"value.pdf\"",
                    variantId,
                    List.of(Map.of(
                            "key", "language",
                            "value", version == 0 ? "nl" : "\"nl\"",
                            "required", true)))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("configuration v" + version)
                    .hasMessageContaining("variantId and variantAttributes are mutually exclusive");
        }
    }

    @Test
    void eachVersionRejectsInvalidDataMappingSyntax() {
        for (int version : List.of(0, 1)) {
            var valid = properties(
                    version,
                    version == 0 ? "value.pdf" : "\"value.pdf\"",
                    null,
                    null);
            var invalid = new GenerateDocumentActionProperties(
                    valid.actionConfigVersion(),
                    valid.catalogId(),
                    valid.templateId(),
                    valid.variantId(),
                    valid.variantAttributes(),
                    valid.environmentId(),
                    "<mapping>",
                    valid.outputFormat(),
                    valid.filename(),
                    valid.correlationId(),
                    valid.resultProcessVariable());

            assertThatThrownBy(() -> GenerateDocumentActionConfigurationRegistry.parse(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("configuration v" + version)
                    .hasMessageContaining("dataMapping")
                    .hasMessageContaining("<mapping>")
                    .hasCauseInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void rejectsMissingRequiredFieldsAtTheVersionBoundary() {
        for (int version : List.of(0, 1)) {
            assertMissingRequired(version, "catalogId", propertiesMissing(version, "catalogId"));
            assertMissingRequired(version, "templateId", propertiesMissing(version, "templateId"));
            assertMissingRequired(version, "dataMapping", propertiesMissing(version, "dataMapping"));
            assertMissingRequired(version, "outputFormat", propertiesMissing(version, "outputFormat"));
            assertMissingRequired(version, "filename", propertiesMissing(version, "filename"));
            assertMissingRequired(
                    version,
                    "resultProcessVariable",
                    propertiesMissing(version, "resultProcessVariable"));
        }
    }

    @Test
    void syntaxErrorIdentifiesVersionFieldAndExpression() {
        GenerateDocumentActionProperties valid = properties(1, "\"value.pdf\"", null, null);
        var invalid = new GenerateDocumentActionProperties(
                valid.actionConfigVersion(),
                valid.catalogId(),
                valid.templateId(),
                valid.variantId(),
                valid.variantAttributes(),
                valid.environmentId(),
                valid.dataMapping(),
                valid.outputFormat(),
                "<filename>",
                valid.correlationId(),
                valid.resultProcessVariable());

        assertThatThrownBy(() -> GenerateDocumentActionConfigurationRegistry.parse(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configuration v1")
                .hasMessageContaining("filename")
                .hasMessageContaining("<filename>")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void runtimeErrorIdentifiesFieldAndExecutionWithoutIncludingRuntimeValues() {
        var config = GenerateDocumentActionConfigurationRegistry.parse(properties(
                1,
                "\"value.pdf\"",
                null,
                null));
        JsonataMappingService mappingService = mock(JsonataMappingService.class);
        RuntimeException parserFailure = new RuntimeException("Syntax error: \"filename\"");
        when(mappingService.evaluateScalar(org.mockito.ArgumentMatchers.any()))
                .thenThrow(parserFailure);

        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getProcessDefinitionId()).thenReturn("letters:3:definition");
        when(execution.getProcessInstanceId()).thenReturn("process-123");
        when(execution.getCurrentActivityId()).thenReturn("generate-letter");
        EvaluationContext context = EvaluationContext.builder()
                .operation("execution")
                .execution(execution)
                .documentId("document-456")
                .processVariableResolver(name -> "secret-runtime-value")
                .build();

        assertThatThrownBy(() -> config.filename().resolve(mappingService, context))
                .isInstanceOf(GenerateDocumentExpressionException.class)
                .hasMessageContaining("epistola-generate-document v1")
                .hasMessageContaining("field 'filename'")
                .hasMessageContaining("expression='\"value.pdf\"'")
                .hasMessageContaining("operation=execution")
                .hasMessageContaining("processDefinitionId=letters:3:definition")
                .hasMessageContaining("processInstanceId=process-123")
                .hasMessageContaining("activityId=generate-letter")
                .hasMessageContaining("documentId=document-456")
                .hasMessageContaining("Syntax error")
                .hasMessageNotContaining("secret-runtime-value")
                .hasCause(parserFailure);
    }

    @Test
    void dataMappingRuntimeErrorIdentifiesFieldAndPreviewContext() {
        var config = GenerateDocumentActionConfigurationRegistry.parse(properties(
                1,
                "\"value.pdf\"",
                null,
                null));
        JsonataMappingService mappingService = mock(JsonataMappingService.class);
        RuntimeException evaluationFailure = new RuntimeException("Custom function failed");
        when(mappingService.evaluate(org.mockito.ArgumentMatchers.any()))
                .thenThrow(evaluationFailure);
        EvaluationContext context = EvaluationContext.builder()
                .operation("preview")
                .processDefinitionId("letters:3:definition")
                .processInstanceId("process-123")
                .activityId("generate-letter")
                .documentId("document-456")
                .build();

        assertThatThrownBy(() -> config.evaluateDataMapping(mappingService, context))
                .isInstanceOf(GenerateDocumentExpressionException.class)
                .hasMessageContaining("field 'dataMapping'")
                .hasMessageContaining("operation=preview")
                .hasMessageContaining("processDefinitionId=letters:3:definition")
                .hasMessageContaining("activityId=generate-letter")
                .hasCause(evaluationFailure);
    }

    @Test
    void variantAttributeRuntimeErrorUsesTheAttributeKeyAsFieldPath() {
        var config = GenerateDocumentActionConfigurationRegistry.parse(properties(
                1,
                "\"value.pdf\"",
                null,
                List.of(Map.of(
                        "key", "language",
                        "value", "$pv.language",
                        "required", true))));
        JsonataMappingService mappingService = mock(JsonataMappingService.class);
        when(mappingService.evaluateScalar(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("Evaluation failed"));

        assertThatThrownBy(() -> config.variantAttributes().getFirst().value().resolve(
                mappingService,
                EvaluationContext.builder().operation("execution").build()))
                .isInstanceOf(GenerateDocumentExpressionException.class)
                .hasMessageContaining("field 'variantAttributes.language'")
                .hasMessageContaining("expression='$pv.language'");
    }

    private static void assertMissingRequired(
            int version,
            String field,
            GenerateDocumentActionProperties properties
    ) {
        assertThatThrownBy(() -> GenerateDocumentActionConfigurationRegistry.parse(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configuration v" + version)
                .hasMessageContaining(field);
    }

    private static GenerateDocumentActionProperties propertiesMissing(int version, String field) {
        return new GenerateDocumentActionProperties(
                version,
                field.equals("catalogId") ? " " : "catalog",
                field.equals("templateId") ? null : "template",
                null,
                null,
                null,
                field.equals("dataMapping") ? "" : "{}",
                field.equals("outputFormat") ? " " : version == 0 ? "PDF" : "\"PDF\"",
                field.equals("filename") ? null : version == 0 ? "value.pdf" : "\"value.pdf\"",
                null,
                field.equals("resultProcessVariable") ? "" : "result");
    }

    private static GenerateDocumentActionProperties properties(
            Integer version,
            String filename,
            String variantId,
            Object variantAttributes
    ) {
        return new GenerateDocumentActionProperties(
                version,
                "catalog",
                "template",
                variantId,
                variantAttributes,
                null,
                "{}",
                version == null || version == 0 ? "PDF" : "\"PDF\"",
                filename,
                version == null || version == 0 ? null : "$pv.correlationId",
                "result");
    }

    private record V0ScalarCompatibilityFixture(String name, String value, String interpretation) {}
}
