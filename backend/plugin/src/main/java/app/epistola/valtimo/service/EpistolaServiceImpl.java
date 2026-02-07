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
import io.epistola.client.api.EnvironmentsApi;
import io.epistola.client.api.GenerationApi;
import io.epistola.client.api.TemplatesApi;
import io.epistola.client.api.VariantsApi;
import io.epistola.client.model.CreateTemplate201Response;
import io.epistola.client.model.GenerateDocument202Response;
import io.epistola.client.model.GenerateDocumentRequest;
import io.epistola.client.model.GetGenerationJobStatus200Response;
import io.epistola.client.model.GetGenerationJobStatus200ResponseItemsInner;
import io.epistola.client.model.ListEnvironments200Response;
import io.epistola.client.model.ListEnvironments200ResponseItemsInner;
import io.epistola.client.model.ListTemplates200Response;
import io.epistola.client.model.ListTemplates200ResponseItemsInner;
import io.epistola.client.model.ListVariants200Response;
import io.epistola.client.model.ListVariants200ResponseItemsInner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
            ListTemplates200Response response = templatesApi.listTemplates(tenantId, null);

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
            CreateTemplate201Response response = templatesApi.getTemplate(tenantId, templateId);

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
            ListEnvironments200Response response = environmentsApi.listEnvironments(tenantId);

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
            ListVariants200Response response = variantsApi.listVariants(tenantId, templateId);

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
    public GeneratedDocument generateDocument(
            String baseUrl,
            String apiKey,
            String tenantId,
            String templateId,
            String variantId,
            String environmentId,
            Map<String, Object> data,
            FileFormat format,
            String filename,
            String correlationId
    ) {
        log.info("Submitting document generation request: tenantId={}, templateId={}, variantId={}, format={}, filename={}",
                tenantId, templateId, variantId, format, filename);
        log.debug("Template data: {}", data);

        try {
            GenerationApi generationApi = apiClientFactory.createGenerationApi(baseUrl, apiKey);

            // Build the request using the constructor (Kotlin data class - immutable)
            GenerateDocumentRequest request = new GenerateDocumentRequest(
                    templateId,
                    variantId,
                    data,
                    null,  // versionId - not used when environmentId is specified
                    environmentId,
                    filename,
                    correlationId
            );

            GenerateDocument202Response response = generationApi.generateDocument(tenantId, request);

            UUID requestId = response.getRequestId();

            log.info("Document generation request submitted: requestId={}", requestId);

            return GeneratedDocument.builder()
                    .documentId(requestId.toString())
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
            GetGenerationJobStatus200Response response = generationApi.getGenerationJobStatus(tenantId, requestUuid);

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
            GenerationApi generationApi = apiClientFactory.createGenerationApi(baseUrl, apiKey);
            UUID documentUuid = UUID.fromString(documentId);
            File file = generationApi.downloadDocument(tenantId, documentUuid);

            // Read file contents and return as byte array
            byte[] content = Files.readAllBytes(file.toPath());

            // Clean up temp file
            if (!file.delete()) {
                log.warn("Failed to delete temporary file: {}", file.getAbsolutePath());
            }

            return content;
        } catch (IOException e) {
            log.error("Failed to read downloaded document for tenant {}, documentId {}: {}", tenantId, documentId, e.getMessage());
            throw new EpistolaApiException("Failed to read downloaded document", e);
        } catch (Exception e) {
            log.error("Failed to download document for tenant {}, documentId {}: {}", tenantId, documentId, e.getMessage());
            throw new EpistolaApiException("Failed to download document", e);
        }
    }

    // Mapping methods

    private TemplateInfo mapToTemplateInfo(ListTemplates200ResponseItemsInner dto) {
        return new TemplateInfo(
                dto.getId(),
                dto.getName(),
                null  // description is not available in ListTemplates200ResponseItemsInner
        );
    }

    private TemplateDetails mapToTemplateDetails(CreateTemplate201Response dto) {
        List<TemplateField> fields = extractFieldsFromSchema(dto.getSchema());

        return new TemplateDetails(
                dto.getId(),
                dto.getName(),
                fields
        );
    }

    /**
     * Extract fields from the template's JSON schema.
     * The schema is expected to be a JSON Schema object with "properties" defining the fields.
     */
    private List<TemplateField> extractFieldsFromSchema(Object schema) {
        if (schema == null) {
            return Collections.emptyList();
        }

        // The schema is typically a Map representing JSON Schema
        if (schema instanceof Map<?, ?> schemaMap) {
            Object properties = schemaMap.get("properties");
            Object requiredList = schemaMap.get("required");

            List<String> required = Collections.emptyList();
            if (requiredList instanceof List<?> list) {
                required = list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList();
            }

            if (properties instanceof Map<?, ?> propsMap) {
                List<String> finalRequired = required;
                return propsMap.entrySet().stream()
                        .map(entry -> {
                            String fieldName = String.valueOf(entry.getKey());
                            Map<?, ?> fieldDef = entry.getValue() instanceof Map<?, ?>
                                    ? (Map<?, ?>) entry.getValue()
                                    : Collections.emptyMap();

                            String type = fieldDef.get("type") != null
                                    ? String.valueOf(fieldDef.get("type"))
                                    : "string";
                            String description = fieldDef.get("description") != null
                                    ? String.valueOf(fieldDef.get("description"))
                                    : null;
                            boolean isRequired = finalRequired.contains(fieldName);

                            return new TemplateField(fieldName, type, isRequired, description);
                        })
                        .toList();
            }
        }

        return Collections.emptyList();
    }

    private EnvironmentInfo mapToEnvironmentInfo(ListEnvironments200ResponseItemsInner dto) {
        return new EnvironmentInfo(
                dto.getId(),
                dto.getName()
        );
    }

    private VariantInfo mapToVariantInfo(ListVariants200ResponseItemsInner dto) {
        // Convert Map<String, String> tags to List<String> for our domain model
        List<String> tagList = new ArrayList<>();
        if (dto.getTags() != null) {
            dto.getTags().forEach((key, value) -> tagList.add(key + "=" + value));
        }
        return new VariantInfo(
                dto.getId(),
                dto.getTemplateId(),
                dto.getTitle() != null ? dto.getTitle() : dto.getId(),  // fallback to id if no title
                tagList
        );
    }

    private GenerationJobDetail mapToGenerationJobDetail(GetGenerationJobStatus200Response response, String requestId) {
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

    private GenerationJobStatus mapToJobStatus(GetGenerationJobStatus200ResponseItemsInner.Status status) {
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
