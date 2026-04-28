package app.epistola.valtimo.web.rest.dto;

/**
 * Request to evaluate a JSONata data mapping expression against a real document.
 */
public record EvaluationRequest(
        String expression,
        String documentId,
        String processInstanceId
) {}
