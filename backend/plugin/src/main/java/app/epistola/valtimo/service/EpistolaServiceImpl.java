package app.epistola.valtimo.service;

import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.EnvironmentInfo;
import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GenerationJobResult;
import app.epistola.valtimo.domain.GenerationJobDetail;
import app.epistola.valtimo.domain.GenerationJobStatus;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateInfo;
import app.epistola.valtimo.domain.VariantInfo;
import app.epistola.client.api.EnvironmentsApi;
import app.epistola.client.api.GenerationApi;
import app.epistola.client.api.TemplatesApi;
import app.epistola.client.api.VariantsApi;
import app.epistola.client.model.DocumentGenerationItemDto;
import app.epistola.client.model.EnvironmentDto;
import app.epistola.client.model.EnvironmentListResponse;
import app.epistola.client.model.GenerateDocumentRequest;
import app.epistola.client.model.GenerationJobResponse;
import app.epistola.client.model.ImportTemplatesRequest;
import app.epistola.client.model.ImportTemplatesResponse;
import app.epistola.client.model.VariantSelectionAttribute;
import app.epistola.client.model.TemplateDto;
import app.epistola.client.model.TemplateListResponse;
import app.epistola.client.model.TemplateSummaryDto;
import app.epistola.client.model.VariantDto;
import app.epistola.client.model.VariantListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of EpistolaService using the Epistola REST API client.
 */
@Slf4j
@RequiredArgsConstructor
public class EpistolaServiceImpl implements EpistolaService {

    private final EpistolaApiClientFactory apiClientFactory;

    @Override
    public List<TemplateInfo> getTemplates(String baseUrl, String apiKey, String tenantId) {
        log.info("Fetching templates for tenant: {}", tenantId);
        try {
            TemplatesApi templatesApi = apiClientFactory.createTemplatesApi(baseUrl, apiKey);
            TemplateListResponse response = templatesApi.listTemplates(tenantId, null);

            if (response == null || response.getItems() == null) {
                return Collections.emptyList();
            }

            return response.getItems().stream()
                    .map(this::mapToTemplateInfo)
                    .toList();
        } catch (Exception e) {
            log.error("Failed to fetch templates for tenant {}: {}", tenantId, e.getMessage());
            throw new EpistolaApiException("Failed to fetch templates", e);
        }
    }

    @Override
    public TemplateDetails getTemplateDetails(String baseUrl, String apiKey, String tenantId, String templateId) {
        log.info("Fetching template details for tenant: {}, template: {}", tenantId, templateId);
        try {
            TemplatesApi templatesApi = apiClientFactory.createTemplatesApi(baseUrl, apiKey);
            TemplateDto response = templatesApi.getTemplate(tenantId, templateId);

            if (response == null) {
                throw new EpistolaApiException("Template not found: " + templateId);
            }

            return mapToTemplateDetails(response);
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch template details for tenant {}, template {}: {}", tenantId, templateId, e.getMessage());
            throw new EpistolaApiException("Failed to fetch template details", e);
        }
    }

    @Override
    public List<EnvironmentInfo> getEnvironments(String baseUrl, String apiKey, String tenantId) {
        log.info("Fetching environments for tenant: {}", tenantId);
        try {
            EnvironmentsApi environmentsApi = apiClientFactory.createEnvironmentsApi(baseUrl, apiKey);
            EnvironmentListResponse response = environmentsApi.listEnvironments(tenantId);

            if (response == null || response.getItems() == null) {
                return Collections.emptyList();
            }

            return response.getItems().stream()
                    .map(this::mapToEnvironmentInfo)
                    .toList();
        } catch (Exception e) {
            log.error("Failed to fetch environments for tenant {}: {}", tenantId, e.getMessage());
            throw new EpistolaApiException("Failed to fetch environments", e);
        }
    }

    @Override
    public List<VariantInfo> getVariants(String baseUrl, String apiKey, String tenantId, String templateId) {
        log.info("Fetching variants for tenant: {}, template: {}", tenantId, templateId);
        try {
            VariantsApi variantsApi = apiClientFactory.createVariantsApi(baseUrl, apiKey);
            VariantListResponse response = variantsApi.listVariants(tenantId, templateId);

            if (response == null || response.getItems() == null) {
                return Collections.emptyList();
            }

            return response.getItems().stream()
                    .map(this::mapToVariantInfo)
                    .toList();
        } catch (Exception e) {
            log.error("Failed to fetch variants for tenant {}, template {}: {}", tenantId, templateId, e.getMessage());
            throw new EpistolaApiException("Failed to fetch variants", e);
        }
    }

