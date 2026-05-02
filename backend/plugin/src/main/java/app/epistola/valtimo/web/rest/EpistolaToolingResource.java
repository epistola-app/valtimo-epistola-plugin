package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.expression.ExpressionFunctionInfo;
import app.epistola.valtimo.expression.ExpressionFunctionRegistry;
import app.epistola.valtimo.service.suggestion.ProcessVariableDiscoveryService;
import app.epistola.valtimo.service.suggestion.VariableSuggestionService;
import app.epistola.valtimo.web.rest.dto.JsonataValidationResult;
import app.epistola.valtimo.web.rest.dto.ValidateJsonataRequest;
import com.dashjoin.jsonata.Jsonata;
import com.ritense.valtimo.contract.annotation.SkipComponentScan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Epistola tooling and suggestion operations.
 * Provides endpoints for process variable discovery, variable suggestions, and expression functions.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/plugin/epistola")
@SkipComponentScan
@RequiredArgsConstructor
public class EpistolaToolingResource {

    private final ProcessVariableDiscoveryService processVariableDiscoveryService;
    private final VariableSuggestionService variableSuggestionService;
    private final ExpressionFunctionRegistry expressionFunctionRegistry;

    /**
     * Discover process variable names for a given process definition.
     * Combines variables from historic process instances and BPMN model definitions.
     *
     * @param processDefinitionKey The process definition key
     * @return Sorted list of discovered variable names
     */
    @GetMapping("/process-variables")
    public ResponseEntity<List<String>> getProcessVariables(
            @RequestParam("processDefinitionKey") String processDefinitionKey
    ) {
        log.debug("Discovering process variables for process definition: {}", processDefinitionKey);

        List<String> variables = processVariableDiscoveryService.discoverVariables(processDefinitionKey);
        return ResponseEntity.ok(variables);
    }

    /**
     * Get all available variable suggestions for autocompletion in JSONata expressions.
     * Returns document fields (from JSON Schema) and process variables grouped by source.
     *
     * @param caseDefinitionKey    The case definition key (for document schema)
     * @param processDefinitionKey The process definition key (for process variables)
     * @return Variable paths grouped by source ($doc, $pv)
     */
    @GetMapping("/variable-suggestions")
    public ResponseEntity<VariableSuggestionService.VariableSuggestions> getVariableSuggestions(
            @RequestParam(value = "caseDefinitionKey", required = false) String caseDefinitionKey,
            @RequestParam(value = "processDefinitionKey", required = false) String processDefinitionKey
    ) {
        log.debug("Fetching variable suggestions for case={}, process={}", caseDefinitionKey, processDefinitionKey);
        return ResponseEntity.ok(variableSuggestionService.getSuggestions(caseDefinitionKey, processDefinitionKey));
    }

    /**
     * List all available expression functions that can be used in JSONata expressions.
     *
     * @return List of expression functions with their overload signatures
     */
    @GetMapping("/expression-functions")
    public ResponseEntity<List<ExpressionFunctionInfo>> getExpressionFunctions() {
        return ResponseEntity.ok(expressionFunctionRegistry.listFunctions());
    }

    /**
     * Validate the JSONata syntax of action-config expressions at save time.
     * Parse-only — does not evaluate, so missing variables and runtime type errors
     * are not detected here.
     */
    @PostMapping("/validate-jsonata")
    public ResponseEntity<JsonataValidationResult> validateJsonata(
            @RequestBody ValidateJsonataRequest request
    ) {
        List<JsonataValidationResult.FieldError> errors = new ArrayList<>();
        validate("dataMapping", request.dataMapping(), errors);
        validate("filename", request.filename(), errors);
        validate("variantId", request.variantId(), errors);
        if (request.variantAttributeValues() != null) {
            for (Map.Entry<String, String> entry : request.variantAttributeValues().entrySet()) {
                validate("variantAttributes." + entry.getKey(), entry.getValue(), errors);
            }
        }
        return ResponseEntity.ok(new JsonataValidationResult(errors.isEmpty(), errors));
    }

    private void validate(String field, String expression, List<JsonataValidationResult.FieldError> errors) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        try {
            Jsonata.jsonata(expression);
        } catch (Exception e) {
            errors.add(new JsonataValidationResult.FieldError(field, expression, e.getMessage()));
        }
    }
}
