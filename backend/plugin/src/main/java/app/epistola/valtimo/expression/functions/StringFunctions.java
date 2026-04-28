package app.epistola.valtimo.expression.functions;

import app.epistola.valtimo.expression.ExpressionContext;
import app.epistola.valtimo.expression.EpistolaExpressionFunction;

/**
 * Expression function for common string operations.
 * <p>
 * Usage examples:
 * <pre>
 * expr:str(#doc['name'])                          → passthrough
 * expr:str(#doc['firstName'], ' ', #doc['lastName']) → concatenate
 * </pre>
 */
public class StringFunctions implements EpistolaExpressionFunction {

    @Override
    public String name() {
        return "str";
    }

    @Override
    public String description() {
        return "String utilities: passthrough, concatenation, upper/lower case";
    }

    /**
     * Convert a value to its string representation.
     */
    public String execute(ExpressionContext ctx, Object value) {
        return value != null ? value.toString() : "";
    }

    /**
     * Concatenate two strings.
     */
    public String execute(ExpressionContext ctx, String a, String b) {
        return (a != null ? a : "") + (b != null ? b : "");
    }

    /**
     * Concatenate three strings.
     */
    public String execute(ExpressionContext ctx, String a, String b, String c) {
        return (a != null ? a : "") + (b != null ? b : "") + (c != null ? c : "");
    }

    /**
     * Return {@code value} if non-null, otherwise return {@code fallback}.
     */
    public Object execute(ExpressionContext ctx, Object value, Object fallback) {
        return value != null ? value : fallback;
    }
}
