package app.epistola.valtimo.expression;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry that collects all {@link EpistolaExpressionFunction} beans, discovers their
 * {@code execute(ExpressionContext, ...)} methods via reflection, and provides overload
 * matching at runtime.
 */
@Slf4j
public class ExpressionFunctionRegistry {

    private final Map<String, RegisteredFunction> functions = new LinkedHashMap<>();

    public ExpressionFunctionRegistry(List<EpistolaExpressionFunction> functionBeans) {
        for (EpistolaExpressionFunction bean : functionBeans) {
            String name = bean.name();
            if (functions.containsKey(name)) {
                log.warn("Duplicate expression function name '{}', overwriting with {}", name, bean.getClass().getName());
            }
            List<Method> executeMethods = discoverExecuteMethods(bean);
            if (executeMethods.isEmpty()) {
                log.warn("Expression function '{}' ({}) has no valid execute(ExpressionContext, ...) methods",
                        name, bean.getClass().getName());
                continue;
            }
            functions.put(name, new RegisteredFunction(bean, executeMethods));
            log.debug("Registered expression function '{}' with {} overload(s)", name, executeMethods.size());
        }
    }

    /**
     * Get a registered function by name.
     *
     * @return the registered function, or {@code null} if not found
     */
    public RegisteredFunction getFunction(String name) {
        return functions.get(name);
    }

    /**
     * List all registered functions with their overload metadata, for the REST API.
     */
    public List<ExpressionFunctionInfo> listFunctions() {
        List<ExpressionFunctionInfo> result = new ArrayList<>();
        for (var entry : functions.entrySet()) {
            RegisteredFunction rf = entry.getValue();
            EpistolaExpressionFunction bean = rf.bean();
            List<ExpressionFunctionInfo.OverloadInfo> overloads = new ArrayList<>();

            for (Method method : rf.methods()) {
                List<ExpressionFunctionInfo.ArgumentInfo> args = new ArrayList<>();
                Parameter[] params = method.getParameters();
                // Skip first parameter (ExpressionContext)
                for (int i = 1; i < params.length; i++) {
                    args.add(new ExpressionFunctionInfo.ArgumentInfo(
                            params[i].getName(),
                            params[i].getType().getSimpleName()
                    ));
                }
                overloads.add(new ExpressionFunctionInfo.OverloadInfo(
                        args,
                        method.getReturnType().getSimpleName()
                ));
            }

            result.add(new ExpressionFunctionInfo(bean.name(), bean.description(), overloads));
        }
        return result;
    }

    /**
     * Find the best matching execute() overload for the given evaluated arguments.
     *
     * @param name           the function name
     * @param evaluatedArgs  the already-evaluated arguments (excluding ExpressionContext)
     * @return the matching method
     * @throws ExpressionEvaluationException if no matching overload is found
     */
    public MethodMatch findMatchingOverload(String name, Object[] evaluatedArgs) {
        RegisteredFunction rf = functions.get(name);
        if (rf == null) {
            throw new ExpressionEvaluationException(
                    "Unknown expression function: '" + name + "'. Available functions: " + functions.keySet());
        }

        Method exactMatch = null;
        Method assignableMatch = null;

        for (Method method : rf.methods()) {
            Parameter[] params = method.getParameters();
            int expectedArgCount = params.length - 1; // exclude ExpressionContext
            if (expectedArgCount != evaluatedArgs.length) {
                continue;
            }

            boolean exact = true;
            boolean assignable = true;
            for (int i = 0; i < evaluatedArgs.length; i++) {
                Class<?> paramType = params[i + 1].getType();
                Object arg = evaluatedArgs[i];
                if (arg == null) {
                    // null is assignable to any non-primitive
                    if (paramType.isPrimitive()) {
                        assignable = false;
                        break;
                    }
                    exact = false;
                } else if (paramType.equals(arg.getClass()) || paramType.equals(toPrimitive(arg.getClass()))) {
                    // exact match
                } else if (isAssignable(paramType, arg)) {
                    exact = false;
                } else {
                    assignable = false;
                    break;
                }
            }

            if (assignable && exact) {
                exactMatch = method;
                break; // can't do better
            }
            if (assignable && assignableMatch == null) {
                assignableMatch = method;
            }
        }

        Method match = exactMatch != null ? exactMatch : assignableMatch;
        if (match == null) {
            throw new ExpressionEvaluationException(buildOverloadMismatchMessage(name, evaluatedArgs, rf));
        }
        return new MethodMatch(rf.bean(), match);
    }

    private List<Method> discoverExecuteMethods(EpistolaExpressionFunction bean) {
        return Arrays.stream(bean.getClass().getMethods())
                .filter(m -> "execute".equals(m.getName()))
                .filter(m -> m.getParameterCount() >= 1)
                .filter(m -> ExpressionContext.class.isAssignableFrom(m.getParameterTypes()[0]))
                .toList();
    }

    private boolean isAssignable(Class<?> paramType, Object arg) {
        if (paramType.isInstance(arg)) {
            return true;
        }
        // Handle primitive boxing
        Class<?> primitive = toPrimitive(arg.getClass());
        return primitive != null && paramType.equals(primitive);
    }

    private Class<?> toPrimitive(Class<?> wrapper) {
        if (wrapper == Integer.class) return int.class;
        if (wrapper == Long.class) return long.class;
        if (wrapper == Double.class) return double.class;
        if (wrapper == Float.class) return float.class;
        if (wrapper == Boolean.class) return boolean.class;
        if (wrapper == Short.class) return short.class;
        if (wrapper == Byte.class) return byte.class;
        if (wrapper == Character.class) return char.class;
        return null;
    }

    private String buildOverloadMismatchMessage(String name, Object[] args, RegisteredFunction rf) {
        StringBuilder sb = new StringBuilder();
        sb.append("No matching overload for function '").append(name).append("' with arguments [");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        sb.append("]. Available overloads:\n");
        for (Method m : rf.methods()) {
            sb.append("  - ").append(name).append("(");
            Parameter[] params = m.getParameters();
            for (int i = 1; i < params.length; i++) {
                if (i > 1) sb.append(", ");
                sb.append(params[i].getType().getSimpleName()).append(" ").append(params[i].getName());
            }
            sb.append(") → ").append(m.getReturnType().getSimpleName()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * A registered function with its bean instance and discovered execute methods.
     */
    public record RegisteredFunction(EpistolaExpressionFunction bean, List<Method> methods) {}

    /**
     * Result of overload matching: the bean to invoke and the matched method.
     */
    public record MethodMatch(EpistolaExpressionFunction bean, Method method) {}
}
