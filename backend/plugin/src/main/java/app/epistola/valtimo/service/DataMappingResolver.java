package app.epistola.valtimo.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts a flat map with dot-notation keys into a nested map structure.
 * <p>
 * For example:
 * <pre>
 * Input:  {"invoice.date": "2024-01-01", "invoice.total": 100, "customerName": "John"}
 * Output: {"invoice": {"date": "2024-01-01", "total": 100}, "customerName": "John"}
 * </pre>
 * <p>
 * Array values (e.g., lists) are placed at the correct nesting level as-is.
 * The caller is responsible for ensuring array item structures match the template schema.
 */
public final class DataMappingResolver {

    private DataMappingResolver() {
    }

    /**
     * Convert a flat map with dot-notation keys to a nested structure.
     *
     * @param flatMap the resolved data mapping with dot-notation keys
     * @return a nested map suitable for the Epistola API
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toNestedStructure(Map<String, Object> flatMap) {
        Map<String, Object> result = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : flatMap.entrySet()) {
            String[] parts = entry.getKey().split("\\.");
            Map<String, Object> current = result;

            for (int i = 0; i < parts.length - 1; i++) {
                current = (Map<String, Object>) current.computeIfAbsent(
                        parts[i], k -> new LinkedHashMap<>()
                );
            }

            current.put(parts[parts.length - 1], entry.getValue());
        }

        return result;
    }
}
