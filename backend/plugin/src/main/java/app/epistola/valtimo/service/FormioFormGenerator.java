package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.TemplateField;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Generates Formio-compatible form JSON from Epistola template fields and resolved data.
 * <p>
 * Each TemplateField is converted to the appropriate Formio component type:
 * <ul>
 *   <li>SCALAR string → textfield</li>
 *   <li>SCALAR number/integer → number</li>
 *   <li>SCALAR boolean → checkbox</li>
 *   <li>OBJECT → fieldset with nested components</li>
 *   <li>ARRAY → datagrid with item components and defaultValue</li>
 * </ul>
 */
@RequiredArgsConstructor
public class FormioFormGenerator {

    private final ObjectMapper objectMapper;

    /**
     * Generate a complete Formio form definition from template fields and resolved data.
     *
     * @param fields       the template field schema
     * @param resolvedData the resolved data values to prefill
     * @return a Formio form JSON object with display:"form" and components array
     */
    public ObjectNode generateForm(List<TemplateField> fields, Map<String, Object> resolvedData) {
        ObjectNode form = objectMapper.createObjectNode();
        form.put("display", "form");
        ArrayNode components = form.putArray("components");

        for (TemplateField field : fields) {
            components.add(buildComponent(field, resolvedData));
        }

        return form;
    }

    @SuppressWarnings("unchecked")
    private ObjectNode buildComponent(TemplateField field, Map<String, Object> parentData) {
        Object value = parentData != null ? parentData.get(field.name()) : null;

        return switch (field.fieldType()) {
            case SCALAR -> buildScalarComponent(field, value);
            case OBJECT -> buildObjectComponent(field, value instanceof Map<?, ?>
                    ? (Map<String, Object>) value : Map.of());
            case ARRAY -> buildArrayComponent(field, value instanceof List<?>
                    ? (List<?>) value : List.of());
        };
    }

    private ObjectNode buildScalarComponent(TemplateField field, Object value) {
        ObjectNode component = objectMapper.createObjectNode();
        String formioType = mapScalarType(field.type());
        component.put("type", formioType);
        // Use dot-notation path so Formio nests the submission data correctly
        component.put("key", field.path());
        component.put("label", humanizeLabel(field.name()));
        component.put("input", true);

        if (field.description() != null && !field.description().isBlank()) {
            component.put("tooltip", field.description());
        }

        if (value != null) {
            component.set("defaultValue", objectMapper.valueToTree(value));
        }

        if (field.required()) {
            ObjectNode validate = component.putObject("validate");
            validate.put("required", true);
        }

        return component;
    }

    private ObjectNode buildObjectComponent(TemplateField field, Map<String, Object> data) {
        ObjectNode component = objectMapper.createObjectNode();
        component.put("type", "fieldset");
        component.put("legend", humanizeLabel(field.name()));
        component.put("key", field.name());

        ArrayNode components = component.putArray("components");
        for (TemplateField child : safeChildren(field)) {
            components.add(buildComponent(child, data));
        }

        return component;
    }

    @SuppressWarnings("unchecked")
    private ObjectNode buildArrayComponent(TemplateField field, List<?> items) {
        ObjectNode component = objectMapper.createObjectNode();
        component.put("type", "datagrid");
        component.put("key", field.path());
        component.put("label", humanizeLabel(field.name()));
        component.put("input", true);

        // Add item field definitions — use leaf name() since keys are relative to the array item
        ArrayNode components = component.putArray("components");
        for (TemplateField child : safeChildren(field)) {
            ObjectNode colComponent = buildScalarComponent(child, null);
            // Override key to use leaf name (not full path) since datagrid items are scoped
            colComponent.put("key", child.name());
            components.add(colComponent);
        }

        // Set default values from resolved data
        if (!items.isEmpty()) {
            component.set("defaultValue", objectMapper.valueToTree(items));
        }

        if (field.required()) {
            ObjectNode validate = component.putObject("validate");
            validate.put("required", true);
        }

        return component;
    }

    private String mapScalarType(String jsonSchemaType) {
        if (jsonSchemaType == null) {
            return "textfield";
        }
        return switch (jsonSchemaType.toLowerCase()) {
            case "integer", "number" -> "number";
            case "boolean" -> "checkbox";
            default -> "textfield";
        };
    }

    /**
     * Convert a camelCase or snake_case field name to a human-readable label.
     */
    private String humanizeLabel(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        // Insert spaces before uppercase letters (camelCase)
        String spaced = name.replaceAll("([a-z])([A-Z])", "$1 $2");
        // Replace underscores and hyphens with spaces
        spaced = spaced.replaceAll("[_-]", " ");
        // Capitalize first letter
        if (spaced.length() == 1) {
            return spaced.toUpperCase();
        }
        return spaced.substring(0, 1).toUpperCase() + spaced.substring(1);
    }

    private List<TemplateField> safeChildren(TemplateField field) {
        return field.children() != null ? field.children() : List.of();
    }
}
