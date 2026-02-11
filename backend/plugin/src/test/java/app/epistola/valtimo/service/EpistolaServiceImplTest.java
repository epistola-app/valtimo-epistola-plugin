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
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EpistolaServiceImpl using WireMock to mock the Epistola API.
 */
class EpistolaServiceImplTest {

    private WireMockServer wireMockServer;
    private EpistolaServiceImpl service;
    private String baseUrl;

    private static final String API_KEY = "test-api-key";
    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0); // Random port
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
        baseUrl = "http://localhost:" + wireMockServer.port();

        EpistolaApiClientFactory factory = new EpistolaApiClientFactory();
        service = new EpistolaServiceImpl(factory);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void getTemplates_returnsTemplateList() {
        // Given
        stubFor(get(urlEqualTo("/v1/tenants/test-tenant/templates"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(okJson("""
                        {
                            "items": [
                                {
                                    "id": "invoice-template",
                                    "tenantId": "test-tenant",
                                    "name": "Invoice Template",
                                    "createdAt": "2024-01-15T10:30:00Z",
                                    "lastModified": "2024-01-20T14:45:00Z"
                                },
                                {
                                    "id": "contract-template",
                                    "tenantId": "test-tenant",
                                    "name": "Contract Template",
                                    "createdAt": "2024-02-01T09:00:00Z",
                                    "lastModified": "2024-02-05T11:30:00Z"
                                }
                            ]
                        }
                        """)));

        // When
        List<TemplateInfo> templates = service.getTemplates(baseUrl, API_KEY, TENANT_ID);

        // Then
        assertEquals(2, templates.size());
        assertEquals("invoice-template", templates.get(0).id());
        assertEquals("Invoice Template", templates.get(0).name());
        assertEquals("contract-template", templates.get(1).id());
        assertEquals("Contract Template", templates.get(1).name());
    }

    @Test
    void getTemplateDetails_returnsTemplateWithSchema() {
        // Given
        stubFor(get(urlEqualTo("/v1/tenants/test-tenant/templates/invoice-template"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(okJson("""
                        {
                            "id": "invoice-template",
                            "tenantId": "test-tenant",
                            "name": "Invoice Template",
                            "variants": [],
                            "createdAt": "2024-01-15T10:30:00Z",
                            "lastModified": "2024-01-20T14:45:00Z",
                            "schema": {
                                "type": "object",
                                "properties": {
                                    "customerName": {
                                        "type": "string",
                                        "description": "Name of the customer"
                                    },
                                    "amount": {
                                        "type": "number",
                                        "description": "Invoice amount"
                                    }
                                },
                                "required": ["customerName"]
                            }
                        }
                        """)));

        // When
        TemplateDetails details = service.getTemplateDetails(baseUrl, API_KEY, TENANT_ID, "invoice-template");

        // Then
        assertEquals("invoice-template", details.id());
        assertEquals("Invoice Template", details.name());
        assertEquals(2, details.fields().size());

        var customerNameField = details.fields().stream()
                .filter(f -> f.name().equals("customerName"))
                .findFirst()
                .orElseThrow();
        assertEquals("string", customerNameField.type());
        assertEquals("customerName", customerNameField.path());
        assertEquals(TemplateField.FieldType.SCALAR, customerNameField.fieldType());
        assertTrue(customerNameField.required());
        assertEquals("Name of the customer", customerNameField.description());
        assertTrue(customerNameField.children().isEmpty());
    }

    @Test
    void getEnvironments_returnsEnvironmentList() {
        // Given
        stubFor(get(urlEqualTo("/v1/tenants/test-tenant/environments"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(okJson("""
                        {
                            "items": [
                                {
                                    "id": "staging",
                                    "tenantId": "test-tenant",
                                    "name": "Staging",
                                    "createdAt": "2024-01-01T00:00:00Z"
                                },
                                {
                                    "id": "production",
                                    "tenantId": "test-tenant",
                                    "name": "Production",
                                    "createdAt": "2024-01-01T00:00:00Z"
                                }
                            ]
                        }
                        """)));

        // When
        List<EnvironmentInfo> environments = service.getEnvironments(baseUrl, API_KEY, TENANT_ID);

        // Then
        assertEquals(2, environments.size());
        assertEquals("staging", environments.get(0).id());
        assertEquals("Staging", environments.get(0).name());
        assertEquals("production", environments.get(1).id());
        assertEquals("Production", environments.get(1).name());
    }

    @Test
    void getVariants_returnsVariantList() {
        // Given
        stubFor(get(urlEqualTo("/v1/tenants/test-tenant/templates/invoice-template/variants"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(okJson("""
                        {
                            "items": [
                                {
                                    "id": "nl-variant",
                                    "templateId": "invoice-template",
                                    "tags": {"locale": "nl-NL", "brand": "acme"},
                                    "hasDraft": false,
                                    "publishedVersions": [1, 2],
                                    "createdAt": "2024-01-15T10:30:00Z",
                                    "lastModified": "2024-01-20T14:45:00Z",
                                    "title": "Dutch Variant"
                                },
                                {
                                    "id": "en-variant",
                                    "templateId": "invoice-template",
                                    "tags": {"locale": "en-US"},
                                    "hasDraft": true,
                                    "publishedVersions": [1],
                                    "createdAt": "2024-01-16T11:00:00Z",
                                    "lastModified": "2024-01-21T15:00:00Z",
                                    "title": "English Variant"
                                }
                            ]
                        }
                        """)));

        // When
        List<VariantInfo> variants = service.getVariants(baseUrl, API_KEY, TENANT_ID, "invoice-template");

        // Then
        assertEquals(2, variants.size());
        assertEquals("nl-variant", variants.get(0).id());
        assertEquals("invoice-template", variants.get(0).templateId());
        assertEquals("Dutch Variant", variants.get(0).name());
        assertTrue(variants.get(0).tags().contains("locale=nl-NL"));
        assertTrue(variants.get(0).tags().contains("brand=acme"));
    }

    @Test
    void generateDocument_submitsRequestAndReturnsRequestId() {
        // Given
        stubFor(post(urlEqualTo("/v1/tenants/test-tenant/documents/generate"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .withHeader("Content-Type", containing("application/vnd.epistola.v1+json"))
                .willReturn(okJson("""
                        {
                            "requestId": "550e8400-e29b-41d4-a716-446655440000",
                            "status": "PENDING",
                            "jobType": "SINGLE",
                            "totalCount": 1,
                            "createdAt": "2024-01-20T14:45:00Z"
                        }
                        """)));

        // When
        GeneratedDocument result = service.generateDocument(
                baseUrl,
                API_KEY,
                TENANT_ID,
                "invoice-template",
                "nl-variant",
                "production",
                Map.of("customerName", "Test Customer", "amount", 100.50),
                FileFormat.PDF,
                "invoice-001.pdf",
                "correlation-123"
        );

        // Then
        assertNotNull(result);
        assertEquals("550e8400-e29b-41d4-a716-446655440000", result.getDocumentId());

        verify(postRequestedFor(urlEqualTo("/v1/tenants/test-tenant/documents/generate"))
                .withRequestBody(matchingJsonPath("$.templateId", equalTo("invoice-template")))
                .withRequestBody(matchingJsonPath("$.variantId", equalTo("nl-variant")))
                .withRequestBody(matchingJsonPath("$.environmentId", equalTo("production")))
                .withRequestBody(matchingJsonPath("$.filename", equalTo("invoice-001.pdf")))
                .withRequestBody(matchingJsonPath("$.correlationId", equalTo("correlation-123"))));
    }

    @Test
    void getJobStatus_returnsCompletedStatus() {
        // Given
        stubFor(get(urlEqualTo("/v1/tenants/test-tenant/documents/jobs/550e8400-e29b-41d4-a716-446655440000"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(okJson("""
                        {
                            "request": {
                                "id": "550e8400-e29b-41d4-a716-446655440000",
                                "jobType": "SINGLE",
                                "status": "COMPLETED",
                                "totalCount": 1,
                                "completedCount": 1,
                                "failedCount": 0,
                                "createdAt": "2024-01-20T14:45:00Z",
                                "completedAt": "2024-01-20T14:46:00Z"
                            },
                            "items": [
                                {
                                    "id": "660e8400-e29b-41d4-a716-446655440001",
                                    "templateId": "invoice-template",
                                    "variantId": "nl-variant",
                                    "data": {},
                                    "status": "COMPLETED",
                                    "createdAt": "2024-01-20T14:45:00Z",
                                    "documentId": "770e8400-e29b-41d4-a716-446655440002",
                                    "completedAt": "2024-01-20T14:46:00Z"
                                }
                            ]
                        }
                        """)));

        // When
        GenerationJobDetail detail = service.getJobStatus(
                baseUrl,
                API_KEY,
                TENANT_ID,
                "550e8400-e29b-41d4-a716-446655440000"
        );

        // Then
        assertEquals("550e8400-e29b-41d4-a716-446655440000", detail.getRequestId());
        assertEquals(GenerationJobStatus.COMPLETED, detail.getStatus());
        assertEquals("770e8400-e29b-41d4-a716-446655440002", detail.getDocumentId());
        assertNull(detail.getErrorMessage());
        assertNotNull(detail.getCompletedAt());
    }

    @Test
    void getJobStatus_returnsFailedStatus() {
        // Given
        stubFor(get(urlEqualTo("/v1/tenants/test-tenant/documents/jobs/550e8400-e29b-41d4-a716-446655440000"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(okJson("""
                        {
                            "request": {
                                "id": "550e8400-e29b-41d4-a716-446655440000",
                                "jobType": "SINGLE",
                                "status": "FAILED",
                                "totalCount": 1,
                                "completedCount": 0,
                                "failedCount": 1,
                                "createdAt": "2024-01-20T14:45:00Z",
                                "completedAt": "2024-01-20T14:46:00Z",
                                "errorMessage": "Template rendering failed: missing required field"
                            },
                            "items": [
                                {
                                    "id": "660e8400-e29b-41d4-a716-446655440001",
                                    "templateId": "invoice-template",
                                    "variantId": "nl-variant",
                                    "data": {},
                                    "status": "FAILED",
                                    "createdAt": "2024-01-20T14:45:00Z",
                                    "errorMessage": "Template rendering failed: missing required field",
                                    "completedAt": "2024-01-20T14:46:00Z"
                                }
                            ]
                        }
                        """)));

        // When
        GenerationJobDetail detail = service.getJobStatus(
                baseUrl,
                API_KEY,
                TENANT_ID,
                "550e8400-e29b-41d4-a716-446655440000"
        );

        // Then
        assertEquals(GenerationJobStatus.FAILED, detail.getStatus());
        assertNull(detail.getDocumentId());
        assertEquals("Template rendering failed: missing required field", detail.getErrorMessage());
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
