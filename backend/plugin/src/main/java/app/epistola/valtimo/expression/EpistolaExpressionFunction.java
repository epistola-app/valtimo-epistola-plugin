package app.epistola.valtimo.expression;

/**
 * Marker interface for Spring beans that are callable from {@code expr:} expressions
 * in Epistola data mappings.
 * <p>
 * Implementations define one or more {@code execute(ExpressionContext, ...)} methods
 * with typed parameters. The framework discovers these via reflection and matches
 * the best overload at runtime based on argument types.
 * <p>
 * <b>Convention:</b>
 * <ul>
 *   <li>Method name must be {@code execute}</li>
 *   <li>First parameter must be {@link ExpressionContext}</li>
 *   <li>Remaining parameters are the user-provided arguments (typed)</li>
 *   <li>Multiple overloads are supported</li>
 *   <li>Return type is the expression result</li>
 * </ul>
 * <p>
 * Example usage in a data mapping value:
 * <pre>expr:formatDate(#doc['invoice.date'], 'dd-MM-yyyy')</pre>
 * <p>
 * Example implementation:
 * <pre>
 * &#64;Component
 * public class FormatDateFunction implements EpistolaExpressionFunction {
 *     public String name() { return "formatDate"; }
 *     public String description() { return "Format a date to a string pattern"; }
 *
 *     public String execute(ExpressionContext ctx, LocalDate date, String pattern) {
 *         return date.format(DateTimeFormatter.ofPattern(pattern));
 *     }
 * }
 * </pre>
 */
public interface EpistolaExpressionFunction {

    /**
     * The identifier used in {@code expr:} expressions.
     * Must be a valid Java identifier (e.g., "formatDate", "str").
     */
    String name();

    /**
     * A human-readable description shown in the frontend UI.
     */
    String description();
}
