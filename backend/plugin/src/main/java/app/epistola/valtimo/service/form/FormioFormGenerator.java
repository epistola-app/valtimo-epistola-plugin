/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package app.epistola.valtimo.service.form;

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
 *   <li>SCALAR with schema {@code enum}/{@code const} → select of exactly those values</li>
 *   <li>SCALAR string → textfield (email format → email; date formats keep an explicit placeholder)</li>
 *   <li>SCALAR number/integer → number</li>
 *   <li>SCALAR boolean → checkbox</li>
 *   <li>OBJECT → fieldset with nested components</li>
 *   <li>SCALAR typed {@code array} (array of scalars) → one input with {@code multiple}</li>
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
        TemplateField.FieldHints hints = field.hints();
        List<Object> allowedValues = hints != null ? hints.allowedValues() : null;
        boolean constrained = allowedValues != null && !allowedValues.isEmpty();

        component.put("type", constrained ? "select" : mapScalarType(field.type(), hints));
        // Use dot-notation path so Formio nests the submission data correctly
        component.put("key", field.path());
        component.put("label", labelOf(field));
        component.put("input", true);

        if (constrained) {
            // The schema constrains this field to a set of values, so offer exactly those rather
            // than a free-text box the backend would reject on submit.
            ArrayNode values = component.putObject("data").putArray("values");
            for (Object allowed : allowedValues) {
                ObjectNode option = values.addObject();
                option.put("label", String.valueOf(allowed));
                option.set("value", objectMapper.valueToTree(allowed));
            }
        } else if (isPrimitiveArray(field)) {
            // An array of scalars: one repeating input rather than a grid of single-column rows.
            component.put("multiple", true);
        }

        if (field.description() != null && !field.description().isBlank()) {
            component.put("tooltip", field.description());
        }

        String placeholder = placeholderFor(hints);
        if (placeholder != null) {
            component.put("placeholder", placeholder);
        }

        Object effectiveValue = value != null ? value : (hints != null ? hints.defaultValue() : null);
        if (effectiveValue != null) {
            component.set("defaultValue", objectMapper.valueToTree(effectiveValue));
        }

        if (field.required()) {
            ObjectNode validate = component.putObject("validate");
            validate.put("required", true);
        }

        return component;
    }

    /**
     * The schema's {@code title} is what the template author wrote for this field, so it beats a
     * label humanized from the property name.
     */
    private String labelOf(TemplateField field) {
        TemplateField.FieldHints hints = field.hints();
        if (hints != null && hints.title() != null && !hints.title().isBlank()) {
            return hints.title();
        }
        return humanizeLabel(field.name());
    }

    /**
     * A date is rendered as a text field with an explicit placeholder rather than Formio's date
     * picker: the picker emits a full ISO timestamp, which a schema with {@code "format": "date"}
     * rejects.
     */
    private String placeholderFor(TemplateField.FieldHints hints) {
        if (hints == null || hints.format() == null) {
            return null;
        }
        return switch (hints.format()) {
            case "date" -> "YYYY-MM-DD";
            case "date-time" -> "YYYY-MM-DDTHH:MM:SSZ";
            default -> null;
        };
    }

    /**
     * An array of scalars reaches the analyzer as a SCALAR field typed {@code array}: it has no
     * child fields to map, only repeated values.
     */
    private boolean isPrimitiveArray(TemplateField field) {
        return "array".equalsIgnoreCase(field.type());
    }

    private ObjectNode buildObjectComponent(TemplateField field, Map<String, Object> data) {
        ObjectNode component = objectMapper.createObjectNode();
        component.put("type", "fieldset");
        component.put("legend", labelOf(field));
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
        // Known gap: an object nested inside an array item is flattened to a single input here,
        // because its children would carry paths relative to the array rather than the item.

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

    private String mapScalarType(String jsonSchemaType, TemplateField.FieldHints hints) {
        if (hints != null && "email".equals(hints.format())) {
            return "email";
        }
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
