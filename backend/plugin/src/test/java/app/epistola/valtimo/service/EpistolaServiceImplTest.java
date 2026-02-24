package app.epistola.valtimo.service;

import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.EnvironmentInfo;
import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GeneratedDocument;
import app.epistola.valtimo.domain.GenerationJobDetail;
import app.epistola.valtimo.domain.GenerationJobStatus;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateInfo;
import app.epistola.valtimo.domain.VariantInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EpistolaServiceImpl using the Epistola contract mock server (Prism).
 * The mock server is generated from the same OpenAPI spec as the client library,
 * guaranteeing path and response compatibility.
 */
@Testcontainers
class EpistolaServiceImplTest {

    @Container
    private static final GenericContainer<?> MOCK_SERVER = new GenericContainer<>(
            "ghcr.io/epistola-app/epistola-contract/mock-server:0.1.10"
    )
            .withExposedPorts(4010)
            .waitingFor(Wait.forHttp("/tenants/test/templates")
                    .forStatusCode(200)
                    .withHeader("X-API-Key", "test"));

    private static EpistolaServiceImpl service;
    private static String baseUrl;

    private static final String API_KEY = "test-api-key";
    private static final String TENANT_ID = "test-tenant";

    @BeforeAll
    static void setUp() {
        baseUrl = "http://" + MOCK_SERVER.getHost() + ":" + MOCK_SERVER.getMappedPort(4010);
        EpistolaApiClientFactory factory = new EpistolaApiClientFactory();
        service = new EpistolaServiceImpl(factory);
    }

    @Test
    void getTemplates_returnsTemplateList() {
        List<TemplateInfo> templates = service.getTemplates(baseUrl, API_KEY, TENANT_ID);

        assertNotNull(templates);
        assertFalse(templates.isEmpty());
        // Mock server returns deterministic data — verify structure, not exact values
        TemplateInfo first = templates.get(0);
        assertNotNull(first.id());
        assertNotNull(first.name());
    }

    @Test
    void getTemplateDetails_returnsTemplateWithSchema() {
        // First get templates to find a valid ID
        List<TemplateInfo> templates = service.getTemplates(baseUrl, API_KEY, TENANT_ID);
        assertFalse(templates.isEmpty());
        String templateId = templates.get(0).id();

        TemplateDetails details = service.getTemplateDetails(baseUrl, API_KEY, TENANT_ID, templateId);

        assertNotNull(details);
        assertEquals(templateId, details.id());
        assertNotNull(details.name());
        assertNotNull(details.fields());
        assertFalse(details.fields().isEmpty(), "Template should have parsed fields from schema");
    }

    @Test
    void getEnvironments_returnsEnvironmentList() {
        List<EnvironmentInfo> environments = service.getEnvironments(baseUrl, API_KEY, TENANT_ID);

        assertNotNull(environments);
        assertFalse(environments.isEmpty());
        EnvironmentInfo first = environments.get(0);
        assertNotNull(first.id());
        assertNotNull(first.name());
    }

    @Test
    void getVariants_returnsVariantList() {
        // First get templates to find a valid ID
        List<TemplateInfo> templates = service.getTemplates(baseUrl, API_KEY, TENANT_ID);
        assertFalse(templates.isEmpty());
        String templateId = templates.get(0).id();

        List<VariantInfo> variants = service.getVariants(baseUrl, API_KEY, TENANT_ID, templateId);

        assertNotNull(variants);
        assertFalse(variants.isEmpty());
        VariantInfo first = variants.get(0);
        assertNotNull(first.id());
        assertEquals(templateId, first.templateId());
        assertNotNull(first.name());
    }

    @Test
    void generateDocument_submitsRequestAndReturnsRequestId() {
        // First get templates/variants to use valid IDs
        List<TemplateInfo> templates = service.getTemplates(baseUrl, API_KEY, TENANT_ID);
        assertFalse(templates.isEmpty());
        String templateId = templates.get(0).id();

        List<VariantInfo> variants = service.getVariants(baseUrl, API_KEY, TENANT_ID, templateId);
        assertFalse(variants.isEmpty());
        String variantId = variants.get(0).id();

        GeneratedDocument result = service.generateDocument(
                baseUrl,
                API_KEY,
                TENANT_ID,
                templateId,
                variantId,
                null,  // no variant attributes when using explicit variantId
                "production",
                Map.of("customer", Map.of("name", "Test Customer", "email", "test@example.com")),
                FileFormat.PDF,
                "invoice-001.pdf",
                "correlation-123"
        );

        assertNotNull(result);
        assertNotNull(result.getDocumentId());
        assertFalse(result.getDocumentId().isBlank());
    }