    @Override
    public GenerationJobResult submitGenerationJob(
            String baseUrl,
            String apiKey,
            String tenantId,
            String templateId,
            String variantId,
            List<VariantSelectionAttribute> variantAttributes,
            String environmentId,
            Map<String, Object> data,
            FileFormat format,
            String filename,
            String correlationId
    ) {
        log.info("Submitting document generation request: tenantId={}, templateId={}, variantId={}, attributes={}, format={}, filename={}",
                tenantId, templateId, variantId, variantAttributes, format, filename);
        log.debug("Template data: {}", data);

        try {
            GenerationApi generationApi = apiClientFactory.createGenerationApi(baseUrl, apiKey);

            // Build the request using the constructor (Kotlin data class - immutable)
            GenerateDocumentRequest request = new GenerateDocumentRequest(
                    templateId,
                    data,
                    variantId,          // nullable - omit when using attribute-based selection
                    variantAttributes,  // nullable - for attribute-based variant selection
                    null,               // versionId - not used when environmentId is specified
                    environmentId,
                    filename,
                    correlationId
            );

            GenerationJobResponse response = generationApi.generateDocument(tenantId, request);

            log.info("Document generation request submitted: requestId={}, status={}",
                    response.getRequestId(), response.getStatus());

            return GenerationJobResult.builder()
                    .requestId(response.getRequestId().toString())
                    .status(response.getStatus().getValue())
                    .build();
        } catch (Exception e) {
            log.error("Failed to submit document generation request: {}", e.getMessage());
            throw new EpistolaApiException("Failed to submit document generation request", e);
        }
    }

    @Override
    public GenerationJobDetail getJobStatus(String baseUrl, String apiKey, String tenantId, String requestId) {
        log.info("Fetching job status for tenant: {}, requestId: {}", tenantId, requestId);
        try {
            GenerationApi generationApi = apiClientFactory.createGenerationApi(baseUrl, apiKey);
            UUID requestUuid = UUID.fromString(requestId);
            app.epistola.client.model.GenerationJobDetail response = generationApi.getGenerationJobStatus(tenantId, requestUuid);

            if (response == null) {
                throw new EpistolaApiException("Job not found: " + requestId);
            }

            return mapToGenerationJobDetail(response, requestId);
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch job status for tenant {}, requestId {}: {}", tenantId, requestId, e.getMessage());
            throw new EpistolaApiException("Failed to fetch job status", e);
        }
    }

    @Override
    public byte[] downloadDocument(String baseUrl, String apiKey, String tenantId, String documentId) {
        log.info("Downloading document for tenant: {}, documentId: {}", tenantId, documentId);
        try {
            // Use RestClient directly instead of the generated client, because the generated
            // client returns java.io.File which requires an HttpMessageConverter for
            // application/pdf → File that Spring doesn't provide out of the box.
            byte[] content = apiClientFactory.createRestClient(baseUrl, apiKey)
                    .get()
                    .uri("/tenants/{tenantId}/documents/{documentId}", tenantId, documentId)
                    .accept(org.springframework.http.MediaType.APPLICATION_PDF)
                    .retrieve()
                    .body(byte[].class);

            if (content == null || content.length == 0) {
                throw new EpistolaApiException("Downloaded document is empty: " + documentId);
            }

            log.info("Downloaded document {} ({} bytes)", documentId, content.length);
            return content;
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to download document for tenant {}, documentId {}: {}", tenantId, documentId, e.getMessage());
            throw new EpistolaApiException("Failed to download document", e);
        }
    }

    @Override
    public ImportTemplatesResponse importTemplates(String baseUrl, String apiKey, String tenantId, ImportTemplatesRequest request) {
        log.info("Importing {} templates for tenant: {}", request.getTemplates().size(), tenantId);
        try {
            TemplatesApi templatesApi = apiClientFactory.createTemplatesApi(baseUrl, apiKey);
            ImportTemplatesResponse response = templatesApi.importTemplates(tenantId, request);
            log.info("Template import completed for tenant: {}", tenantId);
            return response;
        } catch (Exception e) {
            log.error("Failed to import templates for tenant {}: {}", tenantId, e.getMessage());
            throw new EpistolaApiException("Failed to import templates", e);
        }
    }

    // Mapping methods

    private TemplateInfo mapToTemplateInfo(TemplateSummaryDto dto) {
        return new TemplateInfo(
                dto.getId(),
                dto.getName(),
                null  // description is not available in TemplateSummaryDto
        );
    }

    private TemplateDetails mapToTemplateDetails(TemplateDto dto) {
        Object schemaSource = dto.getDataModel() != null ? dto.getDataModel() : dto.getSchema();
        List<TemplateField> fields = extractFieldsFromSchema(schemaSource);

        return new TemplateDetails(
                dto.getId(),
                dto.getName(),
                fields
        );
    }

    /**
     * Extract fields from the template's JSON schema.
     * Recursively processes nested objects and arrays to produce a tree of TemplateField nodes.
     */
    List<TemplateField> extractFieldsFromSchema(Object schema) {
        if (schema == null) {
            return Collections.emptyList();
        }

        if (schema instanceof Map<?, ?> schemaMap) {
            return extractFieldsFromObject(schemaMap, "");
        }

        return Collections.emptyList();
    }

