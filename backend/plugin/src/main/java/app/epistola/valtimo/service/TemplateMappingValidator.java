package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.TemplateField;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Validates that all required template fields have been mapped to a data source.
 * Walks the field tree and the nested mapping tree in parallel to check that
 * each required field has a non-empty value.
 */
public final class TemplateMappingValidator {

    private TemplateMappingValidator() {
    }

    /**
     * Find required template fields that are missing from the data mapping.
     * The data mapping is a nested structure that mirrors the template field hierarchy:
     * objects map to nested Maps, scalars and arrays map to String values.
     *
     * @param fields      the template field tree from schema parsing
     * @param dataMapping the current nested data mapping (field name -> value or nested map)
     * @return list of missing required field paths (empty if all required fields are mapped)
     */
    public static List<String> findMissingRequiredFields(List<TemplateField> fields, Map<String, Object> dataMapping) {
        if (fields == null || fields.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Object> mapping = dataMapping != null ? dataMapping : Collections.emptyMap();
        List<String> missing = new ArrayList<>();
        collectMissingRequired(fields, mapping, missing);
        return missing;
    }

    @SuppressWarnings("unchecked")
    private static void collectMissingRequired(List<TemplateField> fields, Map<String, Object> mapping, List<String> missing) {
        for (TemplateField field : fields) {
            switch (field.fieldType()) {
                case SCALAR -> {
                    if (field.required() && !hasNonEmptyStringValue(field.name(), mapping)) {
                        missing.add(field.path());
                    }
                }
                case OBJECT -> {
                    // Get the nested mapping for this object (if present)
                    Object nested = mapping.get(field.name());
                    Map<String, Object> nestedMap = (nested instanceof Map<?, ?>) ? (Map<String, Object>) nested : Collections.emptyMap();
                    collectMissingRequired(field.children(), nestedMap, missing);
                }
                case ARRAY -> {
                    Object arrayValue = mapping.get(field.name());
                    if (DataMappingResolver.isArrayFieldMapping(arrayValue)) {
                        // Per-item field mapping: validate _source and required children
                        @SuppressWarnings("unchecked")
                        Map<String, Object> arrayMapping = (Map<String, Object>) arrayValue;
                        String source = (String) arrayMapping.get(DataMappingResolver.ARRAY_SOURCE_KEY);
                        if (field.required() && (source == null || source.isBlank())) {
                            missing.add(field.path());
                        }
                        // Check required children have non-empty mappings
                        if (field.children() != null) {
                            for (TemplateField child : field.children()) {
                                if (child.required()) {
                                    Object childMapping = arrayMapping.get(child.name());
                                    if (!(childMapping instanceof String str) || str.isBlank()) {
                                        missing.add(child.path());
                                    }
                                }
                            }
                        }
                    } else {
                        // Direct collection mapping (string value)
                        if (field.required() && !hasNonEmptyStringValue(field.name(), mapping)) {
                            missing.add(field.path());
                        }
                    }
                }
            }
        }
    }

    private static boolean hasNonEmptyStringValue(String name, Map<String, Object> mapping) {
        Object value = mapping.get(name);
        if (value instanceof String str) {
            return !str.isBlank();
        }
        return value != null;
    }
}
