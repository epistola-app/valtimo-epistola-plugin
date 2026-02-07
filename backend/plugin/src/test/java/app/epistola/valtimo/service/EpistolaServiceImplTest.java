package app.epistola.valtimo.service;

import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.EnvironmentInfo;
import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GeneratedDocument;
import app.epistola.valtimo.domain.GenerationJobDetail;
import app.epistola.valtimo.domain.GenerationJobStatus;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateInfo;
import app.epistola.valtimo.domain.VariantInfo;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        assertTrue(customerNameField.required());
        assertEquals("Name of the customer", customerNameField.description());
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
}
