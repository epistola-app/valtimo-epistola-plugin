package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.expression.ExpressionFunctionRegistry;
import app.epistola.valtimo.service.suggestion.ProcessVariableDiscoveryService;
import app.epistola.valtimo.service.suggestion.VariableSuggestionService;
import app.epistola.valtimo.web.rest.dto.JsonataValidationResult;
import app.epistola.valtimo.web.rest.dto.ValidateJsonataRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EpistolaToolingResourceValidateJsonataTest {

    private EpistolaToolingResource resource;

    @BeforeEach
    void setUp() {
        resource = new EpistolaToolingResource(
                mock(ProcessVariableDiscoveryService.class),
                mock(VariableSuggestionService.class),
                new ExpressionFunctionRegistry(List.of()));
    }

    @Test
    void allValidExpressionsReturnSuccess() {
        var request = new ValidateJsonataRequest(
                "{ \"name\": $doc.customer.name }",
                "\"besluit-\" & $doc.lastName & \".pdf\"",
                "$pv.variantId",
                Map.of("color", "$pv.color"));

        ResponseEntity<JsonataValidationResult> response = resource.validateJsonata(request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().valid()).isTrue();
        assertThat(response.getBody().errors()).isEmpty();
    }

    @Test
    void blankAndNullExpressionsAreSkipped() {
        var request = new ValidateJsonataRequest(null, "  ", null, null);

        var body = resource.validateJsonata(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.valid()).isTrue();
        assertThat(body.errors()).isEmpty();
    }

    @Test
    void dataMappingSyntaxErrorIsReported() {
        // Missing closing brace
        var request = new ValidateJsonataRequest("{ \"x\": $pv.foo", null, null, null);

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
        var request = new ValidateJsonataRequest(null, "$pv.foo &", null, null);

        var body = resource.validateJsonata(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.valid()).isFalse();
        assertThat(body.errors()).hasSize(1);
        assertThat(body.errors().get(0).field()).isEqualTo("filename");
    }

    @Test
    void variantAttributeErrorsUseCompositeFieldName() {
        var request = new ValidateJsonataRequest(
                null, null, null,
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
                "$pv.foo &",           // filename invalid
                null,
                Map.of("k", "$doc.x"));

        var body = resource.validateJsonata(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.valid()).isFalse();
        assertThat(body.errors()).extracting(JsonataValidationResult.FieldError::field)
                .containsExactlyInAnyOrder("dataMapping", "filename");
    }
}