    @Test
    void getJobStatus_returnsStatus() {
        // The mock server returns a deterministic response for any valid UUID
        GenerationJobDetail detail = service.getJobStatus(
                baseUrl,
                API_KEY,
                TENANT_ID,
                "550e8400-e29b-41d4-a716-446655440000"
        );

        assertNotNull(detail);
        assertEquals("550e8400-e29b-41d4-a716-446655440000", detail.getRequestId());
        assertNotNull(detail.getStatus());
        // Mock server returns COMPLETED status
        assertEquals(GenerationJobStatus.COMPLETED, detail.getStatus());
        assertNotNull(detail.getDocumentId());
    }

    @Nested
    class ExtractFieldsFromSchema {

        @Test
        void flatSchema_producesScalarFields() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "name", Map.of("type", "string", "description", "Customer name"),
                            "age", Map.of("type", "number")
                    ),
                    "required", List.of("name")
            );

            List<TemplateField> fields = service.extractFieldsFromSchema(schema);

            assertEquals(2, fields.size());
            TemplateField nameField = findField(fields, "name");
            assertEquals("name", nameField.path());
            assertEquals(TemplateField.FieldType.SCALAR, nameField.fieldType());
            assertTrue(nameField.required());
            assertEquals("Customer name", nameField.description());
            assertTrue(nameField.children().isEmpty());

            TemplateField ageField = findField(fields, "age");
            assertEquals("age", ageField.path());
            assertEquals(TemplateField.FieldType.SCALAR, ageField.fieldType());
            assertFalse(ageField.required());
        }

        @Test
        void nestedObjectSchema_producesFieldsWithDotNotationPaths() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "invoice", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "date", Map.of("type", "string"),
                                            "total", Map.of("type", "number")
                                    ),
                                    "required", List.of("date")
                            )
                    ),
                    "required", List.of("invoice")
            );

            List<TemplateField> fields = service.extractFieldsFromSchema(schema);

            assertEquals(1, fields.size());
            TemplateField invoiceField = fields.get(0);
            assertEquals("invoice", invoiceField.name());
            assertEquals("invoice", invoiceField.path());
            assertEquals(TemplateField.FieldType.OBJECT, invoiceField.fieldType());
            assertTrue(invoiceField.required());
            assertEquals(2, invoiceField.children().size());

            TemplateField dateField = findField(invoiceField.children(), "date");
            assertEquals("invoice.date", dateField.path());
            assertEquals(TemplateField.FieldType.SCALAR, dateField.fieldType());
            assertTrue(dateField.required());

            TemplateField totalField = findField(invoiceField.children(), "total");
            assertEquals("invoice.total", totalField.path());
            assertFalse(totalField.required());
        }

        @Test
        void arrayOfObjectsSchema_producesArrayFieldWithChildren() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "lineItems", Map.of(
                                    "type", "array",
                                    "items", Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "description", Map.of("type", "string"),
                                                    "amount", Map.of("type", "number")
                                            ),
                                            "required", List.of("description", "amount")
                                    )
                            )
                    )
            );

            List<TemplateField> fields = service.extractFieldsFromSchema(schema);

            assertEquals(1, fields.size());
            TemplateField lineItemsField = fields.get(0);
            assertEquals("lineItems", lineItemsField.name());
            assertEquals("lineItems", lineItemsField.path());
            assertEquals("array", lineItemsField.type());
            assertEquals(TemplateField.FieldType.ARRAY, lineItemsField.fieldType());
            assertEquals(2, lineItemsField.children().size());

            TemplateField descField = findField(lineItemsField.children(), "description");
            assertEquals("lineItems[].description", descField.path());
            assertTrue(descField.required());

            TemplateField amountField = findField(lineItemsField.children(), "amount");
            assertEquals("lineItems[].amount", amountField.path());
            assertTrue(amountField.required());
        }

        @Test
        void arrayOfPrimitivesSchema_producesScalarField() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "tags", Map.of(
                                    "type", "array",
                                    "items", Map.of("type", "string")
                            )
                    )
            );

            List<TemplateField> fields = service.extractFieldsFromSchema(schema);

            assertEquals(1, fields.size());
            TemplateField tagsField = fields.get(0);
            assertEquals("tags", tagsField.name());
            assertEquals("tags", tagsField.path());
            assertEquals("array", tagsField.type());
            assertEquals(TemplateField.FieldType.SCALAR, tagsField.fieldType());
            assertTrue(tagsField.children().isEmpty());
        }

        @Test
        void deeplyNestedSchema_producesCorrectPaths() {
            // Use LinkedHashMap to have stable order
            Map<String, Object> cityProps = new LinkedHashMap<>();
            cityProps.put("name", Map.of("type", "string"));
            cityProps.put("zip", Map.of("type", "string"));

            Map<String, Object> cityDef = new LinkedHashMap<>();
            cityDef.put("type", "object");
            cityDef.put("properties", cityProps);
            cityDef.put("required", List.of("name"));

            Map<String, Object> addressProps = new LinkedHashMap<>();
            addressProps.put("street", Map.of("type", "string"));
            addressProps.put("city", cityDef);

            Map<String, Object> addressDef = new LinkedHashMap<>();
            addressDef.put("type", "object");
            addressDef.put("properties", addressProps);

            Map<String, Object> customerProps = new LinkedHashMap<>();
            customerProps.put("name", Map.of("type", "string"));
            customerProps.put("address", addressDef);

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", customerProps);
            schema.put("required", List.of("name"));

            List<TemplateField> fields = service.extractFieldsFromSchema(schema);

            assertEquals(2, fields.size());
            TemplateField nameField = findField(fields, "name");
            assertEquals("name", nameField.path());
            assertTrue(nameField.required());

            TemplateField addressField = findField(fields, "address");
            assertEquals("address", addressField.path());
            assertEquals(TemplateField.FieldType.OBJECT, addressField.fieldType());
            assertEquals(2, addressField.children().size());

            TemplateField streetField = findField(addressField.children(), "street");
            assertEquals("address.street", streetField.path());

            TemplateField cityField = findField(addressField.children(), "city");
            assertEquals("address.city", cityField.path());
            assertEquals(TemplateField.FieldType.OBJECT, cityField.fieldType());
            assertEquals(2, cityField.children().size());

            TemplateField cityNameField = findField(cityField.children(), "name");
            assertEquals("address.city.name", cityNameField.path());
            assertTrue(cityNameField.required());

            TemplateField zipField = findField(cityField.children(), "zip");
            assertEquals("address.city.zip", zipField.path());
            assertFalse(zipField.required());
        }

        @Test
        void mixedFlatAndNestedSchema_producesCorrectFields() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "customerName", Map.of("type", "string"),
                            "invoice", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "date", Map.of("type", "string"),
                                            "lineItems", Map.of(
                                                    "type", "array",
                                                    "items", Map.of(
                                                            "type", "object",
                                                            "properties", Map.of(
                                                                    "product", Map.of("type", "string"),
                                                                    "price", Map.of("type", "number")
                                                            )
                                                    )
                                            )
                                    )
                            )
                    ),
                    "required", List.of("customerName", "invoice")
            );

            List<TemplateField> fields = service.extractFieldsFromSchema(schema);

            assertEquals(2, fields.size());

            TemplateField customerField = findField(fields, "customerName");
            assertEquals(TemplateField.FieldType.SCALAR, customerField.fieldType());
            assertTrue(customerField.required());

            TemplateField invoiceField = findField(fields, "invoice");
            assertEquals(TemplateField.FieldType.OBJECT, invoiceField.fieldType());
            assertTrue(invoiceField.required());
            assertEquals(2, invoiceField.children().size());

            TemplateField dateField = findField(invoiceField.children(), "date");
            assertEquals("invoice.date", dateField.path());

            TemplateField lineItemsField = findField(invoiceField.children(), "lineItems");
            assertEquals("invoice.lineItems", lineItemsField.path());
            assertEquals(TemplateField.FieldType.ARRAY, lineItemsField.fieldType());
            assertEquals(2, lineItemsField.children().size());

            TemplateField productField = findField(lineItemsField.children(), "product");
            assertEquals("invoice.lineItems[].product", productField.path());

            TemplateField priceField = findField(lineItemsField.children(), "price");
            assertEquals("invoice.lineItems[].price", priceField.path());
        }

        @Test
        void nullSchema_returnsEmptyList() {
            List<TemplateField> fields = service.extractFieldsFromSchema(null);
            assertTrue(fields.isEmpty());
        }

        @Test
        void schemaWithoutProperties_returnsEmptyList() {
            Map<String, Object> schema = Map.of("type", "object");
            List<TemplateField> fields = service.extractFieldsFromSchema(schema);
            assertTrue(fields.isEmpty());
        }

        private TemplateField findField(List<TemplateField> fields, String name) {
            return fields.stream()
                    .filter(f -> f.name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Field not found: " + name));
        }
    }
}
