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
package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.expression.ExpressionFunctionRegistry;
import app.epistola.valtimo.expression.ExpressionFunctionInfo;
import app.epistola.valtimo.service.preview.ProcessLinkMappingService;
import app.epistola.valtimo.service.suggestion.ProcessVariableDiscoveryService;
import app.epistola.valtimo.service.suggestion.VariableSuggestionService;
import app.epistola.valtimo.web.rest.dto.JsonataValidationResult;
import app.epistola.valtimo.web.rest.dto.ProcessLinkMappingResponse;
import app.epistola.valtimo.web.rest.dto.ValidateJsonataRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EpistolaToolingResourceValidateJsonataTest {

    private EpistolaToolingResource resource;
    private ProcessLinkMappingService processLinkMappingService;

    @BeforeEach
    void setUp() {
        processLinkMappingService = mock(ProcessLinkMappingService.class);
        resource = new EpistolaToolingResource(
                mock(ProcessVariableDiscoveryService.class),
                mock(VariableSuggestionService.class),
                new ExpressionFunctionRegistry(List.of()),
                processLinkMappingService);
    }

    @Test
    void processLinkMappingReturnsResolvedDataMapping() {
        when(processLinkMappingService.getDataMapping("my-process", "Activity_1"))
                .thenReturn("{ \"name\": $doc.customer.name }");

        ResponseEntity<ProcessLinkMappingResponse> response =
                resource.getProcessLinkMapping("my-process", "Activity_1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().dataMapping()).isEqualTo("{ \"name\": $doc.customer.name }");
    }

    @Test
    void processLinkMappingReturnsEmptyWhenUnresolved() {
        when(processLinkMappingService.getDataMapping("my-process", "Activity_1")).thenReturn("");

        var body = resource.getProcessLinkMapping("my-process", "Activity_1").getBody();

        assertThat(body).isNotNull();
        assertThat(body.dataMapping()).isEmpty();
    }

    @Test
    void expressionFunctionsEndpointReturnsExtendedMetadata() throws Exception {
        var registry = mock(ExpressionFunctionRegistry.class);
        var expected = new ExpressionFunctionInfo(
                "person",
                "Returns a person",
                List.of(new ExpressionFunctionInfo.OverloadInfo(
                        List.of(),
                        "Map",
                        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("type", "object"),
                        null
                ))
        );
        resource = new EpistolaToolingResource(
                mock(ProcessVariableDiscoveryService.class),
                mock(VariableSuggestionService.class),
                registry,
                processLinkMappingService);
        when(registry.listFunctions()).thenReturn(List.of(expected));

        var mockMvc = MockMvcBuilders.standaloneSetup(resource)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();

        mockMvc.perform(get("/api/v1/plugin/epistola/expression-functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("person"))
                .andExpect(jsonPath("$[0].overloads[0].returnType").value("Map"))
                .andExpect(jsonPath("$[0].overloads[0].resultSchema.type").value("object"))
                .andExpect(jsonPath("$[0].overloads[0].schemaDiagnostic").doesNotExist());
    }

    @Test
    void allValidExpressionsReturnSuccess() {
        var request = new ValidateJsonataRequest(
                "{ \"name\": $doc.customer.name }",
                "\"PDF\"",
                "\"besluit-\" & $doc.lastName & \".pdf\"",
                "$pv.variantId",
                "$pv.environmentId",
                "$pv.correlationId",
                Map.of("color", "$pv.color"));

        ResponseEntity<JsonataValidationResult> response = resource.validateJsonata(request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().valid()).isTrue();
        assertThat(response.getBody().errors()).isEmpty();
    }

    @Test
    void blankAndNullExpressionsAreSkipped() {
        var request = new ValidateJsonataRequest(null, null, "  ", null, null, null, null);

        var body = resource.validateJsonata(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.valid()).isTrue();
        assertThat(body.errors()).isEmpty();
    }

    @Test
    void dataMappingSyntaxErrorIsReported() {
        // Missing closing brace
        var request = new ValidateJsonataRequest(
                "{ \"x\": $pv.foo", null, null, null, null, null, null);

        var body = resource.validateJsonata(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.valid()).isFalse();
        assertThat(body.errors()).hasSize(1);
        assertThat(body.errors().get(0).field()).isEqualTo("dataMapping");
        assertThat(body.errors().get(0).expression()).isEqualTo("{ \"x\": $pv.foo");
        assertThat(body.errors().get(0).message()).isNotBlank();
    }

    @Test
    void filenameSyntaxErrorIsReported() {
        var request = new ValidateJsonataRequest(
                null, null, "$pv.foo &", null, null, null, null);

        var body = resource.validateJsonata(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.valid()).isFalse();
        assertThat(body.errors()).hasSize(1);
        assertThat(body.errors().get(0).field()).isEqualTo("filename");
    }

    @Test
    void environmentIdSyntaxErrorIsReported() {
        var request = new ValidateJsonataRequest(
                null, null, null, null, "$pv.environment &", null, null);

        var body = resource.validateJsonata(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.valid()).isFalse();
        assertThat(body.errors()).hasSize(1);
        assertThat(body.errors().get(0).field()).isEqualTo("environmentId");
    }

    @Test
    void variantAttributeErrorsUseCompositeFieldName() {
        var request = new ValidateJsonataRequest(
                null, null, null, null, null, null,
                Map.of("color", "$pv.color &"));  // syntax error

        var body = resource.validateJsonata(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.valid()).isFalse();
        assertThat(body.errors()).hasSize(1);
        assertThat(body.errors().get(0).field()).isEqualTo("variantAttributes.color");
    }

    @Test
    void multipleFieldErrorsAreAllReported() {
        var request = new ValidateJsonataRequest(
                "{ broken",            // dataMapping invalid
                null,
                "$pv.foo &",           // filename invalid
                null,
                null,
                null,
                Map.of("k", "$doc.x"));

        var body = resource.validateJsonata(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.valid()).isFalse();
        assertThat(body.errors()).extracting(JsonataValidationResult.FieldError::field)
                .containsExactlyInAnyOrder("dataMapping", "filename");
    }
}
