package app.epistola.valtimo.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataMappingResolverTest {

    @Test
    void flatKeys_returnsFlatJson() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("customerName", "John");
        flat.put("amount", 100.50);

        Map<String, Object> result = DataMappingResolver.toNestedStructure(flat);

        assertEquals("John", result.get("customerName"));
        assertEquals(100.50, result.get("amount"));
        assertEquals(2, result.size());
    }

    @Test
    void dotNotationKeys_returnsNestedJson() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("invoice.date", "2024-01-01");
        flat.put("invoice.total", 250.00);

        Map<String, Object> result = DataMappingResolver.toNestedStructure(flat);

        assertEquals(1, result.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> invoice = (Map<String, Object>) result.get("invoice");
        assertNotNull(invoice);
        assertEquals("2024-01-01", invoice.get("date"));
        assertEquals(250.00, invoice.get("total"));
    }

    @Test
    void arrayValue_placedAtCorrectLevel() {
        List<Map<String, Object>> lineItems = List.of(
                Map.of("product", "Widget", "price", 10),
                Map.of("product", "Gadget", "price", 20)
        );

        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("invoice.lineItems", lineItems);

        Map<String, Object> result = DataMappingResolver.toNestedStructure(flat);

        @SuppressWarnings("unchecked")
        Map<String, Object> invoice = (Map<String, Object>) result.get("invoice");
        assertNotNull(invoice);
        assertSame(lineItems, invoice.get("lineItems"));
    }

    @Test
    void mixedFlatAndNested_returnsCorrectStructure() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("customerName", "John");
        flat.put("invoice.date", "2024-01-01");
        flat.put("invoice.total", 100);

        Map<String, Object> result = DataMappingResolver.toNestedStructure(flat);

        assertEquals("John", result.get("customerName"));
        @SuppressWarnings("unchecked")
        Map<String, Object> invoice = (Map<String, Object>) result.get("invoice");
        assertNotNull(invoice);
        assertEquals("2024-01-01", invoice.get("date"));
        assertEquals(100, invoice.get("total"));
    }

    @Test
    void deeplyNestedKeys_returnsCorrectStructure() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("customer.address.city.name", "Amsterdam");
        flat.put("customer.address.city.zip", "1000AA");
        flat.put("customer.address.street", "Main St");
        flat.put("customer.name", "John");

        Map<String, Object> result = DataMappingResolver.toNestedStructure(flat);

        @SuppressWarnings("unchecked")
        Map<String, Object> customer = (Map<String, Object>) result.get("customer");
        assertEquals("John", customer.get("name"));

        @SuppressWarnings("unchecked")
        Map<String, Object> address = (Map<String, Object>) customer.get("address");
        assertEquals("Main St", address.get("street"));

        @SuppressWarnings("unchecked")
        Map<String, Object> city = (Map<String, Object>) address.get("city");
        assertEquals("Amsterdam", city.get("name"));
        assertEquals("1000AA", city.get("zip"));
    }

    @Test
    void emptyMap_returnsEmptyMap() {
        Map<String, Object> result = DataMappingResolver.toNestedStructure(Map.of());
        assertTrue(result.isEmpty());
    }

    // --- mapArrayItems tests ---

    @Test
    void mapArrayItems_transformsItems() {
        List<Map<String, Object>> source = List.of(
                Map.of("productName", "Widget", "unitPrice", 10, "qty", 5),
                Map.of("productName", "Gadget", "unitPrice", 20, "qty", 3)
        );
        Map<String, String> fieldMappings = new LinkedHashMap<>();
        fieldMappings.put("product", "productName");
        fieldMappings.put("price", "unitPrice");

        List<Map<String, Object>> result = DataMappingResolver.mapArrayItems(source, fieldMappings);

        assertEquals(2, result.size());
        assertEquals("Widget", result.get(0).get("product"));
        assertEquals(10, result.get(0).get("price"));
        assertNull(result.get(0).get("qty")); // not in mapping, so not included
        assertEquals("Gadget", result.get(1).get("product"));
        assertEquals(20, result.get(1).get("price"));
    }

    @Test
    void mapArrayItems_noFieldMappings_passesThrough() {
        List<Map<String, Object>> source = List.of(
                Map.of("product", "Widget", "price", 10)
        );

        List<Map<String, Object>> result = DataMappingResolver.mapArrayItems(source, Map.of());

        assertEquals(1, result.size());
        assertEquals("Widget", result.get(0).get("product"));
        assertEquals(10, result.get(0).get("price"));
    }

    @Test
    void mapArrayItems_missingSourceField_returnsNull() {
        List<Map<String, Object>> source = List.of(
                Map.of("productName", "Widget")
        );
        Map<String, String> fieldMappings = Map.of(
                "product", "productName",
                "price", "unitPrice" // not in source
        );

        List<Map<String, Object>> result = DataMappingResolver.mapArrayItems(source, fieldMappings);

        assertEquals(1, result.size());
        assertEquals("Widget", result.get(0).get("product"));
        assertNull(result.get(0).get("price"));
    }

    @Test
    void mapArrayItems_emptyList_returnsEmpty() {
        List<Map<String, Object>> result = DataMappingResolver.mapArrayItems(
                List.of(), Map.of("product", "productName")
        );
        assertTrue(result.isEmpty());
    }

    @Test
    void mapArrayItems_nullList_returnsEmpty() {
        List<Map<String, Object>> result = DataMappingResolver.mapArrayItems(
                null, Map.of("product", "productName")
        );
        assertTrue(result.isEmpty());
    }

    @Test
    void mapArrayItems_nonMapItems_skipped() {
        List<Object> source = List.of("not a map", 42, Map.of("productName", "Widget"));
        Map<String, String> fieldMappings = Map.of("product", "productName");

        List<Map<String, Object>> result = DataMappingResolver.mapArrayItems(source, fieldMappings);

        assertEquals(1, result.size());
        assertEquals("Widget", result.get(0).get("product"));
    }

    @Test
    void mapArrayItems_nullFieldMappings_passesThrough() {
        List<Map<String, Object>> source = List.of(
                Map.of("product", "Widget")
        );

        List<Map<String, Object>> result = DataMappingResolver.mapArrayItems(source, null);

        assertEquals(1, result.size());
        assertEquals("Widget", result.get(0).get("product"));
    }

    @Test
    void isArrayFieldMapping_withSourceKey_returnsTrue() {
        Map<String, Object> mapping = Map.of(
                "_source", "doc:order.items",
                "product", "productName"
        );
        assertTrue(DataMappingResolver.isArrayFieldMapping(mapping));
    }

    @Test
    void isArrayFieldMapping_withoutSourceKey_returnsFalse() {
        Map<String, Object> mapping = Map.of("product", "productName");
        assertFalse(DataMappingResolver.isArrayFieldMapping(mapping));
    }

    @Test
    void isArrayFieldMapping_stringValue_returnsFalse() {
        assertFalse(DataMappingResolver.isArrayFieldMapping("doc:order.items"));
    }

    @Test
    void isArrayFieldMapping_null_returnsFalse() {
        assertFalse(DataMappingResolver.isArrayFieldMapping(null));
    }

    // --- toNestedStructure tests ---

    @Test
    void nullValues_arePreserved() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("invoice.date", null);
        flat.put("name", null);

        Map<String, Object> result = DataMappingResolver.toNestedStructure(flat);

        assertNull(result.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> invoice = (Map<String, Object>) result.get("invoice");
        assertNull(invoice.get("date"));
    }
}
