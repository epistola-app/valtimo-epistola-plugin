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
