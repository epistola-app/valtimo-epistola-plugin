package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateField.FieldType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FormioFormGeneratorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FormioFormGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new FormioFormGenerator(objectMapper);
    }

    @Nested
    class ScalarFields {

        @Test
        void stringField_generatesTextfieldComponent() {
            TemplateField field = new TemplateField(
                    "firstName", "customer.firstName", "string",
                    FieldType.SCALAR, false, null, List.of());

            ObjectNode form = generator.generateForm(List.of(field), Map.of());

            assertEquals("form", form.get("display").asText());
            ArrayNode components = (ArrayNode) form.get("components");
            assertEquals(1, components.size());

            ObjectNode component = (ObjectNode) components.get(0);
            assertEquals("textfield", component.get("type").asText());
            assertEquals("customer.firstName", component.get("key").asText());
            assertEquals("First Name", component.get("label").asText());
            assertTrue(component.get("input").asBoolean());
        }

        @Test
        void numberField_generatesNumberComponent() {
            TemplateField field = new TemplateField(
                    "amount", "amount", "number",
                    FieldType.SCALAR, false, null, List.of());

            ObjectNode form = generator.generateForm(List.of(field), Map.of());

            ObjectNode component = (ObjectNode) form.get("components").get(0);
            assertEquals("number", component.get("type").asText());
        }

        @Test
        void integerField_generatesNumberComponent() {
            TemplateField field = new TemplateField(
                    "quantity", "quantity", "integer",
                    FieldType.SCALAR, false, null, List.of());

            ObjectNode form = generator.generateForm(List.of(field), Map.of());

            ObjectNode component = (ObjectNode) form.get("components").get(0);
            assertEquals("number", component.get("type").asText());
        }

        @Test
        void booleanField_generatesCheckboxComponent() {
            TemplateField field = new TemplateField(
                    "active", "active", "boolean",
                    FieldType.SCALAR, false, null, List.of());

            ObjectNode form = generator.generateForm(List.of(field), Map.of());

            ObjectNode component = (ObjectNode) form.get("components").get(0);
            assertEquals("checkbox", component.get("type").asText());
        }

        @Test
        void nullType_defaultsToTextfield() {
            TemplateField field = new TemplateField(
                    "unknown", "unknown", null,
                    FieldType.SCALAR, false, null, List.of());

            ObjectNode form = generator.generateForm(List.of(field), Map.of());

            ObjectNode component = (ObjectNode) form.get("components").get(0);
            assertEquals("textfield", component.get("type").asText());
        }
    }

    @Nested
    class ObjectFields {

        @Test
        void objectField_generatesFieldsetWithNestedChildren() {
            TemplateField child1 = new TemplateField(
                    "street", "address.street", "string",
                    FieldType.SCALAR, false, null, List.of());
            TemplateField child2 = new TemplateField(
                    "city", "address.city", "string",
                    FieldType.SCALAR, false, null, List.of());
            TemplateField parent = new TemplateField(
                    "address", "address", "object",
                    FieldType.OBJECT, false, null, List.of(child1, child2));

            ObjectNode form = generator.generateForm(List.of(parent), Map.of());

            ObjectNode fieldset = (ObjectNode) form.get("components").get(0);
            assertEquals("fieldset", fieldset.get("type").asText());
            assertEquals("Address", fieldset.get("legend").asText());

            ArrayNode nested = (ArrayNode) fieldset.get("components");
            assertEquals(2, nested.size());
            assertEquals("textfield", nested.get(0).get("type").asText());
            assertEquals("address.street", nested.get(0).get("key").asText());
            assertEquals("textfield", nested.get(1).get("type").asText());
            assertEquals("address.city", nested.get(1).get("key").asText());
        }

        @Test
        void objectField_passesNestedDataToChildren() {
            TemplateField child = new TemplateField(
                    "city", "address.city", "string",
                    FieldType.SCALAR, false, null, List.of());
            TemplateField parent = new TemplateField(
                    "address", "address", "object",
                    FieldType.OBJECT, false, null, List.of(child));

            Map<String, Object> data = Map.of("address", Map.of("city", "Amsterdam"));
            ObjectNode form = generator.generateForm(List.of(parent), data);

            ObjectNode fieldset = (ObjectNode) form.get("components").get(0);
            ObjectNode cityComponent = (ObjectNode) fieldset.get("components").get(0);
            assertEquals("Amsterdam", cityComponent.get("defaultValue").asText());
        }
    }

    @Nested
    class ArrayFields {

        @Test
        void arrayField_generatesDatagridWithItemColumns() {
            TemplateField child1 = new TemplateField(
                    "product", "items[].product", "string",
                    FieldType.SCALAR, false, null, List.of());
            TemplateField child2 = new TemplateField(
                    "price", "items[].price", "number",
                    FieldType.SCALAR, false, null, List.of());
            TemplateField arrayField = new TemplateField(
                    "items", "items", "array",
                    FieldType.ARRAY, false, null, List.of(child1, child2));

            ObjectNode form = generator.generateForm(List.of(arrayField), Map.of());

            ObjectNode datagrid = (ObjectNode) form.get("components").get(0);
            assertEquals("datagrid", datagrid.get("type").asText());
            assertEquals("items", datagrid.get("key").asText());
            assertEquals("Items", datagrid.get("label").asText());

            ArrayNode columns = (ArrayNode) datagrid.get("components");
            assertEquals(2, columns.size());
            // Children use leaf name as key (not full path)
            assertEquals("product", columns.get(0).get("key").asText());
            assertEquals("textfield", columns.get(0).get("type").asText());
            assertEquals("price", columns.get(1).get("key").asText());
            assertEquals("number", columns.get(1).get("type").asText());
        }

        @Test
        void arrayField_setsDefaultValuesFromResolvedData() {
            TemplateField child = new TemplateField(
                    "product", "items[].product", "string",
                    FieldType.SCALAR, false, null, List.of());
            TemplateField arrayField = new TemplateField(
                    "items", "items", "array",
                    FieldType.ARRAY, false, null, List.of(child));

            List<Map<String, Object>> items = List.of(
                    Map.of("product", "Widget"),
                    Map.of("product", "Gadget"));
            Map<String, Object> data = Map.of("items", items);

            ObjectNode form = generator.generateForm(List.of(arrayField), data);

            ObjectNode datagrid = (ObjectNode) form.get("components").get(0);
            assertNotNull(datagrid.get("defaultValue"));
            assertTrue(datagrid.get("defaultValue").isArray());
            assertEquals(2, datagrid.get("defaultValue").size());
        }

        @Test
        void arrayField_emptyItems_noDefaultValue() {
            TemplateField child = new TemplateField(
                    "product", "items[].product", "string",
                    FieldType.SCALAR, false, null, List.of());
            TemplateField arrayField = new TemplateField(
                    "items", "items", "array",
                    FieldType.ARRAY, false, null, List.of(child));

            ObjectNode form = generator.generateForm(List.of(arrayField), Map.of());

            ObjectNode datagrid = (ObjectNode) form.get("components").get(0);
            assertNull(datagrid.get("defaultValue"));
        }
    }

    @Nested
    class DefaultValues {

        @Test
        void scalarField_setsDefaultValueFromResolvedData() {
            TemplateField field = new TemplateField(
                    "name", "name", "string",
                    FieldType.SCALAR, false, null, List.of());

            Map<String, Object> data = Map.of("name", "John");
            ObjectNode form = generator.generateForm(List.of(field), data);

            ObjectNode component = (ObjectNode) form.get("components").get(0);
            assertEquals("John", component.get("defaultValue").asText());
        }

        @Test
        void scalarField_numericDefaultValue() {
            TemplateField field = new TemplateField(
                    "amount", "amount", "number",
                    FieldType.SCALAR, false, null, List.of());

            Map<String, Object> data = Map.of("amount", 42.5);
            ObjectNode form = generator.generateForm(List.of(field), data);

            ObjectNode component = (ObjectNode) form.get("components").get(0);
            assertEquals(42.5, component.get("defaultValue").asDouble());
        }

        @Test
        void scalarField_noMatchingData_noDefaultValue() {
            TemplateField field = new TemplateField(
                    "name", "name", "string",
                    FieldType.SCALAR, false, null, List.of());

            ObjectNode form = generator.generateForm(List.of(field), Map.of());

            ObjectNode component = (ObjectNode) form.get("components").get(0);
            assertNull(component.get("defaultValue"));
        }
    }

    @Nested
    class Validation {

        @Test
        void requiredField_setsValidateRequired() {
            TemplateField field = new TemplateField(
                    "name", "name", "string",
                    FieldType.SCALAR, true, null, List.of());

            ObjectNode form = generator.generateForm(List.of(field), Map.of());

            ObjectNode component = (ObjectNode) form.get("components").get(0);
            assertTrue(component.has("validate"));
            assertTrue(component.get("validate").get("required").asBoolean());
        }

        @Test
        void nonRequiredField_noValidateBlock() {
            TemplateField field = new TemplateField(
                    "name", "name", "string",
                    FieldType.SCALAR, false, null, List.of());

            ObjectNode form = generator.generateForm(List.of(field), Map.of());

            ObjectNode component = (ObjectNode) form.get("components").get(0);
            assertFalse(component.has("validate"));
        }

        @Test
        void requiredArrayField_setsValidateRequired() {
            TemplateField arrayField = new TemplateField(
                    "items", "items", "array",
                    FieldType.ARRAY, true, null, List.of());

            ObjectNode form = generator.generateForm(List.of(arrayField), Map.of());

            ObjectNode datagrid = (ObjectNode) form.get("components").get(0);
            assertTrue(datagrid.get("validate").get("required").asBoolean());
        }
    }

    @Nested
    class Description {

        @Test
        void fieldWithDescription_setsTooltip() {
            TemplateField field = new TemplateField(
                    "name", "name", "string",
                    FieldType.SCALAR, false, "The customer's full name", List.of());

            ObjectNode form = generator.generateForm(List.of(field), Map.of());

            ObjectNode component = (ObjectNode) form.get("components").get(0);
            assertEquals("The customer's full name", component.get("tooltip").asText());
        }

        @Test
        void fieldWithNullDescription_noTooltip() {
            TemplateField field = new TemplateField(
                    "name", "name", "string",
                    FieldType.SCALAR, false, null, List.of());

            ObjectNode form = generator.generateForm(List.of(field), Map.of());

            ObjectNode component = (ObjectNode) form.get("components").get(0);
            assertFalse(component.has("tooltip"));
        }

        @Test
        void fieldWithBlankDescription_noTooltip() {
            TemplateField field = new TemplateField(
                    "name", "name", "string",
                    FieldType.SCALAR, false, "   ", List.of());

            ObjectNode form = generator.generateForm(List.of(field), Map.of());

            ObjectNode component = (ObjectNode) form.get("components").get(0);
            assertFalse(component.has("tooltip"));
        }
    }

    @Nested
    class HumanizeLabel {

        @Test
        void camelCase_insertsSpacesAndCapitalizes() {
            TemplateField field = new TemplateField(
                    "firstName", "firstName", "string",
                    FieldType.SCALAR, false, null, List.of());

            ObjectNode form = generator.generateForm(List.of(field), Map.of());

            assertEquals("First Name", form.get("components").get(0).get("label").asText());
        }

        @Test
        void snakeCase_replacesUnderscoresWithSpaces() {
            TemplateField field = new TemplateField(
                    "postal_code", "postal_code", "string",
                    FieldType.SCALAR, false, null, List.of());

            ObjectNode form = generator.generateForm(List.of(field), Map.of());

            assertEquals("Postal code", form.get("components").get(0).get("label").asText());
        }

        @Test
        void singleCharacter_capitalizes() {
            TemplateField field = new TemplateField(
                    "a", "a", "string",
                    FieldType.SCALAR, false, null, List.of());

            ObjectNode form = generator.generateForm(List.of(field), Map.of());

            assertEquals("A", form.get("components").get(0).get("label").asText());
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void emptyFieldList_returnsFormWithEmptyComponents() {
            ObjectNode form = generator.generateForm(List.of(), Map.of());

            assertEquals("form", form.get("display").asText());
            assertEquals(0, form.get("components").size());
        }

        @Test
        void objectField_nullChildren_handledSafely() {
            TemplateField parent = new TemplateField(
                    "address", "address", "object",
                    FieldType.OBJECT, false, null, null);

            ObjectNode form = generator.generateForm(List.of(parent), Map.of());

            ObjectNode fieldset = (ObjectNode) form.get("components").get(0);
            assertEquals("fieldset", fieldset.get("type").asText());
            assertEquals(0, fieldset.get("components").size());
        }

        @Test
        void arrayField_nullChildren_handledSafely() {
            TemplateField arrayField = new TemplateField(
                    "items", "items", "array",
                    FieldType.ARRAY, false, null, null);

            ObjectNode form = generator.generateForm(List.of(arrayField), Map.of());

            ObjectNode datagrid = (ObjectNode) form.get("components").get(0);
            assertEquals("datagrid", datagrid.get("type").asText());
            assertEquals(0, datagrid.get("components").size());
        }

        @Test
        void objectField_emptyChildren_handledSafely() {
            TemplateField parent = new TemplateField(
                    "address", "address", "object",
                    FieldType.OBJECT, false, null, List.of());

            ObjectNode form = generator.generateForm(List.of(parent), Map.of());

            ObjectNode fieldset = (ObjectNode) form.get("components").get(0);
            assertEquals(0, fieldset.get("components").size());
        }

        @Test
        void nullResolvedData_handledSafely() {
            TemplateField field = new TemplateField(
                    "name", "name", "string",
                    FieldType.SCALAR, false, null, List.of());

            // null parentData is handled via the ternary in buildComponent
            ObjectNode form = generator.generateForm(List.of(field), Map.of());

            ObjectNode component = (ObjectNode) form.get("components").get(0);
            assertNull(component.get("defaultValue"));
        }
    }
}
