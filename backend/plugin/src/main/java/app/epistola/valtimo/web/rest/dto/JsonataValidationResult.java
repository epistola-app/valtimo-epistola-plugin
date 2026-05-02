package app.epistola.valtimo.web.rest.dto;

import java.util.List;

/**
 * Result of validating JSONata expressions for an action config.
 * {@code valid} is true iff {@code errors} is empty.
 */
public record JsonataValidationResult(boolean valid, List<FieldError> errors) {

    /**
     * A single field-level validation error.
     *
     * @param field      the logical field name (e.g. "dataMapping", "filename",
     *                   "variantAttributes.{key}")
     * @param expression the offending expression (echoed back so the frontend can match
     *                   it to the current form state)
     * @param message    the parser error message
     */
    public record FieldError(String field, String expression, String message) {}
}
