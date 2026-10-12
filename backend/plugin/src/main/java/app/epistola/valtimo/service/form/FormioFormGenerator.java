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
import app.epistola.valtimo.richtext.PlainTextFormat;
import app.epistola.valtimo.richtext.RichTextConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Generates Formio-compatible form JSON from Epistola template fields and resolved data.
 * <p>
 * Each TemplateField is converted to the appropriate Formio component type:
 * <ul>
 *   <li>SCALAR with schema {@code enum}/{@code const} → select of exactly those values</li>
 *   <li>SCALAR string → textfield (email format → email; date → textfield with a calendar that
 *       stores {@code yyyy-MM-dd}; date-time → datetime)</li>
 *   <li>SCALAR number/integer → number (integer without decimals)</li>
 *   <li>SCALAR boolean → checkbox</li>
 *   <li>OBJECT → fieldset with nested components</li>
 *   <li>SCALAR typed {@code array} (array of scalars) → one input with {@code multiple}</li>
 *   <li>ARRAY → datagrid with item components and defaultValue; an object or array nested inside
 *       an item keeps its shape, with keys rebased to the row</li>
 *   <li>Rich text → the editor of the configured rich-text format: a WYSIWYG text area for HTML,
 *       a plain one for markdown and plain text (ADR 0008)</li>
 * </ul>
 */
public class FormioFormGenerator {

    /** How a {@code "format": "date"} value is stored: what the schema accepts. */
    private static final String DATE_VALUE_FORMAT = "yyyy-MM-dd";
    /**
     * How a date is shown and typed when nothing knows the reader's locale. The plugin's own
     * frontend replaces it with the locale's format; the stored value never changes.
     */
    private static final String DATE_DISPLAY_FORMAT = "dd-MM-yyyy";
    private static final String DATE_TIME_DISPLAY_FORMAT = "dd-MM-yyyy HH:mm";

    private final ObjectMapper objectMapper;
    private final RichTextConverter richText;

    /** A generator that reads and asks for rich text as plain text. */
    public FormioFormGenerator(ObjectMapper objectMapper) {
        this(objectMapper, new RichTextConverter(List.of(new PlainTextFormat()), PlainTextFormat.ID));
    }

    public FormioFormGenerator(ObjectMapper objectMapper, RichTextConverter richText) {
        this.objectMapper = objectMapper;
        this.richText = richText;
    }

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
        TemplateField.RichTextKind richTextKind = RichTextConverter.kindOf(field);
        if (richTextKind != null) {
            return buildRichTextComponent(field, value, richTextKind);
        }
        ObjectNode component = objectMapper.createObjectNode();
        TemplateField.FieldHints hints = field.hints();
        List<Object> allowedValues = hints != null ? hints.allowedValues() : null;
        boolean constrained = allowedValues != null && !allowedValues.isEmpty();

        component.put("type", constrained ? "select" : mapScalarType(field.type(), hints));
        // Use dot-notation path so Formio nests the submission data correctly
        component.put("key", field.path());
        component.put("label", labelOf(field));
        component.put("input", true);

        if (!constrained) {
            applyInputShape(component, field, hints);
        }

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

        // The example again, named rather than inferred from the placeholder, so that the
        // validation message offering it does not depend on what the placeholder happens to hold.
        if (hints != null && hints.example() != null) {
            component.put("epistolaExample", hints.example());
        }

        Object effectiveValue = value != null ? value : (hints != null ? hints.defaultValue() : null);
        if (effectiveValue != null) {
            component.set("defaultValue", objectMapper.valueToTree(effectiveValue));
        }

        applyValidation(component, field, hints, constrained);

        return component;
    }

    /**
     * An editor for a rich-text field, in the case's format: what the person types is what the case
     * keeps, and the conversion to Epistola's document happens on the way out. A value that is
     * already a document — what was rendered, shown again on a retry form — is written back into
     * that format so the editor shows text rather than JSON.
     */
    private ObjectNode buildRichTextComponent(
            TemplateField field, Object value, TemplateField.RichTextKind kind) {
        ObjectNode component = objectMapper.valueToTree(richText.editorFor(kind));
        component.put("key", field.path());
        component.put("label", labelOf(field));
        component.put("input", true);
        if (field.description() != null && !field.description().isBlank()) {
            component.put("tooltip", field.description());
        }
        Object editorValue = richText.toEditorValue(value, kind);
        if (editorValue != null) {
            component.set("defaultValue", objectMapper.valueToTree(editorValue));
        }
        if (field.required()) {
            component.putObject("validate").put("required", true);
        }
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
     * Shape the input to the value the schema expects, beyond what the component type says.
     *
     * <p>A {@code "format": "date"} field is a text field with a calendar attached rather than
     * Formio's {@code datetime} component, which always stores a full ISO timestamp — and a schema
     * asking for a date rejects one. The calendar widget with {@code saveAs: text} keeps the stored
     * value exactly {@code yyyy-MM-dd}, while the employee picks or types a day as they would write
     * it. A date-time has no such problem: the {@code datetime} component's timestamp is what the
     * schema asks for.
     */
    private void applyInputShape(ObjectNode component, TemplateField field, TemplateField.FieldHints hints) {
        if ("integer".equalsIgnoreCase(field.type())) {
            component.put("decimalLimit", 0);
            component.put("requireDecimal", false);
        }
        String format = hints != null ? hints.format() : null;
        if ("date".equals(format)) {
            ObjectNode widget = component.putObject("widget");
            widget.put("type", "calendar");
            widget.put("saveAs", "text");
            widget.put("dateFormat", DATE_VALUE_FORMAT);
            widget.put("format", DATE_DISPLAY_FORMAT);
            widget.put("enableDate", true);
            widget.put("enableTime", false);
            widget.put("allowInput", true);
            widget.put("altInput", true);
        } else if ("date-time".equals(format)) {
            component.put("format", DATE_TIME_DISPLAY_FORMAT);
            component.put("enableDate", true);
            component.put("enableTime", true);
            component.put("allowInput", true);
            // The datetime component builds its own calendar from these, so a 24-hour clock is
            // asked for here rather than on a widget it would overwrite.
            component.putObject("timePicker").put("showMeridian", false);
        }
    }

    /**
     * An authored example, the only placeholder worth showing: a date or date-time already says
     * how it is written through its picker.
     */
    private String placeholderFor(TemplateField.FieldHints hints) {
        return hints != null ? hints.example() : null;
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
        if (hints != null && "date-time".equals(hints.format())) {
            return "datetime";
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
