package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateField.FieldType;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TemplateMappingValidatorTest {

    @Test
    void allRequiredMapped_returnsEmpty() {
        List<TemplateField> fields = List.of(
                scalar("name", "name", true),
                scalar("email", "email", true)
        );
        Map<String, String> mapping = Map.of(
                "name", "doc:customer.name",
                "email", "doc:customer.email"
        );

        List<String> missing = TemplateMappingValidator.findMissingRequiredFields(fields, mapping);

        assertTrue(missing.isEmpty());
    }

    @Test
    void missingRequired_returnsMissingPaths() {
        List<TemplateField> fields = List.of(
                scalar("name", "name", true),
                scalar("email", "email", true),
                scalar("phone", "phone", false)
        );
        Map<String, String> mapping = Map.of(
                "name", "doc:customer.name"
        );

        List<String> missing = TemplateMappingValidator.findMissingRequiredFields(fields, mapping);

        assertEquals(1, missing.size());
        assertTrue(missing.contains("email"));
    }

    @Test
    void extraOptionalUnmapped_stillValid() {
        List<TemplateField> fields = List.of(
                scalar("name", "name", true),
                scalar("phone", "phone", false),
                scalar("fax", "fax", false)
        );
        Map<String, String> mapping = Map.of(
                "name", "doc:customer.name"
        );

        List<String> missing = TemplateMappingValidator.findMissingRequiredFields(fields, mapping);

        assertTrue(missing.isEmpty());
    }

    @Test
    void nestedRequiredFields_detected() {
        List<TemplateField> fields = List.of(
                new TemplateField("invoice", "invoice", "object", FieldType.OBJECT, true, null, List.of(
                        scalar("date", "invoice.date", true),
                        scalar("total", "invoice.total", false)
                ))
        );
        Map<String, String> mapping = Map.of(
                "invoice.total", "doc:order.total"
        );

        List<String> missing = TemplateMappingValidator.findMissingRequiredFields(fields, mapping);

        assertEquals(1, missing.size());
        assertEquals("invoice.date", missing.get(0));
    }

    @Test
    void requiredArrayField_detected() {
        List<TemplateField> fields = List.of(
                new TemplateField("lineItems", "lineItems", "array", FieldType.ARRAY, true, null, List.of(
                        scalar("product", "lineItems[].product", true),
                        scalar("price", "lineItems[].price", true)
                ))
        );

        List<String> missing = TemplateMappingValidator.findMissingRequiredFields(fields, Map.of());

        assertEquals(1, missing.size());
        assertEquals("lineItems", missing.get(0));
    }

    @Test
    void requiredArrayField_mappedIsValid() {
        List<TemplateField> fields = List.of(
                new TemplateField("lineItems", "lineItems", "array", FieldType.ARRAY, true, null, List.of(
                        scalar("product", "lineItems[].product", true)
                ))
        );
        Map<String, String> mapping = Map.of("lineItems", "doc:order.items");

        List<String> missing = TemplateMappingValidator.findMissingRequiredFields(fields, mapping);

        assertTrue(missing.isEmpty());
    }

    @Test
    void blankMappingValue_treatedAsMissing() {
        List<TemplateField> fields = List.of(
                scalar("name", "name", true)
        );
        Map<String, String> mapping = Map.of("name", "   ");

        List<String> missing = TemplateMappingValidator.findMissingRequiredFields(fields, mapping);

        assertEquals(1, missing.size());
        assertEquals("name", missing.get(0));
    }

    @Test
    void nullFields_returnsEmpty() {
        List<String> missing = TemplateMappingValidator.findMissingRequiredFields(null, Map.of());
        assertTrue(missing.isEmpty());
    }

    @Test
    void nullMapping_treatsAllRequiredAsMissing() {
        List<TemplateField> fields = List.of(
                scalar("name", "name", true)
        );

        List<String> missing = TemplateMappingValidator.findMissingRequiredFields(fields, null);

        assertEquals(1, missing.size());
        assertEquals("name", missing.get(0));
    }

    private static TemplateField scalar(String name, String path, boolean required) {
        return new TemplateField(name, path, "string", FieldType.SCALAR, required, null, Collections.emptyList());
    }
}
