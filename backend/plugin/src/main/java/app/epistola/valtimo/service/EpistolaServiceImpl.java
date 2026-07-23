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
package app.epistola.valtimo.service;

import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.AttributeDefinition;
import app.epistola.valtimo.domain.CatalogInfo;
import app.epistola.valtimo.domain.EnvironmentInfo;
import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GenerationJobResult;
import app.epistola.valtimo.domain.GenerationJobDetail;
import app.epistola.valtimo.domain.GenerationJobStatus;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateInfo;
import app.epistola.valtimo.domain.VariantInfo;
import app.epistola.client.api.AttributesApi;
import app.epistola.client.api.EnvironmentsApi;
import app.epistola.client.api.GenerationApi;
import app.epistola.client.api.TemplatesApi;
import app.epistola.client.api.VariantsApi;
import app.epistola.client.model.DocumentGenerationItemDto;
import app.epistola.client.model.EnvironmentDto;
import app.epistola.client.model.EnvironmentListResponse;
import app.epistola.client.model.GenerateDocumentRequest;
import app.epistola.client.model.GenerationJobResponse;
import app.epistola.client.model.PingRequest;
import app.epistola.client.model.PongDetailsDto;
import app.epistola.client.model.PongResponse;
import app.epistola.client.model.VariantSelectionAttribute;
import app.epistola.client.model.TemplateDto;
import app.epistola.client.model.TemplateListResponse;
import app.epistola.client.model.TemplateSummaryDto;
import app.epistola.client.model.VariantDto;
import app.epistola.client.model.VariantListResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Implementation of EpistolaService using the Epistola REST API client.
 */
@Slf4j
public class EpistolaServiceImpl implements EpistolaService {

    private final EpistolaApiClientFactory apiClientFactory;

    /** Retry attempts (beyond the first try) for idempotent reads on a transient failure. */
    private final int maxReadRetries;

    public EpistolaServiceImpl(EpistolaApiClientFactory apiClientFactory) {
        this(apiClientFactory, 2);
    }

    public EpistolaServiceImpl(EpistolaApiClientFactory apiClientFactory, int maxReadRetries) {
        this.apiClientFactory = apiClientFactory;
        this.maxReadRetries = maxReadRetries;
    }

