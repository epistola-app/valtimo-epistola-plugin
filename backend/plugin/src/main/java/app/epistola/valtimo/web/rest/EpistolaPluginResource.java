package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.domain.AttributeDefinition;
import app.epistola.valtimo.domain.CatalogInfo;
import app.epistola.valtimo.domain.EnvironmentInfo;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateInfo;
import app.epistola.valtimo.domain.VariantInfo;
import app.epistola.valtimo.expression.ExpressionFunctionInfo;
import app.epistola.valtimo.expression.ExpressionFunctionRegistry;
import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.PreviewService;
import app.epistola.valtimo.service.ProcessVariableDiscoveryService;
import app.epistola.valtimo.service.RetryFormService;
import app.epistola.valtimo.service.VariableSuggestionService;
import app.epistola.valtimo.web.rest.dto.EvaluationRequest;
import app.epistola.valtimo.web.rest.dto.EvaluationResult;
import app.epistola.valtimo.web.rest.dto.PreviewRequest;
import app.epistola.valtimo.web.rest.dto.ValidateMappingRequest;
import app.epistola.valtimo.web.rest.dto.ValidateMappingResponse;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.contract.annotation.SkipComponentScan;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for Epistola plugin operations.
 * Provides endpoints for fetching templates and template details.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/plugin/epistola")
@SkipComponentScan
@RequiredArgsConstructor
public class EpistolaPluginResource {

    private final PluginService pluginService;
    private final EpistolaService epistolaService;
    private final ProcessVariableDiscoveryService processVariableDiscoveryService;
    private final RetryFormService retryFormService;
    private final PreviewService previewService;
    private final ExpressionFunctionRegistry expressionFunctionRegistry;
    private final VariableSuggestionService variableSuggestionService;
    private final JsonataMappingService jsonataMappingService;
    private final com.ritense.document.service.DocumentService documentService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper2;

