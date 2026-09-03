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
import app.epistola.valtimo.schema.JsonSchemaMappingAnalyzer;
import app.epistola.client.api.AttributesApi;
import app.epistola.client.api.CatalogsApi;
import app.epistola.client.api.EnvironmentsApi;
import app.epistola.client.api.GenerationApi;
import app.epistola.client.api.TemplatesApi;
import app.epistola.client.api.VariantsApi;
import app.epistola.client.model.DocumentGenerationItemDto;
import app.epistola.client.model.EnvironmentDto;
import app.epistola.client.model.EnvironmentListResponse;
import app.epistola.client.model.GenerateDocumentRequest;
import app.epistola.client.model.GenerationJobResponse;
import app.epistola.client.error.ProblemDetailException;
import app.epistola.client.model.ImportCatalogResponse;
import app.epistola.client.model.PageMeta;
import app.epistola.client.model.PreviewDocumentRequest;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Implementation of EpistolaService using the Epistola REST API client.
 */
@Slf4j
public class EpistolaServiceImpl implements EpistolaService {

    private static final int LIST_PAGE_SIZE = 100;
    private static final int MAX_LIST_PAGES = 100;

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
     * connection errors ({@link ResourceAccessException}) or 5xx responses. 4xx responses
     * and everything else propagate immediately without a retry.
     * <p>
     * The decision is made on the response status rather than the exception class: since the
     * client installs the RFC 9457 status handler, an error carrying {@code problem+json}
     * arrives as {@link ProblemDetailException}, which extends {@link RestClientResponseException}
     * directly and is <em>not</em> an {@code HttpServerErrorException}. Branching on the class
     * would silently stop retrying every problem-shaped 5xx.
     */
    private <T> T withRetry(String operation, Supplier<T> call) {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (RestClientResponseException | ResourceAccessException e) {
                if (e instanceof RestClientResponseException response
                        && !response.getStatusCode().is5xxServerError()) {
                    throw e;
                }
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

    private <T, R> List<T> fetchAllPages(
            String operation,
            Function<Integer, R> fetchPage,
            Function<R, List<T>> itemsExtractor,
            Function<R, PageMeta> pageExtractor
    ) {
        List<T> results = new ArrayList<>();
        int pageNumber = 0;
        while (true) {
            R response = fetchPage.apply(pageNumber);
            if (response == null) {
                return results;
            }

            List<T> items = itemsExtractor.apply(response);
            if (items != null) {
                results.addAll(items);
            }

            PageMeta page = pageExtractor.apply(response);
            if (page == null || page.getTotalPages() <= pageNumber + 1) {
                return results;
            }

            pageNumber++;
            if (pageNumber >= MAX_LIST_PAGES) {
                throw new EpistolaApiException("Refusing to fetch more than " + MAX_LIST_PAGES + " pages for " + operation);
            }
        }
    }

    @Override
    public List<CatalogInfo> getCatalogs(String baseUrl, String apiKey, String tenantId) {
        log.debug("Fetching catalogs for tenant: {}", tenantId);
        try {
            CatalogsApi catalogsApi = apiClientFactory.createCatalogsApi(baseUrl, apiKey);
            var catalogs = fetchAllPages(
                    "catalogs",
                    page -> catalogsApi.listCatalogs(tenantId, page, LIST_PAGE_SIZE, null, null),
                    response -> response.getItems(),
                    response -> response.getPage()
            );

            return catalogs.stream()
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
            var templates = fetchAllPages(
                    "templates",
                    page -> templatesApi.listTemplates(tenantId, catalogId, null, page, LIST_PAGE_SIZE, null, null),
                    TemplateListResponse::getItems,
                    TemplateListResponse::getPage
            );

            return templates.stream()
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
            var attributes = fetchAllPages(
                    "attributes",
                    page -> attributesApi.listAttributes(tenantId, catalogId, page, LIST_PAGE_SIZE, null, null),
                    response -> response.getItems(),
                    response -> response.getPage()
            );

            return attributes.stream()
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
            var environments = fetchAllPages(
                    "environments",
                    page -> environmentsApi.listEnvironments(tenantId, page, LIST_PAGE_SIZE, null, null),
                    EnvironmentListResponse::getItems,
                    EnvironmentListResponse::getPage
            );

            return environments.stream()
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
            var variants = fetchAllPages(
                    "variants",
                    page -> variantsApi.listVariants(tenantId, catalogId, templateId, page, LIST_PAGE_SIZE, null, null),
                    VariantListResponse::getItems,
                    VariantListResponse::getPage
            );

            return variants.stream()
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
        UUID id = parseDocumentId(documentId);
        try {
            // The long-running client: a document download has no meaningful read timeout.
            Resource resource = withRetry("downloadDocument", () ->
                    apiClientFactory.createLongRunningGenerationApi(baseUrl, apiKey)
                            .downloadDocument(tenantId, id));

            byte[] content = resource.getContentAsByteArray();
            if (content.length == 0) {
                throw new EpistolaApiException("Downloaded document is empty: " + documentId);
            }

            log.debug("Downloaded document {} ({} bytes)", documentId, content.length);
            return content;
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to download document for tenant {}, documentId {}: {}", tenantId, documentId, e.getMessage());
            throw toApiException("Failed to download document", e);
        }
    }

    /**
     * The generated client types {@code documentId} as a {@link UUID}. Rejecting a malformed id
     * here keeps the failure a 400-shaped {@link EpistolaApiException} instead of an
     * {@link IllegalArgumentException} escaping as a 500.
     */
    private UUID parseDocumentId(String documentId) {
        try {
            return UUID.fromString(documentId);
        } catch (IllegalArgumentException e) {
            throw new EpistolaApiException("Not a valid Epistola document id: " + documentId, e, 400, null, Map.of());
        }
    }

    public ImportCatalogResult importCatalog(String baseUrl, String apiKey, String tenantId, byte[] zipBytes, String catalogType) {
        log.info("Importing catalog ZIP ({} bytes) for tenant: {}, type: {}", zipBytes.length, tenantId, catalogType);
        try {
            Resource zipResource = new ByteArrayResource(zipBytes) {
                @Override
                public String getFilename() {
                    return "catalog.zip";
                }
            };

            // authoredMode is sent explicitly: the server's Kotlin signature treats it as
            // non-null even though the spec documents it as optional with default MERGE.
            ImportCatalogResponse response = apiClientFactory.createLongRunningCatalogsApi(baseUrl, apiKey)
                    .importCatalog(
                            tenantId,
                            zipResource,
                            parseCatalogType(catalogType),
                            CatalogsApi.AuthoredModeImportCatalog.MERGE);

            log.info("Catalog import completed for tenant: {}, key={}, installed={}, updated={}, failed={}, total={}",
                    tenantId, response.getCatalogKey(), response.getInstalled(), response.getUpdated(),
                    response.getFailed(), response.getTotal());

            return new ImportCatalogResult(
                    response.getCatalogKey(),
                    response.getCatalogName(),
                    response.getInstalled(),
                    response.getUpdated(),
                    response.getFailed(),
                    response.getTotal());
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to import catalog for tenant {}: {}", tenantId, e.getMessage());
            // Preserves the downstream RFC-9457 problem detail (covers 4xx and 5xx): the suite's
            // import endpoint returns a structured body (e.g. catalog-schema-too-old with
            // version/baselineVersion) that callers translate and map to a correct status class.
            throw toApiException("Failed to import catalog", e);
        }
    }

    /** Map the plugin's catalog-type string onto the generated enum, defaulting to AUTHORED. */
    private CatalogsApi.CatalogTypeImportCatalog parseCatalogType(String catalogType) {
        if (catalogType == null || catalogType.isBlank()) {
            return CatalogsApi.CatalogTypeImportCatalog.AUTHORED;
        }
        for (CatalogsApi.CatalogTypeImportCatalog value : CatalogsApi.CatalogTypeImportCatalog.values()) {
            if (value.getValue().equalsIgnoreCase(catalogType)) {
                return value;
            }
        }
        throw new EpistolaApiException("Unknown catalog type: " + catalogType);
    }

    public java.io.InputStream previewDocument(
            String baseUrl, String apiKey, String tenantId,
            String catalogId, String templateId, String variantId, String environmentId,
            Map<String, Object> data
    ) {
        log.debug("Previewing document for tenant: {}, catalog: {}, template: {}", tenantId, catalogId, templateId);
        try {
            var request = new PreviewDocumentRequest(
                    catalogId,
                    templateId,
                    data,
                    variantId,
                    null,           // attributes — the plugin selects a variant explicitly or by default
                    null,           // versionId — resolved from environmentId, or latest published
                    environmentId);

            // The long-running client: a preview render has no meaningful read timeout.
            Resource resource = apiClientFactory.createLongRunningGenerationApi(baseUrl, apiKey)
                    .previewDocument(tenantId, request);

            byte[] content = resource.getContentAsByteArray();
            if (content.length == 0) {
                throw new EpistolaApiException("Preview returned empty content");
            }

            log.debug("Preview generated for tenant: {}, template: {}", tenantId, templateId);
            return new java.io.ByteArrayInputStream(content);
        } catch (EpistolaApiException e) {
            throw e;
        } catch (RestClientResponseException e) {
            log.debug("Preview API error for tenant {}: {} {}", tenantId, e.getStatusCode(), e.getResponseBodyAsString());
            throw toApiException("Failed to preview document", e);
        } catch (Exception e) {
            log.debug("Failed to preview document for tenant {}: {}", tenantId, e.getMessage());
            throw new EpistolaApiException("Failed to preview document: " + e.getMessage(), e);
        }
    }

    /**
     * Wrap a downstream failure, preserving the RFC 9457 problem detail when there is one.
     * <p>
     * The client's status handler parses {@code application/problem+json} into a
     * {@link ProblemDetailException}, so the type URI and the extension members Epistola sends
     * (for example {@code version} / {@code baselineVersion} on {@code catalog-schema-too-old})
     * arrive already parsed; callers translate them and map the failure to a correct status
     * class. A non-problem error body keeps its status but carries no type or extensions.
     */
    private EpistolaApiException toApiException(String fallbackMessage, Throwable cause) {
        if (cause instanceof ProblemDetailException problem) {
            String message = problem.getDetail() != null ? problem.getDetail() : problem.getTitle();
            return new EpistolaApiException(
                    message != null ? message : fallbackMessage,
                    problem,
                    problem.getProblemStatus(),
                    problem.getType().toString(),
                    problem.getExtensions());
        }
        if (cause instanceof RestClientResponseException response) {
            return new EpistolaApiException(
                    fallbackMessage,
                    response,
                    response.getStatusCode().value(),
                    null,
                    Map.of());
        }
        return new EpistolaApiException(fallbackMessage, cause);
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
        JsonSchemaMappingAnalyzer.Analysis schemaAnalysis = new JsonSchemaMappingAnalyzer().analyze(schemaSource);

        return new TemplateDetails(
                dto.getId(),
                dto.getName(),
                schemaAnalysis.fields(),
                schemaSource,
                schemaAnalysis.support()
        );
    }

    /**
     * Extract fields from the template's JSON schema.
     * Recursively processes nested objects and arrays to produce a tree of TemplateField nodes.
     */
    List<TemplateField> extractFieldsFromSchema(Object schema) {
        return new JsonSchemaMappingAnalyzer().analyze(schema).fields();
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
