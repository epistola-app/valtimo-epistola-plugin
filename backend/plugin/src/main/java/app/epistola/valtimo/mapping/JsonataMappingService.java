package app.epistola.valtimo.mapping;

import app.epistola.valtimo.expression.DefaultExpressionContext;
import app.epistola.valtimo.expression.ExpressionContext;
import app.epistola.valtimo.expression.ExpressionFunctionRegistry;
import com.dashjoin.jsonata.Jsonata;
import com.dashjoin.jsonata.Jsonata.Frame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

import static com.dashjoin.jsonata.Jsonata.jsonata;

/**
 * Evaluates JSONata expressions to produce template data payloads.
 * <p>
 * Accepts an {@link EvaluationContext} that provides delegates for resolving
 * data lazily. Binds {@code $doc} and {@code $pv} as lazy maps, and passes
 * a fully populated {@link ExpressionContext} to custom functions.
 */
@Slf4j
@RequiredArgsConstructor
public class JsonataMappingService {

    private static final long TIMEOUT_MS = 5000;
    private static final int MAX_RECURSION_DEPTH = 100;


    private final ExpressionFunctionRegistry functionRegistry;

    /**
     * Evaluate a JSONata expression that returns an object (for data mapping).
     *
     * @param ctx the evaluation context with expression and resolvers
     * @return the evaluated result as a Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> evaluate(EvaluationContext ctx) {
        String expression = ctx.getExpression();
        if (expression == null || expression.isBlank()) {
            return Map.of();
        }

        Frame frame = buildFrame(ctx);
        Jsonata jsonataExpr = jsonata(expression);
        Object result = jsonataExpr.evaluate(Map.of(), frame);
        if (result instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        throw new IllegalStateException(
                "JSONata expression must return an object, but got: " +
                        (result == null ? "null" : result.getClass().getSimpleName()));
    }

    /**
     * Evaluate a JSONata expression that returns a scalar string.
     * Used for variantId, variant attribute values, and filename.
     *
     * @param ctx the evaluation context with expression and resolvers
     * @return the evaluated scalar result as a String, or null
     */
    public String evaluateScalar(EvaluationContext ctx) {
        String expression = ctx.getExpression();
        if (expression == null || expression.isBlank()) {
            return expression;
        }

        Frame frame = buildFrame(ctx);
        Jsonata jsonataExpr = jsonata(expression);
        Object result = jsonataExpr.evaluate(Map.of(), frame);
        return result != null ? result.toString() : null;
    }

    /**
     * Convenience method for simple evaluation without full context (e.g., tests).
     */
    public Map<String, Object> evaluate(
            String expression,
            Map<String, Object> documentData,
            Map<String, Object> processVariables,
            Map<String, Object> caseData
    ) {
        return evaluateWithMaps(expression, documentData, processVariables, caseData, null);
    }

    private Frame buildFrame(EvaluationContext ctx) {
        Map<String, Object> docMap = buildDocumentMap(ctx);
        Map<String, Object> pvMap = buildProcessVariableMap(ctx);

        ExpressionContext exprCtx = new DefaultExpressionContext(
                ctx.getExecution(),
                ctx.getDocumentId(),
                docMap,
                pvMap,
                Map.of()
        );

        String expression = ctx.getExpression();
        Jsonata jsonataExpr = jsonata(expression);
        Frame frame = jsonataExpr.createFrame();
        frame.setRuntimeBounds(TIMEOUT_MS, MAX_RECURSION_DEPTH);

        frame.bind("doc", docMap);
        frame.bind("pv", pvMap);
        frame.bind("case", Map.of());

        registerCustomFunctions(frame, exprCtx);
        return frame;
    }

    /**
     * Internal: evaluate with pre-built maps (for backward compat with tests).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> evaluateWithMaps(
            String expression,
            Map<String, Object> documentData,
            Map<String, Object> processVariables,
            Map<String, Object> caseData,
            ExpressionContext exprCtx
    ) {
        if (expression == null || expression.isBlank()) {
            return Map.of();
        }

        if (exprCtx == null) {
            exprCtx = new DefaultExpressionContext(null, null, documentData, processVariables, Map.of());
        }

        Jsonata jsonataExpr = jsonata(expression);
        Frame frame = jsonataExpr.createFrame();
        frame.setRuntimeBounds(TIMEOUT_MS, MAX_RECURSION_DEPTH);

        frame.bind("doc", documentData != null ? documentData : Map.of());
        frame.bind("pv", processVariables != null ? processVariables : Map.of());
        frame.bind("case", caseData != null ? caseData : Map.of());

        registerCustomFunctions(frame, exprCtx);

        Object result = jsonataExpr.evaluate(Map.of(), frame);
        if (result instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        throw new IllegalStateException(
                "JSONata expression must return an object, but got: " +
                        (result == null ? "null" : result.getClass().getSimpleName()));
    }

    private Map<String, Object> buildDocumentMap(EvaluationContext ctx) {
        if (ctx.getDocumentResolver() != null && ctx.getDocumentId() != null) {
            return new LazyDocumentMap(() -> ctx.getDocumentResolver().apply(ctx.getDocumentId()));
        }
        if (ctx.getDocumentResolver() != null) {
            return new LazyDocumentMap(() -> ctx.getDocumentResolver().apply(null));
        }
        return Map.of();
    }

    private Map<String, Object> buildProcessVariableMap(EvaluationContext ctx) {
        if (ctx.getProcessVariableResolver() != null) {
            return new LazyProcessVariableMap(ctx.getProcessVariableResolver());
        }
        return Map.of();
    }

    private void registerCustomFunctions(Frame frame, ExpressionContext exprCtx) {
        for (var funcInfo : functionRegistry.listFunctions()) {
            String name = funcInfo.name();
            var registeredFunc = functionRegistry.getFunction(name);
            if (registeredFunc == null) {
                continue;
            }

            Jsonata.JFunctionCallable callable = (input, args) -> {
                Object[] argsArray = args != null ? args.toArray() : new Object[0];
                try {
                    var match = functionRegistry.findMatchingOverload(name, argsArray);
                    Object[] fullArgs = new Object[argsArray.length + 1];
                    fullArgs[0] = exprCtx;
                    System.arraycopy(argsArray, 0, fullArgs, 1, argsArray.length);
                    return match.method().invoke(match.bean(), fullArgs);
                } catch (Exception e) {
                    log.warn("Custom function '{}' failed: {}", name, e.getMessage());
                    return null;
                }
            };
            Jsonata.JFunction jFunc = new Jsonata.JFunction(callable, null);
            frame.bind(name, jFunc);
        }
    }
}
