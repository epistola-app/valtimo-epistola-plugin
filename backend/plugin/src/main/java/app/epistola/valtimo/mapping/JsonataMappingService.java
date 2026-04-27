package app.epistola.valtimo.mapping;

import app.epistola.valtimo.expression.ExpressionFunctionRegistry;
import com.dashjoin.jsonata.Jsonata;
import com.dashjoin.jsonata.Jsonata.Frame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.delegate.DelegateExecution;

import java.util.Map;
import java.util.function.Supplier;

import static com.dashjoin.jsonata.Jsonata.jsonata;

/**
 * Evaluates JSONata expressions to produce template data payloads.
 * <p>
 * Binds three context variables:
 * <ul>
 *   <li>{@code $doc} — Valtimo document data</li>
 *   <li>{@code $pv} — process variables</li>
 *   <li>{@code $case} — case data</li>
 * </ul>
 * Custom functions from {@link ExpressionFunctionRegistry} are registered as JSONata functions.
 */
@Slf4j
@RequiredArgsConstructor
public class JsonataMappingService {

    private static final long TIMEOUT_MS = 5000;
    private static final int MAX_RECURSION_DEPTH = 100;

    private final ExpressionFunctionRegistry functionRegistry;

    /**
     * Evaluate a JSONata expression with explicit data context.
     *
     * @param expression       the JSONata expression
     * @param documentData     Valtimo document data (bound as $doc)
     * @param processVariables process variables (bound as $pv)
     * @param caseData         case data (bound as $case)
     * @return the evaluated result as a Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> evaluate(
            String expression,
            Map<String, Object> documentData,
            Map<String, Object> processVariables,
            Map<String, Object> caseData
    ) {
        if (expression == null || expression.isBlank()) {
            return Map.of();
        }

        Jsonata jsonataExpr = jsonata(expression);
        Frame frame = jsonataExpr.createFrame();
        frame.setRuntimeBounds(TIMEOUT_MS, MAX_RECURSION_DEPTH);

        frame.bind("doc", documentData != null ? documentData : Map.of());
        frame.bind("pv", processVariables != null ? processVariables : Map.of());
        frame.bind("case", caseData != null ? caseData : Map.of());

        registerCustomFunctions(frame);

        Object result = jsonataExpr.evaluate(Map.of(), frame);
        if (result instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        throw new IllegalStateException(
                "JSONata expression must return an object, but got: " +
                        (result == null ? "null" : result.getClass().getSimpleName()));
    }

    /**
     * Evaluate a JSONata expression using a DelegateExecution context.
     * Process variables are resolved lazily per-access (traverses parent scopes).
     * Document data is loaded lazily on first $doc access.
     *
     * @param expression      the JSONata expression
     * @param documentLoader  loads document content on demand
     * @param execution       the Operaton execution context (for $pv)
     * @return the evaluated result as a Map
     */
    public Map<String, Object> evaluate(
            String expression,
            java.util.function.Supplier<Map<String, Object>> documentLoader,
            DelegateExecution execution
    ) {
        Map<String, Object> lazyDoc = new LazyDocumentMap(documentLoader);
        Map<String, Object> lazyPv = new LazyProcessVariableMap(execution::getVariable);
        return evaluate(expression, lazyDoc, lazyPv, Map.of());
    }

    private void registerCustomFunctions(Frame frame) {
        for (var funcInfo : functionRegistry.listFunctions()) {
            String name = funcInfo.name();
            var registeredFunc = functionRegistry.getFunction(name);
            if (registeredFunc == null) {
                continue;
            }

            // Wrap our expression function as a JSONata JFunctionCallable
            Jsonata.JFunctionCallable callable = (input, args) -> {
                Object[] argsArray = args != null ? args.toArray() : new Object[0];
                try {
                    var match = functionRegistry.findMatchingOverload(name, argsArray);
                    // Build full args array: [ExpressionContext, arg1, arg2, ...]
                    Object[] fullArgs = new Object[argsArray.length + 1];
                    fullArgs[0] = null; // ExpressionContext not available in JSONata mode
                    System.arraycopy(argsArray, 0, fullArgs, 1, argsArray.length);
                    return match.method().invoke(match.bean(), fullArgs);
                } catch (Exception e) {
                    log.warn("Custom function '{}' failed: {}", name, e.getMessage());
                    return null;
                }
            };
            Jsonata.JFunction jFunc = new Jsonata.JFunction(callable, name);
            // Disable signature validation — our functions handle their own type checking
            try {
                var sigField = Jsonata.JFunction.class.getDeclaredField("signature");
                sigField.setAccessible(true);
                sigField.set(jFunc, null);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                log.debug("Could not disable signature validation for function '{}': {}", name, e.getMessage());
            }
            frame.bind(name, jFunc);
        }
    }
}
