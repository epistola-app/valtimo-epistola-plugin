package app.epistola.valtimo.expression;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.SpelParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.List;
import java.util.Map;

/**
 * Resolves {@code expr:functionName(arg1, arg2, ...)} expressions.
 * <p>
 * Parsing:
 * <ol>
 *   <li>Strips the {@code expr:} prefix</li>
 *   <li>Extracts the function name (before the first {@code (})</li>
 *   <li>Extracts the argument string (between first {@code (} and last {@code )})</li>
 *   <li>Evaluates arguments as a SpEL list expression with {@code #doc} and {@code #pv} in context</li>
 *   <li>Finds the matching overload via {@link ExpressionFunctionRegistry}</li>
 *   <li>Invokes via reflection</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public class ExpressionResolver {

    public static final String EXPRESSION_PREFIX = "expr:";

    private final ExpressionFunctionRegistry registry;
    private final SpelExpressionParser parser = new SpelExpressionParser();

    /**
     * Check if a string value is an expression.
     */
    public static boolean isExpressionValue(String value) {
        return value != null && value.startsWith(EXPRESSION_PREFIX);
    }

    /**
     * Resolve an expression value using the given context.
     *
     * @param expressionValue the full expression string including {@code expr:} prefix
     * @param context         the expression context with document data, process variables, etc.
     * @return the result of the expression evaluation
     * @throws ExpressionEvaluationException if parsing, matching, or evaluation fails
     */
    public Object resolve(String expressionValue, ExpressionContext context) {
        if (!isExpressionValue(expressionValue)) {
            throw new ExpressionEvaluationException("Value is not an expression: " + expressionValue);
        }

        String raw = expressionValue.substring(EXPRESSION_PREFIX.length()).trim();

        // Parse function name and argument string
        int parenStart = raw.indexOf('(');
        if (parenStart < 0 || !raw.endsWith(")")) {
            throw new ExpressionEvaluationException(
                    "Malformed expression: '" + expressionValue + "'. Expected format: expr:functionName(arg1, arg2, ...)");
        }

        String functionName = raw.substring(0, parenStart).trim();
        String argString = raw.substring(parenStart + 1, raw.length() - 1).trim();

        // Evaluate arguments via SpEL
        Object[] evaluatedArgs = evaluateArguments(argString, context);

        // Find matching overload and invoke
        ExpressionFunctionRegistry.MethodMatch match = registry.findMatchingOverload(functionName, evaluatedArgs);

        try {
            // Build invoke args: ExpressionContext + evaluated args
            Object[] invokeArgs = new Object[evaluatedArgs.length + 1];
            invokeArgs[0] = context;
            System.arraycopy(evaluatedArgs, 0, invokeArgs, 1, evaluatedArgs.length);

            return match.method().invoke(match.bean(), invokeArgs);
        } catch (ExpressionEvaluationException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ExpressionEvaluationException(
                    "Failed to invoke expression function '" + functionName + "': " + cause.getMessage(), cause);
        }
    }

    @SuppressWarnings("unchecked")
    private Object[] evaluateArguments(String argString, ExpressionContext context) {
        if (argString.isEmpty()) {
            return new Object[0];
        }

        // Build a safe SpEL context with #doc and #pv variables
        SimpleEvaluationContext evalContext = SimpleEvaluationContext
                .forReadOnlyDataBinding()
                .withInstanceMethods()
                .build();
        evalContext.setVariable("doc", context.getDocumentData());
        evalContext.setVariable("pv", context.getProcessVariables());

        // Evaluate the argument list as a SpEL inline list: {arg1, arg2, ...}
        // This handles nested parentheses, string literals with commas, etc. correctly.
        try {
            Expression listExpr = parser.parseExpression("{" + argString + "}");
            Object result = listExpr.getValue(evalContext);
            if (result instanceof List<?> list) {
                return list.toArray();
            }
            return new Object[]{result};
        } catch (SpelParseException e) {
            throw new ExpressionEvaluationException(
                    "Failed to parse expression arguments: '" + argString + "': " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ExpressionEvaluationException(
                    "Failed to evaluate expression arguments: '" + argString + "': " + e.getMessage(), e);
        }
    }
}
