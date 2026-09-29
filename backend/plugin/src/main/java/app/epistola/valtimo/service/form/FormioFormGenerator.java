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
import com.fasterxml.jackson.databind.JsonNode;
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
 *   <li>ARRAY → datagrid with item components and defaultValue; an object or array nested inside
 *       an item keeps its shape, with keys rebased to the row</li>
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

        applyValidation(component, field, hints, constrained);

        return component;
    }

    /**
     * Put the schema's own rules on the input.
     *
     * <p><b>Epistola remains the authority.</b> It validates every value against the contract when
     * it renders, and it would still refuse anything wrong if none of this existed. What this adds
     * is <i>where and when</i> the rule appears: under the field while it is being typed, rather
     * than as a render error beside the finished letter.
     *
     * <p>Two things follow from that. It need not be exhaustive — {@code exclusiveMinimum},
     * {@code exclusiveMaximum} and {@code multipleOf} have no Formio validator, and skipping them
     * costs a late message rather than a wrong letter. And it must never be <i>stricter</i> than
     * the contract, because a rule the server would have accepted becomes work the employee cannot
     * submit at all; see {@link #formioPattern}.
     */
    private void applyValidation(
            ObjectNode component,
            TemplateField field,
            TemplateField.FieldHints hints,
            boolean constrained
    ) {
        TemplateField.Constraints constraints = hints != null ? hints.constraints() : null;
        if (!field.required() && (constraints == null || constraints.isEmpty())) {
            return;
        }

        ObjectNode validate = component.putObject("validate");
        if (field.required()) {
            validate.put("required", true);
        }
        if (constraints == null) {
            return;
        }

        // An enum is already a closed list of values, so length and pattern rules on it can only
        // contradict the options offered.
        if (!constrained) {
            putIfPresent(validate, "minLength", constraints.minLength());
            putIfPresent(validate, "maxLength", constraints.maxLength());
            String pattern = formioPattern(constraints.pattern());
            if (pattern != null) {
                validate.put("pattern", pattern);
            }
        }
        if (constraints.minimum() != null) {
            validate.put("min", constraints.minimum());
        }
        if (constraints.maximum() != null) {
            validate.put("max", constraints.maximum());
        }
        // An array of scalars is one input with `multiple`, and Formio counts its entries with the
        // same two keywords it uses for string length.
        if (isPrimitiveArray(field)) {
            putIfPresent(validate, "minLength", constraints.minItems());
            putIfPresent(validate, "maxLength", constraints.maxItems());
        }
    }

    /**
     * A JSON Schema {@code pattern} as Formio will evaluate it.
     *
     * <p>They do not mean the same thing. JSON Schema's pattern <i>searches</i> — {@code \d{3}}
     * matches "ab123cd" — while Formio wraps it as {@code ^…$} and so requires the whole value to
     * match. Passing an unanchored pattern straight through would reject values the contract
     * allows and Epistola accepts, so it is wrapped to search. An already-anchored pattern is left
     * exactly as it is, which is the common case.
     */
    private String formioPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        if (pattern.startsWith("^") || pattern.endsWith("$")) {
            return pattern;
        }
        return "[\\s\\S]*(?:" + pattern + ")[\\s\\S]*";
    }

    /**
     * Strip an array item's own path from every key beneath it.
     *
     * <p>The analyzer paths an item's fields against the document — {@code regels[].adres.straat} —
     * while a Form.io data grid scopes each row's components to the row, so the same field has to
     * be keyed {@code adres.straat} inside the grid. A nested grid has already rebased its own
     * children against its own prefix by the time this runs, and those no longer carry this one,
     * so they are left alone.
     */
    private void rebaseKeys(ObjectNode component, String itemPrefix) {
        JsonNode key = component.get("key");
        if (key != null && key.isTextual() && key.asText().startsWith(itemPrefix)) {
            component.put("key", key.asText().substring(itemPrefix.length()));
        }
        if (component.get("components") instanceof ArrayNode children) {
            for (JsonNode child : children) {
                if (child instanceof ObjectNode childObject) {
                    rebaseKeys(childObject, itemPrefix);
                }
            }
        }
    }

    private void putIfPresent(ObjectNode validate, String key, Integer value) {
        if (value != null) {
            validate.put(key, value);
        }
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

        // A data grid counts its rows with the same two keywords a string uses for its length.
        TemplateField.Constraints constraints =
                field.hints() != null ? field.hints().constraints() : null;
        if (field.required() || (constraints != null && !constraints.isEmpty())) {
            ObjectNode validate = component.putObject("validate");
            if (field.required()) {
                validate.put("required", true);
            }
            if (constraints != null) {
                putIfPresent(validate, "minLength", constraints.minItems());
                putIfPresent(validate, "maxLength", constraints.maxItems());
            }
        }

        // An item's components are built exactly as top-level ones are — so an object inside an
        // item becomes a fieldset and a nested array becomes another grid — and then rebased,
        // because a data grid scopes its children to the row while the analyzer gives them paths
        // relative to the whole document.
        ArrayNode components = component.putArray("components");
        String itemPrefix = field.path() + "[].";
        for (TemplateField child : safeChildren(field)) {
            ObjectNode column = buildComponent(child, Map.of());
            rebaseKeys(column, itemPrefix);
            components.add(column);
        }

        // Set default values from resolved data
        if (!items.isEmpty()) {
            component.set("defaultValue", objectMapper.valueToTree(items));
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
