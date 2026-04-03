package app.epistola.valtimo.service;

import app.epistola.valtimo.expression.DefaultExpressionContext;
import app.epistola.valtimo.expression.ExpressionContext;
import app.epistola.valtimo.expression.ExpressionResolver;
import com.ritense.valueresolver.ValueResolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.delegate.DelegateExecution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves value expressions in nested data mappings.
 * <p>
 * Supports three types of values:
 * <ul>
 *   <li>Standard prefixes ({@code doc:}, {@code pv:}, {@code case:}, {@code template:}) — batch-resolved via ValueResolverService</li>
 *   <li>Expression functions ({@code expr:functionName(...)}) — evaluated via {@link ExpressionResolver}</li>
 *   <li>Literal values — passed through as-is</li>
 * </ul>
 * <p>
 * Resolution happens in three passes:
 * <ol>
 *   <li>Collect and batch-resolve standard prefix values</li>
 *   <li>Build partially-resolved tree</li>
 *   <li>Evaluate {@code expr:} values with access to the already-resolved data</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public class DataMappingResolverService {

    private final ValueResolverService valueResolverService;
    private final ExpressionResolver expressionResolver;

    /**
     * Resolve all value expressions in a nested data mapping.
     *
     * @param documentId  the Valtimo document instance ID to resolve doc:/case: expressions
     * @param dataMapping the nested data mapping with value resolver expressions
     * @return a new map with all expressions replaced by their resolved values
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveMapping(String documentId, Map<String, Object> dataMapping) {
        if (dataMapping == null || dataMapping.isEmpty()) {
            return Map.of();
        }

        // Pass 1: collect and batch-resolve standard prefix values
        List<String> valuesToResolve = new ArrayList<>();
        collectResolvableValues(dataMapping, valuesToResolve);

        Map<String, Object> resolvedValues = valuesToResolve.isEmpty()
                ? Map.of()
                : valueResolverService.resolveValues(documentId, valuesToResolve);

        // Pass 2: build partially-resolved tree
        Map<String, Object> partialResult = applyResolvedValues(dataMapping, resolvedValues);

        // Pass 3: evaluate expr: values
        if (hasExpressionValues(dataMapping)) {
            ExpressionContext ctx = new DefaultExpressionContext(
                    null, documentId, collectDocValues(resolvedValues), Map.of(), partialResult);
            return resolveExpressions(partialResult, ctx);
        }
        return partialResult;
    }

    /**
     * Resolve all value expressions in a nested data mapping using a DelegateExecution context.
     *
     * @param execution   the process execution context
     * @param dataMapping the nested data mapping with value resolver expressions
     * @return a new map with all expressions replaced by their resolved values
     */
    public Map<String, Object> resolveMapping(DelegateExecution execution, Map<String, Object> dataMapping) {
        if (dataMapping == null || dataMapping.isEmpty()) {
            return Map.of();
        }

        // Pass 1: collect and batch-resolve standard prefix values
        List<String> valuesToResolve = new ArrayList<>();
        collectResolvableValues(dataMapping, valuesToResolve);

        Map<String, Object> resolvedValues = valuesToResolve.isEmpty()
                ? Map.of()
                : valueResolverService.resolveValues(
                        execution.getProcessInstanceId(),
                        execution,
                        valuesToResolve
                );

        // Pass 2: build partially-resolved tree
        Map<String, Object> partialResult = applyResolvedValues(dataMapping, resolvedValues);

        // Pass 3: evaluate expr: values
        if (hasExpressionValues(dataMapping)) {
            Map<String, Object> processVars = new LinkedHashMap<>();
            for (var entry : execution.getVariables().entrySet()) {
                processVars.put(entry.getKey(), entry.getValue());
            }
            String documentId = execution.getBusinessKey();
            ExpressionContext ctx = new DefaultExpressionContext(
                    execution, documentId, collectDocValues(resolvedValues), processVars, partialResult);
            return resolveExpressions(partialResult, ctx);
        }
        return partialResult;
    }

    @SuppressWarnings("unchecked")
    private void collectResolvableValues(Map<String, Object> mapping, List<String> valuesToResolve) {
        for (Object value : mapping.values()) {
            if (value instanceof String str && isResolvableValue(str)) {
                valuesToResolve.add(str);
            } else if (DataMappingResolver.isArrayFieldMapping(value)) {
                Map<String, Object> arrayMapping = (Map<String, Object>) value;
                Object source = arrayMapping.get(DataMappingResolver.ARRAY_SOURCE_KEY);
                if (source instanceof String str && isResolvableValue(str)) {
                    valuesToResolve.add(str);
                }
            } else if (value instanceof Map<?, ?> nested) {
                collectResolvableValues((Map<String, Object>) nested, valuesToResolve);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyResolvedValues(Map<String, Object> mapping, Map<String, Object> resolvedValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : mapping.entrySet()) {
            Object value = entry.getValue();
            if (DataMappingResolver.isArrayFieldMapping(value)) {
                result.put(entry.getKey(), resolveArrayFieldMapping((Map<String, Object>) value, resolvedValues));
            } else if (value instanceof Map<?, ?> nested) {
                result.put(entry.getKey(), applyResolvedValues((Map<String, Object>) nested, resolvedValues));
            } else if (value instanceof String str && isResolvableValue(str)) {
                result.put(entry.getKey(), resolvedValues.get(str));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object resolveArrayFieldMapping(Map<String, Object> arrayMapping, Map<String, Object> resolvedValues) {
        String sourceExpression = (String) arrayMapping.get(DataMappingResolver.ARRAY_SOURCE_KEY);
        Object resolvedSource = (sourceExpression != null && isResolvableValue(sourceExpression))
                ? resolvedValues.get(sourceExpression)
                : sourceExpression;

        Map<String, String> fieldMappings = new LinkedHashMap<>();
        for (var entry : arrayMapping.entrySet()) {
            if (!DataMappingResolver.ARRAY_SOURCE_KEY.equals(entry.getKey()) && entry.getValue() instanceof String str) {
                fieldMappings.put(entry.getKey(), str);
            }
        }

        if (resolvedSource instanceof List<?> sourceList) {
            return DataMappingResolver.mapArrayItems(sourceList, fieldMappings);
        }

        log.warn("Array field mapping _source did not resolve to a list: {}", resolvedSource);
        return resolvedSource;
    }

    /**
     * Pass 3: walk the partially-resolved tree and evaluate any remaining expr: values.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveExpressions(Map<String, Object> mapping, ExpressionContext ctx) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : mapping.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String str && ExpressionResolver.isExpressionValue(str)) {
                try {
                    result.put(entry.getKey(), expressionResolver.resolve(str, ctx));
                } catch (Exception e) {
                    log.warn("Failed to evaluate expression '{}' for key '{}': {}",
                            str, entry.getKey(), e.getMessage());
                    result.put(entry.getKey(), null);
                }
            } else if (value instanceof Map<?, ?> nested) {
                result.put(entry.getKey(), resolveExpressions((Map<String, Object>) nested, ctx));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    /**
     * Check if the mapping tree contains any expr: values.
     */
    @SuppressWarnings("unchecked")
    private boolean hasExpressionValues(Map<String, Object> mapping) {
        for (Object value : mapping.values()) {
            if (value instanceof String str && ExpressionResolver.isExpressionValue(str)) {
                return true;
            } else if (value instanceof Map<?, ?> nested) {
                if (hasExpressionValues((Map<String, Object>) nested)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Extract resolved doc: and case: values into a flat map for the expression context.
     */
    private Map<String, Object> collectDocValues(Map<String, Object> resolvedValues) {
        Map<String, Object> docValues = new LinkedHashMap<>();
        for (var entry : resolvedValues.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("doc:") || key.startsWith("case:")) {
                // Strip prefix for cleaner access: doc:customer.name → customer.name
                String cleanKey = key.substring(key.indexOf(':') + 1);
                docValues.put(cleanKey, entry.getValue());
            }
        }
        return docValues;
    }

    private boolean isResolvableValue(String value) {
        return value != null && (
                value.startsWith("doc:") ||
                value.startsWith("case:") ||
                value.startsWith("pv:") ||
                value.startsWith("template:")
        );
    }
}
