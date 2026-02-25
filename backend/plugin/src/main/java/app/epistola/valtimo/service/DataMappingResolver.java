package app.epistola.valtimo.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
     * Key used in array field mappings to indicate the source collection expression.
     * When an array mapping value is a Map containing this key, it represents a per-item
     * field mapping rather than a direct collection reference.
     */
    public static final String ARRAY_SOURCE_KEY = "_source";

    /**
     * Check if a mapping value represents a per-item array field mapping.
     * A per-item mapping is a Map that contains the {@link #ARRAY_SOURCE_KEY} entry.
     *
     * @param value the mapping value to check
     * @return true if the value is a per-item array field mapping
     */
    public static boolean isArrayFieldMapping(Object value) {
        return value instanceof Map<?, ?> map && map.containsKey(ARRAY_SOURCE_KEY);
    }

    /**
     * Transform each item in a source list by renaming fields according to the mapping.
     * Each mapping entry maps a template field name (key) to a source field name (value).
     * Items that are not Maps are skipped.
     *
     * @param sourceItems   the source list of items (each item expected to be a Map)
     * @param fieldMappings template field name → source field name pairs
     * @return a new list with each item's fields renamed per the mapping
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> mapArrayItems(
            List<?> sourceItems,
            Map<String, String> fieldMappings
    ) {
        if (sourceItems == null) {
            return List.of();
        }
        if (fieldMappings == null || fieldMappings.isEmpty()) {
            // No field mappings: pass through items as-is (cast Maps, skip non-Maps)
            List<Map<String, Object>> result = new ArrayList<>(sourceItems.size());
            for (Object item : sourceItems) {
                if (item instanceof Map<?, ?> mapItem) {
                    result.add((Map<String, Object>) mapItem);
                }
            }
            return result;
        }

        List<Map<String, Object>> result = new ArrayList<>(sourceItems.size());
        for (Object item : sourceItems) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> sourceItem = (Map<String, Object>) item;
            Map<String, Object> mappedItem = new LinkedHashMap<>();
            for (var mapping : fieldMappings.entrySet()) {
                String templateFieldName = mapping.getKey();
                String sourceFieldName = mapping.getValue();
                mappedItem.put(templateFieldName, sourceItem.get(sourceFieldName));
            }
            result.add(mappedItem);
        }
        return result;
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