    private List<TemplateField> extractFieldsFromObject(Map<?, ?> schemaMap, String parentPath) {
        Object properties = schemaMap.get("properties");
        List<String> requiredNames = extractRequiredNames(schemaMap.get("required"));

        if (!(properties instanceof Map<?, ?> propsMap)) {
            return Collections.emptyList();
        }

        List<TemplateField> fields = new ArrayList<>();
        for (Map.Entry<?, ?> entry : propsMap.entrySet()) {
            String fieldName = String.valueOf(entry.getKey());
            String fieldPath = parentPath.isEmpty() ? fieldName : parentPath + "." + fieldName;
            boolean isRequired = requiredNames.contains(fieldName);

            Map<?, ?> fieldDef = entry.getValue() instanceof Map<?, ?>
                    ? (Map<?, ?>) entry.getValue()
                    : Collections.emptyMap();

            fields.add(buildTemplateField(fieldName, fieldPath, fieldDef, isRequired));
        }

        return fields;
    }

    private TemplateField buildTemplateField(String name, String path, Map<?, ?> fieldDef, boolean required) {
        String type = fieldDef.get("type") != null ? String.valueOf(fieldDef.get("type")) : "string";
        String description = fieldDef.get("description") != null ? String.valueOf(fieldDef.get("description")) : null;

        if ("object".equals(type) && fieldDef.containsKey("properties")) {
            List<TemplateField> children = extractFieldsFromObject(fieldDef, path);
            return new TemplateField(name, path, type, TemplateField.FieldType.OBJECT, required, description, children);
        }

        if ("array".equals(type)) {
            return buildArrayField(name, path, fieldDef, required, description);
        }

        // Scalar field (string, number, integer, boolean)
        return new TemplateField(name, path, type, TemplateField.FieldType.SCALAR, required, description, Collections.emptyList());
    }

    private TemplateField buildArrayField(String name, String path, Map<?, ?> fieldDef, boolean required, String description) {
        Object items = fieldDef.get("items");
        if (items instanceof Map<?, ?> itemsDef) {
            String itemType = itemsDef.get("type") != null ? String.valueOf(itemsDef.get("type")) : "string";
            if ("object".equals(itemType) && itemsDef.containsKey("properties")) {
                // Array of objects: recurse into item properties
                String arrayPath = path + "[]";
                List<TemplateField> children = extractFieldsFromObject(itemsDef, arrayPath);
                return new TemplateField(name, path, "array", TemplateField.FieldType.ARRAY, required, description, children);
            }
        }
        // Array of primitives: treat as scalar (maps to simple list)
        return new TemplateField(name, path, "array", TemplateField.FieldType.SCALAR, required, description, Collections.emptyList());
    }

    private List<String> extractRequiredNames(Object requiredList) {
        if (requiredList instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return Collections.emptyList();
    }

    private EnvironmentInfo mapToEnvironmentInfo(EnvironmentDto dto) {
        return new EnvironmentInfo(
                dto.getId(),
                dto.getName()
        );
    }

    private VariantInfo mapToVariantInfo(VariantDto dto) {
        return new VariantInfo(
                dto.getId(),
                dto.getTemplateId(),
                dto.getTitle() != null ? dto.getTitle() : dto.getId(),  // fallback to id if no title
                dto.getAttributes() != null ? dto.getAttributes() : Map.of()
        );
    }

    private GenerationJobDetail mapToGenerationJobDetail(app.epistola.client.model.GenerationJobDetail response, String requestId) {
        // Get the first item from the response (for single document generation)
        GenerationJobStatus status = GenerationJobStatus.PENDING;
        String documentId = null;
        String errorMessage = null;
        Instant createdAt = null;
        Instant completedAt = null;

        if (response.getItems() != null && !response.getItems().isEmpty()) {
            var item = response.getItems().get(0);
            status = mapToJobStatus(item.getStatus());
            UUID docId = item.getDocumentId();
            documentId = docId != null ? docId.toString() : null;
            errorMessage = item.getErrorMessage();
            createdAt = toInstant(item.getCreatedAt());
            completedAt = toInstant(item.getCompletedAt());
        }

        return GenerationJobDetail.builder()
                .requestId(requestId)
                .status(status)
                .documentId(documentId)
                .errorMessage(errorMessage)
                .createdAt(createdAt)
                .completedAt(completedAt)
                .build();
    }

    private GenerationJobStatus mapToJobStatus(DocumentGenerationItemDto.Status status) {
        if (status == null) {
            return GenerationJobStatus.PENDING;
        }
        return switch (status) {
            case PENDING -> GenerationJobStatus.PENDING;
            case IN_PROGRESS -> GenerationJobStatus.IN_PROGRESS;
            case COMPLETED -> GenerationJobStatus.COMPLETED;
            case FAILED -> GenerationJobStatus.FAILED;
        };
    }

    private Instant toInstant(OffsetDateTime dateTime) {
        return dateTime != null ? dateTime.toInstant() : null;
    }
}