    /**
     * Get all available catalogs for a plugin configuration.
     *
     * @param configurationId The plugin configuration ID
     * @return List of available catalogs
     */
    @GetMapping("/configurations/{configurationId}/catalogs")
    public ResponseEntity<List<CatalogInfo>> getCatalogs(
            @PathVariable("configurationId") UUID configurationId
    ) {
        log.debug("Fetching catalogs for plugin configuration: {}", configurationId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        List<CatalogInfo> catalogs = epistolaService.getCatalogs(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId()
        );

        return ResponseEntity.ok(catalogs);
    }

    /**
     * Get all available templates for a plugin configuration.
     *
     * @param configurationId The plugin configuration ID
     * @param catalogId       The catalog ID
     * @return List of available templates
     */
    @GetMapping("/configurations/{configurationId}/templates")
    public ResponseEntity<List<TemplateInfo>> getTemplates(
            @PathVariable("configurationId") UUID configurationId,
            @RequestParam("catalogId") String catalogId
    ) {
        log.debug("Fetching templates for plugin configuration: {}, catalog: {}", configurationId, catalogId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        List<TemplateInfo> templates = epistolaService.getTemplates(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId(),
                catalogId
        );

        return ResponseEntity.ok(templates);
    }

    /**
     * Get template details including its fields.
     *
     * @param configurationId The plugin configuration ID
     * @param catalogId       The catalog ID
     * @param templateId      The template ID
     * @return Template details with fields
     */
    @GetMapping("/configurations/{configurationId}/templates/{templateId}")
    public ResponseEntity<TemplateDetails> getTemplateDetails(
            @PathVariable("configurationId") UUID configurationId,
            @PathVariable("templateId") String templateId,
            @RequestParam("catalogId") String catalogId
    ) {
        log.debug("Fetching template details for plugin configuration: {}, catalog: {}, template: {}",
                configurationId, catalogId, templateId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        TemplateDetails templateDetails = epistolaService.getTemplateDetails(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId(),
                catalogId,
                templateId
        );

        return ResponseEntity.ok(templateDetails);
    }

    /**
     * Get all attribute definitions for a plugin configuration's tenant and catalog.
     * These define the keys that can be used for variant selection.
     *
     * @param configurationId The plugin configuration ID
     * @param catalogId       The catalog ID
     * @return List of attribute definitions
     */
    @GetMapping("/configurations/{configurationId}/attributes")
    public ResponseEntity<List<AttributeDefinition>> getAttributes(
            @PathVariable("configurationId") UUID configurationId,
            @RequestParam("catalogId") String catalogId
    ) {
        log.debug("Fetching attribute definitions for plugin configuration: {}, catalog: {}", configurationId, catalogId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        List<AttributeDefinition> attributes = epistolaService.getAttributes(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId(),
                catalogId
        );

        return ResponseEntity.ok(attributes);
    }

    /**
     * Get all available environments for a plugin configuration.
     *
     * @param configurationId The plugin configuration ID
     * @return List of available environments
     */
    @GetMapping("/configurations/{configurationId}/environments")
    public ResponseEntity<List<EnvironmentInfo>> getEnvironments(
            @PathVariable("configurationId") UUID configurationId
    ) {
        log.debug("Fetching environments for plugin configuration: {}", configurationId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        List<EnvironmentInfo> environments = epistolaService.getEnvironments(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId()
        );

        return ResponseEntity.ok(environments);
    }

    /**
     * Get all variants for a specific template.
     *
     * @param configurationId The plugin configuration ID
     * @param catalogId       The catalog ID
     * @param templateId      The template ID
     * @return List of variants for the template
     */
    @GetMapping("/configurations/{configurationId}/templates/{templateId}/variants")
    public ResponseEntity<List<VariantInfo>> getVariants(
            @PathVariable("configurationId") UUID configurationId,
            @PathVariable("templateId") String templateId,
            @RequestParam("catalogId") String catalogId
    ) {
        log.debug("Fetching variants for plugin configuration: {}, catalog: {}, template: {}",
                configurationId, catalogId, templateId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        List<VariantInfo> variants = epistolaService.getVariants(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId(),
                catalogId,
                templateId
        );

        return ResponseEntity.ok(variants);
    }

    /**
     * Validate that a data mapping covers all required template fields.
     *
     * @param configurationId The plugin configuration ID
     * @param catalogId       The catalog ID
     * @param templateId      The template ID
     * @param request         The data mapping to validate
     * @return Validation result with missing required fields (if any)
     */
    @PostMapping("/configurations/{configurationId}/templates/{templateId}/validate-mapping")
    public ResponseEntity<ValidateMappingResponse> validateMapping(
            @PathVariable("configurationId") UUID configurationId,
            @PathVariable("templateId") String templateId,
            @RequestParam("catalogId") String catalogId,
            @RequestBody ValidateMappingRequest request
    ) {
        log.debug("Validating mapping for plugin configuration: {}, catalog: {}, template: {}",
                configurationId, catalogId, templateId);

        // TODO: implement JSONata-aware validation (parse expression, check output keys against template fields)
        return ResponseEntity.ok(new ValidateMappingResponse(true, List.of()));
    }

    /**
     * Discover process variable names for a given process definition.
     * Combines variables from historic process instances and BPMN model definitions.
     *
     * @param processDefinitionKey The process definition key
     * @return Sorted list of discovered variable names
     */
    @GetMapping("/process-variables")
    public ResponseEntity<List<String>> getProcessVariables(
            @RequestParam("processDefinitionKey") String processDefinitionKey
    ) {
        log.debug("Discovering process variables for process definition: {}", processDefinitionKey);

        List<String> variables = processVariableDiscoveryService.discoverVariables(processDefinitionKey);
        return ResponseEntity.ok(variables);
    }

    /**
     * Get all available variable suggestions for autocompletion in JSONata expressions.
     * Returns document fields (from JSON Schema) and process variables grouped by source.
     *
     * @param caseDefinitionKey    The case definition key (for document schema)
     * @param processDefinitionKey The process definition key (for process variables)
     * @return Variable paths grouped by source ($doc, $pv)
     */
    @GetMapping("/variable-suggestions")
    public ResponseEntity<VariableSuggestionService.VariableSuggestions> getVariableSuggestions(
            @RequestParam(value = "caseDefinitionKey", required = false) String caseDefinitionKey,
            @RequestParam(value = "processDefinitionKey", required = false) String processDefinitionKey
    ) {
        log.debug("Fetching variable suggestions for case={}, process={}", caseDefinitionKey, processDefinitionKey);
        return ResponseEntity.ok(variableSuggestionService.getSuggestions(caseDefinitionKey, processDefinitionKey));
    }

    /**
     * Evaluate a JSONata data mapping expression against a real document.
     * Returns the resolved JSON output that would be sent to Epistola.
     */
    @PostMapping("/evaluate-mapping")
    @SuppressWarnings("unchecked")
    public ResponseEntity<EvaluationResult> evaluateMapping(@RequestBody EvaluationRequest request) {
        log.debug("Evaluating mapping expression against document {}", request.documentId());
        try {
            var evalCtx = app.epistola.valtimo.mapping.EvaluationContext.builder()
                    .expression(request.expression())
                    .documentResolver(this::loadDocumentContent)
                    .documentId(request.documentId())
                    .build();
            Map<String, Object> result = jsonataMappingService.evaluate(evalCtx);
            return ResponseEntity.ok(EvaluationResult.success(result));
        } catch (Exception e) {
            return ResponseEntity.ok(EvaluationResult.failure(e.getMessage()));
        }
    }

    /**
     * List all available expression functions that can be used in JSONata expressions.
     *
     * @return List of expression functions with their overload signatures
     */
    @GetMapping("/expression-functions")
    public ResponseEntity<List<ExpressionFunctionInfo>> getExpressionFunctions() {
        return ResponseEntity.ok(expressionFunctionRegistry.listFunctions());
    }

    /**
     * Download a generated document directly from Epistola.
     * Resolves the plugin configuration by tenantId and proxies the download.
     *
     * @param documentId The Epistola document ID
     * @param tenantId   The Epistola tenant ID (used to find the correct plugin configuration)
     * @param filename   The desired filename for the download (defaults to "document.pdf")
     * @return The document bytes with PDF content type and attachment disposition
     */
    @GetMapping("/documents/{documentId}/download")
    public ResponseEntity<byte[]> downloadDocument(
            @PathVariable("documentId") String documentId,
            @RequestParam("tenantId") String tenantId,
            @RequestParam(value = "filename", defaultValue = "document.pdf") String filename
    ) {
        log.debug("Downloading document {} for tenantId={}", documentId, tenantId);

        EpistolaPlugin plugin = findPluginByTenantId(tenantId);
        if (plugin == null) {
            log.warn("No Epistola plugin configuration found for tenantId='{}'", tenantId);
            return ResponseEntity.notFound().build();
        }

        byte[] content = epistolaService.downloadDocument(
                plugin.getBaseUrl(), plugin.getApiKey(), tenantId, documentId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename)
                .build());

        return ResponseEntity.ok().headers(headers).body(content);
    }

    /**
     * Get a dynamically generated Formio form for retrying a failed document generation.
     *
     * @param processInstanceId The process instance ID (used to find the process definition)
     * @param documentId        The Valtimo document ID (used to resolve doc: expressions)
     * @param sourceActivityId  The BPMN activity ID of the original generate-document service task (optional)
     * @return A Formio form definition with prefilled values
     */
    @GetMapping("/retry-form")
    public ResponseEntity<ObjectNode> getRetryForm(
            @RequestParam("processInstanceId") String processInstanceId,
            @RequestParam(value = "documentId", required = false) String documentId,
            @RequestParam(value = "sourceActivityId", required = false) String sourceActivityId
    ) {
        try {
            ObjectNode form = retryFormService.generateRetryForm(processInstanceId, documentId, sourceActivityId);
            return ResponseEntity.ok(form);
        } catch (RetryFormService.RetryFormException e) {
            log.warn("Failed to generate retry form: {}", e.getMessage());
            return switch (e.getReason()) {
                case PROCESS_NOT_FOUND, LINK_NOT_FOUND -> ResponseEntity.notFound().build();
                case AMBIGUOUS_ACTIVITY, MISSING_TEMPLATE, NO_DOCUMENT_ID -> ResponseEntity.badRequest().build();
            };
        }
    }

    /**
     * Discover all previewable document sources for a given Valtimo document.
     * Returns generate-document process links from running process instances.
     *
     * @param documentId The Valtimo document ID
     * @return List of preview sources
     */
    @GetMapping("/preview-sources")
    public ResponseEntity<?> getPreviewSources(@RequestParam("documentId") String documentId) {
        try {
            var sources = previewService.getPreviewSources(documentId);
            return ResponseEntity.ok(sources);
        } catch (Exception e) {
            log.debug("Failed to discover preview sources: {}", e.getMessage());
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Failed to discover preview sources: " + e.getMessage()));
        }
    }

    /**
     * Preview a document by "dry-running" the generate-document process link.
     * <p>
     * Resolves the data mapping, merges with optional overrides, and calls Epistola's
     * preview API to render a PDF without creating a generation job.
     *
     * @param request The preview request with document context and optional overrides
     * @return Rendered PDF (inline) or error details
     */
    @PostMapping("/preview")
    public ResponseEntity<?> previewDocument(@RequestBody PreviewRequest request) {
        if (request.documentId() == null) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "documentId is required"));
        }

        try {
            java.io.InputStream pdfStream = previewService.generatePreview(request);

            var resource = new org.springframework.core.io.InputStreamResource(pdfStream);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(ContentDisposition.inline()
                    .filename("preview.pdf")
                    .build());

            return ResponseEntity.ok().headers(headers).body(resource);
        } catch (PreviewService.PreviewException e) {
            log.debug("Preview unavailable: {}", e.getMessage());
            return switch (e.getReason()) {
                case PROCESS_NOT_FOUND, LINK_NOT_FOUND -> ResponseEntity.notFound().build();
                case AMBIGUOUS_ACTIVITY, MISSING_TEMPLATE, MISSING_CONTEXT ->
                        ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                case RENDER_FAILED ->
                        ResponseEntity.unprocessableEntity().body(Map.of(
                                "error", "Preview could not be generated",
                                "details", e.getMessage()));
            };
        }
    }

    /**
     * Find the Epistola plugin configuration matching the given tenantId.
     *
     * @return The matching plugin instance, or null if not found
     */
    private EpistolaPlugin findPluginByTenantId(String tenantId) {
        List<?> configurations = pluginService.findPluginConfigurations(
                EpistolaPlugin.class, props -> true);

        for (Object config : configurations) {
            try {
                EpistolaPlugin plugin = (EpistolaPlugin) pluginService.createInstance(
                        (PluginConfiguration) config);
                if (tenantId.equals(plugin.getTenantId())) {
                    return plugin;
                }
            } catch (Exception e) {
                log.warn("Failed to create plugin instance from configuration: {}", e.getMessage());
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadDocumentContent(String documentId) {
        try {
            var doc = documentService.findBy(
                    com.ritense.document.domain.impl.JsonSchemaDocumentId.existingId(java.util.UUID.fromString(documentId)));
            if (doc.isPresent()) {
                return (Map<String, Object>) objectMapper2.convertValue(
                        doc.get().content().asJson(), Map.class);
            }
            return Map.of();
        } catch (Exception e) {
            log.warn("Failed to load document content for {}: {}", documentId, e.getMessage());
            return Map.of();
        }
    }
}
