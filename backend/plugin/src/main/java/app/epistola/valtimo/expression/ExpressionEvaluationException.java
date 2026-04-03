package app.epistola.valtimo.expression;

/**
 * Thrown when an {@code expr:} expression cannot be parsed, matched, or evaluated.
 */
public class ExpressionEvaluationException extends RuntimeException {

    public ExpressionEvaluationException(String message) {
        super(message);
    }

    public ExpressionEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
