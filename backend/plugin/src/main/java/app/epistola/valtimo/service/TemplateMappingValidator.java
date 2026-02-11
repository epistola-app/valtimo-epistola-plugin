package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.TemplateField;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Validates that all required template fields have been mapped to a data source.
 * Traverses the field tree to collect all required leaf paths and checks each
 * has a non-empty entry in the data mapping.
 */
public final class TemplateMappingValidator {

    private TemplateMappingValidator() {
    }

    /**
     * Find required template fields that are missing from the data mapping.
     *
     * @param fields      the template field tree from schema parsing
     * @param dataMapping the current data mapping (template field path -> value resolver expression)
     * @return list of missing required field paths (empty if all required fields are mapped)
     */
    public static List<String> findMissingRequiredFields(List<TemplateField> fields, Map<String, String> dataMapping) {
        if (fields == null || fields.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, String> mapping = dataMapping != null ? dataMapping : Collections.emptyMap();
        List<String> missing = new ArrayList<>();
        collectMissingRequired(fields, mapping, missing);
        return missing;
    }

    private static void collectMissingRequired(List<TemplateField> fields, Map<String, String> mapping, List<String> missing) {
        for (TemplateField field : fields) {
            switch (field.fieldType()) {
                case SCALAR -> {
                    if (field.required() && !hasNonEmptyMapping(field.path(), mapping)) {
                        missing.add(field.path());
                    }
                }
                case OBJECT -> {
                    // For objects, check children recursively
                    collectMissingRequired(field.children(), mapping, missing);
                }
                case ARRAY -> {
                    // Arrays are mapped as a whole collection by path
                    if (field.required() && !hasNonEmptyMapping(field.path(), mapping)) {
                        missing.add(field.path());
                    }
                }
            }
        }
    }

    private static boolean hasNonEmptyMapping(String path, Map<String, String> mapping) {
        String value = mapping.get(path);
        return value != null && !value.isBlank();
    }
}
