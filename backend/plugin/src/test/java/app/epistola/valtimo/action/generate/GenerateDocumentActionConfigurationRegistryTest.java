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
                    null,
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
                null,
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

    @Test
    void v0_keepsAVersionRangeAsWritten_withoutItsSurroundingWhitespace() {
        var config = GenerateDocumentActionConfigurationRegistry.parse(
                withVersionRange(properties(0, "value.pdf", null, null), " ^1.4.0 "));

        assertThat(config.literalVersionRange()).contains("^1.4.0");
        assertThat(config.resolveVersionRange(null, null)).isEqualTo("^1.4.0");
    }

    @Test
    void v1_aVersionRangeIsJsonata_aFixedOneAStringLiteral() {
        var config = GenerateDocumentActionConfigurationRegistry.parse(
                withVersionRange(properties(1, "\"value.pdf\"", null, null), " \"^1.4.0\" "));

        assertThat(config.literalVersionRange()).contains("^1.4.0");
        assertThat(config.resolveVersionRange(mappingService(), context(Map.of()))).isEqualTo("^1.4.0");
    }

    @Test
    void v1_refusesABareVersionRange_namingTheLiteralToWriteInstead() {
        // Saved before the range became an expression (1.0.0-alpha.1/beta.1): not JSONata, so not guessed at.
        var bare = withVersionRange(properties(1, "\"value.pdf\"", null, null), "^1.4.0");

        assertThatThrownBy(() -> GenerateDocumentActionConfigurationRegistry.parse(bare))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("versionRange must contain valid JSONata")
                .hasMessageContaining("such as \"^1.4.0\"");
    }

    @Test
    void aBlankVersionRangeIsNoRange() {
        for (int version : List.of(0, 1)) {
            var config = GenerateDocumentActionConfigurationRegistry.parse(
                    withVersionRange(properties(version, version == 0 ? "value.pdf" : "\"value.pdf\"", null, null), "  "));

            assertThat(config.literalVersionRange()).as("v" + version).isEmpty();
            assertThat(config.resolveVersionRange(mappingService(), context(Map.of()))).as("v" + version).isNull();
        }
    }

    @Test
    void rejectsAVersionRangeTheContractDoesNotAccept_whenItIsALiteral() {
        // Refused when the link is read, rather than rendering whatever a typo happens to match.
        for (int version : List.of(0, 1)) {
            var invalid = withVersionRange(
                    properties(version, version == 0 ? "value.pdf" : "\"value.pdf\"", null, null),
                    version == 0 ? "1.x.y.z" : "\"1.x.y.z\"");

            assertThatThrownBy(() -> GenerateDocumentActionConfigurationRegistry.parse(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("configuration v" + version)
                    .hasMessageContaining("versionRange: Invalid version range '1.x.y.z'");
        }
    }

    @Test
    void anExpressionRange_isOnlyKnownAtRunTime_andEvaluatesToTheRangeItProduces() {
        var config = GenerateDocumentActionConfigurationRegistry.parse(
                withVersionRange(properties(1, "\"value.pdf\"", null, null), "$pv.briefversie"));

        assertThat(config.literalVersionRange()).isEmpty();
        assertThat(config.resolveVersionRange(mappingService(), context(Map.of("briefversie", " ^2.0.0 "))))
                .isEqualTo("^2.0.0");
    }

    @Test
    void anExpressionThatProducesABlankString_isDeliberatelyNoRange() {
        var config = GenerateDocumentActionConfigurationRegistry.parse(
                withVersionRange(properties(1, "\"value.pdf\"", null, null), "$pv.briefversie"));

        assertThat(config.resolveVersionRange(mappingService(), context(Map.of("briefversie", " ")))).isNull();
    }

    @Test
    void anExpressionThatProducesNothing_isRefused_notReadAsNoRange() {
        // A missing variable or a bare name: never a choice (ADR 0009, second addendum).
        var config = GenerateDocumentActionConfigurationRegistry.parse(
                withVersionRange(properties(1, "\"value.pdf\"", null, null), "$pv.briefversie"));

        assertThatThrownBy(() -> config.resolveVersionRange(mappingService(), context(Map.of())))
                .isInstanceOfSatisfying(GenerateDocumentExpressionException.class, e ->
                        assertThat(e.getKind()).isEqualTo(GenerateDocumentExpressionException.Kind.NO_VALUE))
                .hasMessageContaining("field 'versionRange'")
                .hasMessageContaining("produced no value")
                .hasMessageContaining("Write \"\" for no range");
    }

    // ── The environment: none configured, a value, "" for none, nothing refused ──

    @Test
    void noEnvironmentConfigured_isThePluginsDefault() {
        var config = GenerateDocumentActionConfigurationRegistry.parse(properties(1, "\"value.pdf\"", null, null));

        assertThat(config.resolveEnvironment(mappingService(), context(Map.of()), "productie")).isEqualTo("productie");
        assertThat(config.resolveEnvironment(mappingService(), context(Map.of()), null)).isNull();
    }

    @Test
    void anEnvironmentExpression_isWhatItSays_andAnEmptyStringIsDeliberatelyNone() {
        var config = withEnvironment("$pv.omgeving");

        assertThat(config.resolveEnvironment(mappingService(), context(Map.of("omgeving", " acceptatie ")), "productie"))
                .isEqualTo("acceptatie");
        assertThat(config.resolveEnvironment(mappingService(), context(Map.of("omgeving", "")), "productie"))
                .describedAs("\"\" is a choice: no environment, not the default")
                .isNull();
    }

    @Test
    void anEnvironmentExpressionProducingNothing_isRefused_ratherThanRenderedInTheDefault() {
        // The bug this closes: `someEnv` without quotes is a path to nothing, and used to fall back
        // silently to the default — another environment, unnoticed.
        var config = withEnvironment("someEnv");

        assertThatThrownBy(() -> config.resolveEnvironment(mappingService(), context(Map.of()), "productie"))
                .isInstanceOfSatisfying(GenerateDocumentExpressionException.class, e ->
                        assertThat(e.getKind()).isEqualTo(GenerateDocumentExpressionException.Kind.NO_VALUE))
                .hasMessageContaining("expression='someEnv'")
                .hasMessageContaining("?: $plugin.defaultEnvironmentId");
    }

    @Test
    void theDefaultIsThereToFallBackOnExplicitly_andIsEmptyWhenNoneIsConfigured() {
        var config = withEnvironment("$pv.omgeving ?: $plugin.defaultEnvironmentId");

        assertThat(config.resolveEnvironment(mappingService(), contextWithDefault("productie"), "productie"))
                .isEqualTo("productie");
        assertThat(config.resolveEnvironment(mappingService(), contextWithDefault(null), null))
                .describedAs("no default: the fallback is \"\", deliberately none — never a failure")
                .isNull();
    }

    @Test
    void anAuthorCanRequireAnEnvironment_withTheirOwnMessage() {
        var config = withEnvironment("$pv.omgeving ?: $plugin.defaultEnvironmentId ?: $error(\"Geen omgeving voor deze zaak\")");

        assertThatThrownBy(() -> config.resolveEnvironment(mappingService(), contextWithDefault(null), null))
                .isInstanceOfSatisfying(GenerateDocumentExpressionException.class, e ->
                        assertThat(e.getKind()).isEqualTo(GenerateDocumentExpressionException.Kind.EVALUATION_FAILED))
                .hasMessageContaining("Geen omgeving voor deze zaak");
    }

    @Test
    void anIncidentKeepsTheAuthorsErrorText_fromError() {
        var config = withEnvironment("$pv.omgeving ?: $error(\"Geen omgeving voor deze zaak\")");

        assertThatThrownBy(() -> config.resolveEnvironment(mappingService(), context(Map.of()), null))
                .isInstanceOfSatisfying(GenerateDocumentExpressionException.class, e ->
                        assertThat(e.safeDescription())
                                .contains("field 'environmentId'")
                                .endsWith("failed: Geen omgeving voor deze zaak"));
    }

    @Test
    void anIncidentNeverQuotesWhatAnExpressionReadFromTheCase() {
        // $number("Jansen") fails with `For input string: "Jansen"`: the case's value, in the message.
        var config = withEnvironment("$string($number($pv.naam))");

        assertThatThrownBy(() -> config.resolveEnvironment(mappingService(), context(Map.of("naam", "Jansen")), null))
                .isInstanceOfSatisfying(GenerateDocumentExpressionException.class, e -> {
                    assertThat(e.getMessage()).describedAs("the exception itself may carry it").contains("Jansen");
                    assertThat(e.safeDescription())
                            .describedAs("what the incident and its logs carry")
                            .contains("expression='$string($number($pv.naam))'")
                            .doesNotContain("Jansen");
                });
    }

    @Test
    void anEnvironmentThatIsNotAString_isRefused() {
        assertThatThrownBy(() -> withEnvironment("42").resolveEnvironment(mappingService(), context(Map.of()), null))
                .isInstanceOfSatisfying(GenerateDocumentExpressionException.class, e ->
                        assertThat(e.getKind()).isEqualTo(GenerateDocumentExpressionException.Kind.INVALID_RESULT));
    }

    @Test
    void aV0Link_keepsFallingBackToTheDefault() {
        var config = GenerateDocumentActionConfigurationRegistry.parse(new GenerateDocumentActionProperties(
                0, "catalog", "template", null, null, "$pv.omgeving", null, "{}", "PDF", "value.pdf", null, "result"));

        assertThat(config.resolveEnvironment(mappingService(), context(Map.of()), "productie")).isEqualTo("productie");
    }

    private static GenerateDocumentActionConfiguration withEnvironment(String environment) {
        GenerateDocumentActionProperties valid = properties(1, "\"value.pdf\"", null, null);
        return GenerateDocumentActionConfigurationRegistry.parse(new GenerateDocumentActionProperties(
                valid.actionConfigVersion(), valid.catalogId(), valid.templateId(), valid.variantId(),
                valid.variantAttributes(), environment, null, valid.dataMapping(), valid.outputFormat(),
                valid.filename(), valid.correlationId(), valid.resultProcessVariable()));
    }

    private static EvaluationContext contextWithDefault(String defaultEnvironmentId) {
        return context(Map.of()).withExtraBindings(app.epistola.valtimo.mapping.PluginBinding.of(defaultEnvironmentId));
    }

    @Test
    void anExpressionThatProducesANumber_isRefused_ratherThanReadAsADifferentRange() {
        // The bare 1.0 is the number 1, which as a range would mean 1.x rather than 1.0.x.
        var config = GenerateDocumentActionConfigurationRegistry.parse(
                withVersionRange(properties(1, "\"value.pdf\"", null, null), "1.0"));

        assertThatThrownBy(() -> config.resolveVersionRange(mappingService(), context(Map.of())))
                .isInstanceOf(GenerateDocumentExpressionException.class)
                .hasMessageContaining("field 'versionRange'")
                .hasMessageContaining("must evaluate to a string such as \"^1.4.0\", not a number");
    }

    @Test
    void anExpressionThatProducesWhatIsNotARange_isRefused_withoutRepeatingTheValue() {
        // The value may come from the case; the field and the expression are what diagnose it.
        var config = GenerateDocumentActionConfigurationRegistry.parse(
                withVersionRange(properties(1, "\"value.pdf\"", null, null), "$pv.briefversie"));

        assertThatThrownBy(() -> config.resolveVersionRange(mappingService(), context(Map.of("briefversie", "Jansen"))))
                .isInstanceOf(GenerateDocumentExpressionException.class)
                .hasMessageContaining("expression='$pv.briefversie'")
                .hasMessageContaining("is not a version range")
                .hasMessageNotContaining("Jansen");
    }

    @Test
    void readsTheVersionRangeFromStoredProperties() {
        var stored = JsonNodeFactory.instance.objectNode()
                .put("actionConfigVersion", 1)
                .put("catalogId", "local/valtimo/brieven")
                .put("templateId", "besluit")
                .put("versionRange", "\"1.9.0\"")
                .put("dataMapping", "{}")
                .put("outputFormat", "\"PDF\"")
                .put("filename", "\"besluit.pdf\"")
                .put("resultProcessVariable", "result");

        assertThat(GenerateDocumentActionConfigurationRegistry.parse(stored).literalVersionRange()).contains("1.9.0");
    }

    private static JsonataMappingService mappingService() {
        return new JsonataMappingService(new app.epistola.valtimo.expression.ExpressionFunctionRegistry(List.of()));
    }

    private static EvaluationContext context(Map<String, Object> processVariables) {
        return EvaluationContext.builder()
                .operation("execution")
                .processVariableEnumerator(() -> processVariables)
                .processVariableResolver(processVariables::get)
                .build();
    }

    private static GenerateDocumentActionProperties withVersionRange(
            GenerateDocumentActionProperties valid, String versionRange) {
        return new GenerateDocumentActionProperties(
                valid.actionConfigVersion(),
                valid.catalogId(),
                valid.templateId(),
                valid.variantId(),
                valid.variantAttributes(),
                valid.environmentId(),
                versionRange,
                valid.dataMapping(),
                valid.outputFormat(),
                valid.filename(),
                valid.correlationId(),
                valid.resultProcessVariable());
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
                null,
                "{}",
                version == null || version == 0 ? "PDF" : "\"PDF\"",
                filename,
                version == null || version == 0 ? null : "$pv.correlationId",
                "result");
    }

    private record V0ScalarCompatibilityFixture(String name, String value, String interpretation) {}
}
