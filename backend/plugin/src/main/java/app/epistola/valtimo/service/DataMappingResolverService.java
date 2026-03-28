package app.epistola.valtimo.service;

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
 * Provides two overloads:
 * <ul>
 *   <li>{@link #resolveMapping(String, Map)} — for REST endpoints (uses documentId)</li>
 *   <li>{@link #resolveMapping(DelegateExecution, Map)} — for plugin actions (uses execution context)</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class DataMappingResolverService {

    private final ValueResolverService valueResolverService;

    /**
     * Resolve all value expressions in a nested data mapping.
     * String values with known prefixes (doc:, pv:, case:, template:) are resolved
     * via the ValueResolverService. Nested maps are traversed recursively.
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

        // First pass: collect all resolvable string values from the entire tree
        List<String> valuesToResolve = new ArrayList<>();
        collectResolvableValues(dataMapping, valuesToResolve);

        // Batch-resolve all values at once for efficiency
        // Note: the two-arg resolveValues expects a documentInstanceId, not processInstanceId
        Map<String, Object> resolvedValues = valuesToResolve.isEmpty()
                ? Map.of()
                : valueResolverService.resolveValues(documentId, valuesToResolve);

        // Second pass: build resolved tree using the batch-resolved values
        return applyResolvedValues(dataMapping, resolvedValues);
    }

    /**
     * Resolve all value expressions in a nested data mapping using a DelegateExecution context.
     * This overload is used from plugin actions where an execution is available.
     *
     * @param execution   the process execution context
     * @param dataMapping the nested data mapping with value resolver expressions
     * @return a new map with all expressions replaced by their resolved values
     */
    public Map<String, Object> resolveMapping(DelegateExecution execution, Map<String, Object> dataMapping) {
        if (dataMapping == null || dataMapping.isEmpty()) {
            return Map.of();
        }

        List<String> valuesToResolve = new ArrayList<>();
        collectResolvableValues(dataMapping, valuesToResolve);

        Map<String, Object> resolvedValues = valuesToResolve.isEmpty()
                ? Map.of()
                : valueResolverService.resolveValues(
                        execution.getProcessInstanceId(),
                        execution,
                        valuesToResolve
                );

        return applyResolvedValues(dataMapping, resolvedValues);
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

    private boolean isResolvableValue(String value) {
        return value != null && (
                value.startsWith("doc:") ||
                value.startsWith("case:") ||
                value.startsWith("pv:") ||
                value.startsWith("template:")
        );
    }
}