    /**
     * Run an idempotent read, retrying on transient failures — connect/read timeouts and
     * connection errors ({@link ResourceAccessException}) or 5xx responses
     * ({@link HttpServerErrorException}). 4xx responses ({@link HttpClientErrorException})
     * and everything else propagate immediately without a retry.
     */
    private <T> T withRetry(String operation, Supplier<T> call) {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (HttpClientErrorException e) {
                throw e;
            } catch (ResourceAccessException | HttpServerErrorException e) {
                if (attempt >= maxReadRetries) {
                    throw e;
                }
                attempt++;
                long backoffMs = 200L * (1L << (attempt - 1));
                log.warn("Transient failure on {} (attempt {} of {}): {} — retrying in {}ms",
                        operation, attempt, maxReadRetries + 1, e.getMessage(), backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new EpistolaApiException("Interrupted while retrying " + operation, ie);
                }
            }
        }
    }

    @Override
    public List<CatalogInfo> getCatalogs(String baseUrl, String apiKey, String tenantId) {
        log.debug("Fetching catalogs for tenant: {}", tenantId);
        try {
            var response = apiClientFactory.createCatalogsApi(baseUrl, apiKey)
                    .listCatalogs(tenantId);

            if (response == null || response.getItems() == null) {
                return Collections.emptyList();
            }

            return response.getItems().stream()
                    .map(dto -> new CatalogInfo(dto.getId(), dto.getName(), dto.getType().getValue()))
                    .toList();
        } catch (Exception e) {
            log.error("Failed to fetch catalogs for tenant {}: {}", tenantId, e.getMessage());
            throw new EpistolaApiException("Failed to fetch catalogs", e);
        }
    }

    @Override
    public SystemInfo getSystemInfo(String baseUrl, String apiKey) {
        log.debug("Fetching Epistola system metadata");
        try {
            PongResponse response = withRetry("fetch system metadata", () ->
                    apiClientFactory.createSystemApi(baseUrl, apiKey).ping(new PingRequest()));
            PongDetailsDto details = response != null ? response.getDetails() : null;
            return new SystemInfo(
                    details != null ? details.getServerVersion() : null,
                    details != null ? details.getApiVersion() : null
            );
        } catch (Exception e) {
            log.debug("Failed to fetch Epistola system metadata: {}", e.getMessage());
            throw new EpistolaApiException("Failed to fetch system metadata", e);
        }
    }

    @Override
    public List<TemplateInfo> getTemplates(String baseUrl, String apiKey, String tenantId, String catalogId) {
        log.debug("Fetching templates for tenant: {}, catalog: {}", tenantId, catalogId);
        try {
            TemplatesApi templatesApi = apiClientFactory.createTemplatesApi(baseUrl, apiKey);
            TemplateListResponse response = templatesApi.listTemplates(tenantId, catalogId, null);

            if (response == null || response.getItems() == null) {
                return Collections.emptyList();
            }

            return response.getItems().stream()
                    .map(dto -> mapToTemplateInfo(dto, catalogId))
                    .toList();
        } catch (Exception e) {
            log.error("Failed to fetch templates for tenant {}: {}", tenantId, e.getMessage());
            throw new EpistolaApiException("Failed to fetch templates", e);
        }
    }

    @Override
    public TemplateDetails getTemplateDetails(String baseUrl, String apiKey, String tenantId, String catalogId, String templateId) {
        log.debug("Fetching template details for tenant: {}, catalog: {}, template: {}", tenantId, catalogId, templateId);
        try {
            TemplatesApi templatesApi = apiClientFactory.createTemplatesApi(baseUrl, apiKey);
            TemplateDto response = templatesApi.getTemplate(tenantId, catalogId, templateId);

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
    public List<AttributeDefinition> getAttributes(String baseUrl, String apiKey, String tenantId, String catalogId) {
        log.debug("Fetching attribute definitions for tenant: {}, catalog: {}", tenantId, catalogId);
        try {
            AttributesApi attributesApi = apiClientFactory.createAttributesApi(baseUrl, apiKey);
            var response = attributesApi.listAttributes(tenantId, catalogId);

            if (response == null || response.getItems() == null) {
                return Collections.emptyList();
            }

            return response.getItems().stream()
                    .map(dto -> new AttributeDefinition(dto.getKey(), dto.getDescription()))
                    .toList();
        } catch (Exception e) {
            log.error("Failed to fetch attribute definitions for tenant {}: {}", tenantId, e.getMessage());
            throw new EpistolaApiException("Failed to fetch attribute definitions", e);
        }
    }

    @Override
    public List<EnvironmentInfo> getEnvironments(String baseUrl, String apiKey, String tenantId) {
        log.debug("Fetching environments for tenant: {}", tenantId);
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
    public List<VariantInfo> getVariants(String baseUrl, String apiKey, String tenantId, String catalogId, String templateId) {
        log.debug("Fetching variants for tenant: {}, catalog: {}, template: {}", tenantId, catalogId, templateId);
        try {
            VariantsApi variantsApi = apiClientFactory.createVariantsApi(baseUrl, apiKey);
            VariantListResponse response = variantsApi.listVariants(tenantId, catalogId, templateId);

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
            String catalogId,
            String templateId,
            String variantId,
            List<VariantSelectionAttribute> variantAttributes,
            String environmentId,
            Map<String, Object> data,
            FileFormat format,
            String filename,
            String correlationId,
            String routingKey
    ) {
        log.debug("Submitting document generation request: tenantId={}, templateId={}, variantId={}, attributes={}, format={}, filename={}, routingKey={}",
                tenantId, templateId, variantId, variantAttributes, format, filename, routingKey);
        log.debug("Template data: {}", data);

        try {
            GenerationApi generationApi = apiClientFactory.createGenerationApi(baseUrl, apiKey);

            // Build the request using the constructor (Kotlin data class - immutable)
            // When neither variantId nor variantAttributes is set, Epistola server
            // resolves the default variant automatically (since v0.4.x).
            GenerateDocumentRequest request = new GenerateDocumentRequest(
                    catalogId,
                    templateId,
                    data,
                    variantId,          // nullable - omit when using attribute-based selection
                    variantAttributes,  // nullable - for attribute-based variant selection
                    null,               // versionId - not used when environmentId is specified
                    environmentId,
                    filename,
                    correlationId,
                    routingKey          // routes the result back to the submitting collector node
            );

            GenerationJobResponse response = generationApi.generateDocument(tenantId, request);

            log.debug("Document generation request submitted: requestId={}, status={}",
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
        log.debug("Fetching job status for tenant: {}, requestId: {}", tenantId, requestId);
        try {
            GenerationApi generationApi = apiClientFactory.createGenerationApi(baseUrl, apiKey);
            UUID requestUuid = UUID.fromString(requestId);
            app.epistola.client.model.GenerationJobDetail response = withRetry("getJobStatus",
                    () -> generationApi.getGenerationJobStatus(tenantId, requestUuid));

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
        log.debug("Downloading document for tenant: {}, documentId: {}", tenantId, documentId);
        try {
            // Use RestClient directly instead of the generated client, because the generated
            // client returns java.io.File which requires an HttpMessageConverter for
            // application/pdf → File that Spring doesn't provide out of the box.
            byte[] content = withRetry("downloadDocument", () -> apiClientFactory.createRestClient(baseUrl, apiKey)
                    .get()
                    .uri("/tenants/{tenantId}/documents/{documentId}", tenantId, documentId)
                    .accept(org.springframework.http.MediaType.APPLICATION_PDF)
                    .retrieve()
                    .body(byte[].class));

            if (content == null || content.length == 0) {
                throw new EpistolaApiException("Downloaded document is empty: " + documentId);
            }

            log.debug("Downloaded document {} ({} bytes)", documentId, content.length);
            return content;
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to download document for tenant {}, documentId {}: {}", tenantId, documentId, e.getMessage());
            throw new EpistolaApiException("Failed to download document", e);
        }
    }

    @Override
    public ImportCatalogResult importCatalog(String baseUrl, String apiKey, String tenantId, byte[] zipBytes, String catalogType) {
        log.info("Importing catalog ZIP ({} bytes) for tenant: {}, type: {}", zipBytes.length, tenantId, catalogType);
        try {
            org.springframework.core.io.ByteArrayResource zipResource = new org.springframework.core.io.ByteArrayResource(zipBytes) {
                @Override
                public String getFilename() {
                    return "catalog.zip";
                }
            };

            org.springframework.util.LinkedMultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
            body.add("file", zipResource);
            if (catalogType != null && !catalogType.isBlank()) {
                body.add("catalogType", catalogType);
            }
            // The server's Kotlin signature treats authoredMode as non-null even though the spec
            // documents it as optional with default MERGE — leaving it off NPEs the import.
            body.add("authoredMode", "MERGE");

            String responseJson = apiClientFactory.createRestClient(baseUrl, apiKey)
                    .post()
                    .uri("/tenants/{tenantId}/catalogs/import", tenantId)
                    .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            // Parse the response to extract import result fields
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseJson);

            String catalogKey = root.has("catalogKey") ? root.get("catalogKey").asText(null) : null;
            String catalogName = root.has("catalogName") ? root.get("catalogName").asText(null) : null;
            int installed = root.has("installed") ? root.get("installed").asInt(0) : 0;
            int updated = root.has("updated") ? root.get("updated").asInt(0) : 0;
            int failed = root.has("failed") ? root.get("failed").asInt(0) : 0;
            int total = root.has("total") ? root.get("total").asInt(0) : 0;

            log.info("Catalog import completed for tenant: {}, key={}, installed={}, updated={}, failed={}, total={}",
                    tenantId, catalogKey, installed, updated, failed, total);

            return new ImportCatalogResult(catalogKey, catalogName, installed, updated, failed, total);
        } catch (HttpStatusCodeException e) {
            // Preserve the downstream RFC-9457 problem detail (covers 4xx and 5xx). The suite's
            // import endpoint returns a structured body (e.g. catalog-schema-too-old with
            // version/baselineVersion) that callers translate and map to a correct status class.
            String body = e.getResponseBodyAsString();
            log.error("Failed to import catalog for tenant {}: {} {}", tenantId, e.getStatusCode(), body);
            ProblemBody problem = parseProblemBody(body);
            throw new EpistolaApiException(
                    problem.message() != null ? problem.message() : "Failed to import catalog",
                    e,
                    e.getStatusCode().value(),
                    problem.type(),
                    problem.extensions());
        } catch (Exception e) {
            log.error("Failed to import catalog for tenant {}: {}", tenantId, e.getMessage());
            throw new EpistolaApiException("Failed to import catalog", e);
        }
    }

    @Override
    public java.io.InputStream previewDocument(
            String baseUrl, String apiKey, String tenantId,
            String catalogId, String templateId, String variantId, String environmentId,
            Map<String, Object> data
    ) {
        log.debug("Previewing document for tenant: {}, catalog: {}, template: {}", tenantId, catalogId, templateId);
        try {
            var requestBody = new java.util.LinkedHashMap<String, Object>();
            requestBody.put("catalogId", catalogId);
            requestBody.put("templateId", templateId);
            requestBody.put("data", data);
            if (variantId != null) requestBody.put("variantId", variantId);
            if (environmentId != null) requestBody.put("environmentId", environmentId);

            byte[] content = apiClientFactory.createRestClient(baseUrl, apiKey)
                    .post()
                    .uri("/tenants/{tenantId}/documents/preview", tenantId)
                    .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.epistola.v1+json"))
                    .accept(org.springframework.http.MediaType.APPLICATION_PDF,
                            org.springframework.http.MediaType.parseMediaType("application/vnd.epistola.v1+json"))
                    .body(requestBody)
                    .retrieve()
                    .body(byte[].class);

            if (content == null || content.length == 0) {
                throw new EpistolaApiException("Preview returned empty content");
            }

            log.debug("Preview generated for tenant: {}, template: {}", tenantId, templateId);
            return new java.io.ByteArrayInputStream(content);
        } catch (EpistolaApiException e) {
            throw e;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.debug("Preview API error for tenant {}: {} {}", tenantId, e.getStatusCode(), e.getResponseBodyAsString());
            String detail = extractErrorMessage(e.getResponseBodyAsString());
            throw new EpistolaApiException(detail != null ? detail : e.getMessage(), e);
        } catch (Exception e) {
            log.debug("Failed to preview document for tenant {}: {}", tenantId, e.getMessage());
            throw new EpistolaApiException("Failed to preview document: " + e.getMessage(), e);
        }
    }

    /**
     * Extract a human-readable error message from an Epistola API error response body.
     * Prefers the RFC-9457 problem-detail fields ({@code detail}, then {@code title}),
     * falling back to the legacy {@code message}/{@code error} fields, then the raw body.
     */
    private String extractErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        try {
            var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
            for (String field : new String[]{"detail", "title", "message", "error"}) {
                if (tree.has(field) && !tree.get(field).isNull()) {
                    return tree.get(field).asText();
                }
            }
        } catch (Exception ignored) {
            // Not JSON, return raw body
        }
        return responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody;
    }

    /**
     * A parsed downstream error body: a human-readable {@code message}, the RFC-9457 problem
     * {@code type} URI (or null), and the structured extension members relevant to catalog
     * import ({@code version} / {@code baselineVersion}).
     */
    private record ProblemBody(String message, String type, Map<String, Object> extensions) {}

    /** Parse an Epistola error response body into its message + RFC-9457 type/extensions. */
    private ProblemBody parseProblemBody(String responseBody) {
        String message = extractErrorMessage(responseBody);
        String type = null;
        Map<String, Object> extensions = new java.util.LinkedHashMap<>();
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
                if (tree.has("type") && !tree.get("type").isNull()) {
                    type = tree.get("type").asText();
                }
                for (String field : new String[]{"version", "baselineVersion"}) {
                    if (tree.has(field) && tree.get(field).isInt()) {
                        extensions.put(field, tree.get(field).asInt());
                    }
                }
            } catch (Exception ignored) {
                // Not JSON — message already holds the raw body.
            }
        }
        return new ProblemBody(message, type, extensions);
    }

    // Mapping methods

    private TemplateInfo mapToTemplateInfo(TemplateSummaryDto dto, String catalogId) {
        return new TemplateInfo(
                dto.getId(),
                dto.getName(),
                null,      // description is not available in TemplateSummaryDto
                catalogId,
                null       // catalogName is not available in TemplateSummaryDto
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
