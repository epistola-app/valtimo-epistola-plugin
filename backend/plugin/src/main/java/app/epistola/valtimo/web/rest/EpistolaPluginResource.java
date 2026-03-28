package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.domain.EnvironmentInfo;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateInfo;
import app.epistola.valtimo.domain.VariantInfo;
import app.epistola.valtimo.service.DataMappingResolverService;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.ProcessVariableDiscoveryService;
import app.epistola.valtimo.service.RetryFormService;
import app.epistola.valtimo.service.TemplateMappingValidator;
import app.epistola.valtimo.web.rest.dto.PreviewRequest;
import app.epistola.valtimo.web.rest.dto.ValidateMappingRequest;
import app.epistola.valtimo.web.rest.dto.ValidateMappingResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.plugin.service.PluginService;
import com.ritense.processlink.domain.ProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import com.ritense.valtimo.contract.annotation.SkipComponentScan;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import com.ritense.valtimo.operaton.service.OperatonRepositoryService;
import org.operaton.bpm.engine.RuntimeService;
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

import java.util.LinkedHashMap;
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
    private final ProcessLinkService processLinkService;
    private final OperatonRepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final DataMappingResolverService dataMappingResolverService;
    private final ObjectMapper objectMapper;

    /**
     * Get all available templates for a plugin configuration.
     *
     * @param configurationId The plugin configuration ID
     * @return List of available templates
     */
    @GetMapping("/configurations/{configurationId}/templates")
    public ResponseEntity<List<TemplateInfo>> getTemplates(
            @PathVariable("configurationId") UUID configurationId
    ) {
        log.debug("Fetching templates for plugin configuration: {}", configurationId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        List<TemplateInfo> templates = epistolaService.getTemplates(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId()
        );

        return ResponseEntity.ok(templates);
    }

    /**
     * Get template details including its fields.
     *
     * @param configurationId The plugin configuration ID
     * @param templateId      The template ID
     * @return Template details with fields
     */
    @GetMapping("/configurations/{configurationId}/templates/{templateId}")
    public ResponseEntity<TemplateDetails> getTemplateDetails(
            @PathVariable("configurationId") UUID configurationId,
            @PathVariable("templateId") String templateId
    ) {
        log.debug("Fetching template details for plugin configuration: {}, template: {}",
                configurationId, templateId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        TemplateDetails templateDetails = epistolaService.getTemplateDetails(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId(),
                templateId
        );

        return ResponseEntity.ok(templateDetails);
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
     * @param templateId      The template ID
     * @return List of variants for the template
     */
    @GetMapping("/configurations/{configurationId}/templates/{templateId}/variants")
    public ResponseEntity<List<VariantInfo>> getVariants(
            @PathVariable("configurationId") UUID configurationId,
            @PathVariable("templateId") String templateId
    ) {
        log.debug("Fetching variants for plugin configuration: {}, template: {}",
                configurationId, templateId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        List<VariantInfo> variants = epistolaService.getVariants(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId(),
                templateId
        );

        return ResponseEntity.ok(variants);
    }

    /**
     * Validate that a data mapping covers all required template fields.
     *
     * @param configurationId The plugin configuration ID
     * @param templateId      The template ID
     * @param request         The data mapping to validate
     * @return Validation result with missing required fields (if any)
     */
    @PostMapping("/configurations/{configurationId}/templates/{templateId}/validate-mapping")
    public ResponseEntity<ValidateMappingResponse> validateMapping(
            @PathVariable("configurationId") UUID configurationId,
            @PathVariable("templateId") String templateId,
            @RequestBody ValidateMappingRequest request
    ) {
        log.debug("Validating mapping for plugin configuration: {}, template: {}",
                configurationId, templateId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        TemplateDetails templateDetails = epistolaService.getTemplateDetails(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId(),
                templateId
        );

        List<String> missingFields = TemplateMappingValidator.findMissingRequiredFields(
                templateDetails.fields(),
                request.dataMapping()
        );

        return ResponseEntity.ok(new ValidateMappingResponse(
                missingFields.isEmpty(),
                missingFields
        ));
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
     * Preview a document by "dry-running" the generate-document process link.
     * <p>
     * Resolves the data mapping from the process link, merges with optional overrides,
     * and returns the resolved data as a mock preview (JSON). When the Epistola preview
     * API is available, this will return a rendered PDF instead.
     *
     * @param request The preview request with document context and optional overrides
     * @return Mock preview as JSON (Phase 1) or PDF (later)
     */
    @PostMapping("/preview")
    public ResponseEntity<?> previewDocument(@RequestBody PreviewRequest request) {
        log.debug("Generating preview for processDefinitionKey={}, sourceActivityId={}, documentId={}",
                request.processDefinitionKey(), request.sourceActivityId(), request.documentId());

        if (request.documentId() == null) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "documentId is required"));
        }
        if (request.processDefinitionKey() == null && request.processInstanceId() == null) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Either processDefinitionKey or processInstanceId is required"));
        }

        // 1. Find the process definition (from key or process instance)
        String processDefinitionId;
        if (request.processDefinitionKey() != null) {
            var processDefinition = repositoryService.findLatestProcessDefinition(request.processDefinitionKey());
            if (processDefinition == null) {
                return ResponseEntity.notFound().build();
            }
            processDefinitionId = processDefinition.getId();
        } else {
            var processInstance = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(request.processInstanceId())
                    .singleResult();
            if (processInstance == null) {
                return ResponseEntity.notFound().build();
            }
            processDefinitionId = processInstance.getProcessDefinitionId();
        }

        // 2. Find the generate-document process link (auto-discover if sourceActivityId not provided)
        PluginProcessLink processLink;
        if (request.sourceActivityId() != null && !request.sourceActivityId().isBlank()) {
            processLink = findPluginProcessLink(processDefinitionId, request.sourceActivityId());
        } else {
            // Auto-discover: find the single generate-document process link
            List<PluginProcessLink> generateLinks = processLinkService.getProcessLinks(processDefinitionId).stream()
                    .filter(PluginProcessLink.class::isInstance)
                    .map(PluginProcessLink.class::cast)
                    .filter(link -> "generate-document".equals(link.getPluginActionDefinitionKey()))
                    .toList();
            processLink = generateLinks.size() == 1 ? generateLinks.get(0) : null;
        }
        if (processLink == null) {
            return ResponseEntity.notFound().build();
        }

        // 3. Extract config from process link
        ObjectNode actionProps = processLink.getActionProperties();
        String templateId = actionProps.has("templateId") ? actionProps.get("templateId").asText() : null;
        if (templateId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No templateId in process link"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> dataMapping = actionProps.has("dataMapping")
                ? objectMapper.convertValue(actionProps.get("dataMapping"), Map.class)
                : Map.of();

        // 4. Resolve data mapping
        Map<String, Object> resolvedData = dataMappingResolverService.resolveMapping(
                request.documentId(), dataMapping);

        // 5. Deep-merge with overrides
        if (request.overrides() != null && !request.overrides().isEmpty()) {
            log.debug("Preview overrides: {}", request.overrides());
            resolvedData = deepMerge(resolvedData, request.overrides());
        }
        log.debug("Preview merged data: {}", resolvedData);

        // 6. Get plugin config and extract variant/environment
        EpistolaPlugin plugin = (EpistolaPlugin) pluginService.createInstance(
                processLink.getPluginConfigurationId());

        String variantId = actionProps.has("variantId") && !actionProps.get("variantId").isNull()
                ? actionProps.get("variantId").asText() : null;
        String environmentId = actionProps.has("environmentId") && !actionProps.get("environmentId").isNull()
                ? actionProps.get("environmentId").asText() : plugin.getDefaultEnvironmentId();

        // 7. Call Epistola preview API and stream the response
        try {
            java.io.InputStream pdfStream = epistolaService.previewDocument(
                    plugin.getBaseUrl(), plugin.getApiKey(), plugin.getTenantId(),
                    templateId, variantId, environmentId, resolvedData);

            var resource = new org.springframework.core.io.InputStreamResource(pdfStream);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(ContentDisposition.inline()
                    .filename("preview.pdf")
                    .build());

            return ResponseEntity.ok().headers(headers).body(resource);
        } catch (Exception e) {
            log.warn("Preview generation failed: {}", e.getMessage());
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "error", true,
                            "message", "Preview could not be generated",
                            "details", e.getMessage() != null ? e.getMessage() : "Unknown error"
                    ));
        }
    }

    private PluginProcessLink findPluginProcessLink(String processDefinitionId, String activityId) {
        return processLinkService.getProcessLinks(processDefinitionId, activityId).stream()
                .filter(PluginProcessLink.class::isInstance)
                .map(PluginProcessLink.class::cast)
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> overrides) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (var entry : overrides.entrySet()) {
            Object baseValue = result.get(entry.getKey());
            Object overrideValue = entry.getValue();
            if (baseValue instanceof Map && overrideValue instanceof Map) {
                result.put(entry.getKey(), deepMerge(
                        (Map<String, Object>) baseValue,
                        (Map<String, Object>) overrideValue));
            } else {
                result.put(entry.getKey(), overrideValue);
            }
        }
        return result;
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
}
