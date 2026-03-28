package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.domain.EnvironmentInfo;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateInfo;
import app.epistola.valtimo.domain.VariantInfo;
import app.epistola.valtimo.service.DataMappingResolverService;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.FormioFormGenerator;
import app.epistola.valtimo.service.ProcessVariableDiscoveryService;
import app.epistola.valtimo.service.TemplateMappingValidator;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.task.Task;
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
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final ProcessLinkService processLinkService;
    private final DataMappingResolverService dataMappingResolverService;
    private final FormioFormGenerator formioFormGenerator;
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
     * <p>
     * Looks up the original generate-document process link, resolves its data mapping
     * expressions against the current process instance, fetches the template field schema,
     * and generates a Formio form JSON with prefilled values.
     * <p>
     * If {@code sourceActivityId} is omitted, auto-discovers the generate-document activity
     * by scanning process links. Fails if zero or multiple are found.
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
        log.debug("Generating retry form for processInstanceId={}, documentId={}, sourceActivityId={}",
                processInstanceId, documentId, sourceActivityId);

        // 1. Look up the process instance to get the process definition
        var processInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (processInstance == null) {
            log.warn("Process instance not found: {}", processInstanceId);
            return ResponseEntity.notFound().build();
        }

        String processDefinitionId = processInstance.getProcessDefinitionId();

        // 2. Resolve which generate-document activity to use.
        //    Priority: explicit parameter > BPMN input parameter on active task > auto-discover
        String effectiveSourceActivityId = sourceActivityId;

        if (effectiveSourceActivityId == null || effectiveSourceActivityId.isBlank()) {
            // Try reading epistolaSourceActivityId from the active user task's local variables
            // (set via BPMN input parameter on the retry user task)
            effectiveSourceActivityId = findSourceActivityIdFromActiveTask(processInstanceId);
        }

        PluginProcessLink originalLink;
        if (effectiveSourceActivityId != null && !effectiveSourceActivityId.isBlank()) {
            originalLink = findPluginProcessLink(processDefinitionId, effectiveSourceActivityId);
            if (originalLink == null) {
                log.warn("No plugin process link found for activity '{}' in process '{}'",
                        effectiveSourceActivityId, processDefinitionId);
                return ResponseEntity.notFound().build();
            }
        } else {
            // Auto-discover: find generate-document process links
            List<PluginProcessLink> generateLinks = findGenerateDocumentProcessLinks(processDefinitionId);
            if (generateLinks.isEmpty()) {
                log.warn("No generate-document process links found in process '{}'", processDefinitionId);
                return ResponseEntity.notFound().build();
            }
            if (generateLinks.size() > 1) {
                List<String> activityIds = generateLinks.stream()
                        .map(ProcessLink::getActivityId)
                        .toList();
                log.warn("Multiple generate-document activities found in process '{}': {}. " +
                        "Set epistolaSourceActivityId as a BPMN input parameter on the retry user task.",
                        processDefinitionId, activityIds);
                return ResponseEntity.badRequest().build();
            }
            originalLink = generateLinks.get(0);
            log.debug("Auto-discovered generate-document activity: {}", originalLink.getActivityId());
        }

        // 3. Extract dataMapping and templateId from action properties
        ObjectNode actionProps = originalLink.getActionProperties();
        String templateId = actionProps.has("templateId") ? actionProps.get("templateId").asText() : null;
        if (templateId == null) {
            log.warn("No templateId found in process link action properties for activity '{}'", sourceActivityId);
            return ResponseEntity.badRequest().build();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> dataMapping = actionProps.has("dataMapping")
                ? objectMapper.convertValue(actionProps.get("dataMapping"), Map.class)
                : Map.of();

        // 4. Resolve data mapping expressions using the document ID
        // In Valtimo, the business key of the process instance is the document ID
        String effectiveDocumentId = (documentId != null && !documentId.isBlank())
                ? documentId
                : processInstance.getBusinessKey();
        if (effectiveDocumentId == null || effectiveDocumentId.isBlank()) {
            log.warn("No document ID available for process instance '{}' — cannot resolve doc: expressions",
                    processInstanceId);
            return ResponseEntity.badRequest().build();
        }
        Map<String, Object> resolvedData = dataMappingResolverService.resolveMapping(
                effectiveDocumentId, dataMapping);

        // 5. Get the plugin instance and fetch template field schema
        EpistolaPlugin plugin = (EpistolaPlugin) pluginService.createInstance(
                originalLink.getPluginConfigurationId());
        TemplateDetails template = epistolaService.getTemplateDetails(
                plugin.getBaseUrl(), plugin.getApiKey(), plugin.getTenantId(), templateId);

        // 6. Generate the Formio form
        ObjectNode form = formioFormGenerator.generateForm(template.fields(), resolvedData);

        log.debug("Generated retry form with {} top-level components for template '{}'",
                form.get("components").size(), templateId);

        return ResponseEntity.ok(form);
    }

    /**
     * Find a PluginProcessLink for a given process definition and activity.
     */
    private PluginProcessLink findPluginProcessLink(String processDefinitionId, String activityId) {
        List<ProcessLink> links = processLinkService.getProcessLinks(processDefinitionId, activityId);
        return links.stream()
                .filter(PluginProcessLink.class::isInstance)
                .map(PluginProcessLink.class::cast)
                .findFirst()
                .orElse(null);
    }

    /**
     * Try to find the epistolaSourceActivityId from an active user task's local variables.
     * This value is set via a BPMN input parameter on the retry user task.
     *
     * @return the source activity ID, or null if not found
     */
    private String findSourceActivityIdFromActiveTask(String processInstanceId) {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .active()
                .list();
        for (Task task : tasks) {
            Object value = taskService.getVariableLocal(task.getId(), "epistolaSourceActivityId");
            if (value instanceof String str && !str.isBlank()) {
                log.debug("Found epistolaSourceActivityId='{}' from task '{}'", str, task.getId());
                return str;
            }
        }
        return null;
    }

    /**
     * Find all generate-document PluginProcessLinks in a process definition.
     */
    private List<PluginProcessLink> findGenerateDocumentProcessLinks(String processDefinitionId) {
        return processLinkService.getProcessLinks(processDefinitionId).stream()
                .filter(PluginProcessLink.class::isInstance)
                .map(PluginProcessLink.class::cast)
                .filter(link -> "generate-document".equals(link.getPluginActionDefinitionKey()))
                .toList();
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
