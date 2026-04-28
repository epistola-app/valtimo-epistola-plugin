package app.epistola.valtimo.web.rest.dto;

import java.util.Map;

/**
 * Result of evaluating a JSONata data mapping expression.
 */
public record EvaluationResult(
        boolean success,
        Map<String, Object> result,
        String error
) {
    public static EvaluationResult success(Map<String, Object> result) {
        return new EvaluationResult(true, result, null);
    }

    public static EvaluationResult failure(String error) {
        return new EvaluationResult(false, null, error);
    }
}
