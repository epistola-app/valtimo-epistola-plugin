package app.epistola.valtimo.service.preview;

import app.epistola.valtimo.service.EpistolaService;

import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.web.rest.dto.PreviewRequest;
import app.epistola.valtimo.web.rest.dto.PreviewSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.plugin.service.PluginService;
import com.ritense.processlink.domain.ProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import com.ritense.valtimo.operaton.service.OperatonRepositoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.RuntimeService;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service that generates document previews by "dry-running" the generate-document process link.
 * <p>
 * Resolves the data mapping from the process link, merges with optional overrides,
 * and calls Epistola's preview API to render a PDF without creating a generation job.
 */
@Slf4j
@RequiredArgsConstructor
public class PreviewService {

    private final PluginService pluginService;
    private final EpistolaService epistolaService;
    private final ProcessLinkService processLinkService;
    private final OperatonRepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final JsonataMappingService jsonataMappingService;
    private final com.ritense.document.service.DocumentService documentService;
    private final ObjectMapper objectMapper;

    /**
     * Generate a document preview.
     *
     * @param request The preview request with document context and optional overrides
     * @return PDF bytes as an InputStream
     * @throws PreviewException if the preview cannot be generated
     */
    public InputStream generatePreview(PreviewRequest request) {
        String processDefinitionId = resolveProcessDefinitionId(request);
        PluginProcessLink processLink = resolveProcessLink(processDefinitionId, request.sourceActivityId());

        String catalogId = extractCatalogId(processLink);
        String templateId = extractTemplateId(processLink);
        String dataMapping = extractDataMapping(processLink);

        // Build resolvers with input-level overrides layered on top.
        // The OverlayMap checks overrides first; non-overridden paths fall through
        // to the regular resolver (lazy document load / process variable lookup).
        var docOverrides = request.inputOverrides() != null ? request.inputOverrides().get("doc") : null;
        var pvOverrides = request.inputOverrides() != null ? request.inputOverrides().get("pv") : null;

        var evalCtxBuilder = app.epistola.valtimo.mapping.EvaluationContext.builder()
                .expression(dataMapping)
                .documentResolver(docId -> {
                    Map<String, Object> doc = loadDocumentContent(docId);
                    return docOverrides != null ? new OverlayMap(docOverrides, doc) : doc;
                })
                .documentId(request.documentId());

        // Add process variable resolver (with override fallback)
        if (pvOverrides != null || request.processInstanceId() != null) {
            evalCtxBuilder.processVariableResolver(name -> {
                if (pvOverrides != null && pvOverrides.containsKey(name)) {
                    return pvOverrides.get(name);
                }
                if (request.processInstanceId() != null) {
                    return runtimeService.getVariable(request.processInstanceId(), name);
                }
                return null;
            });
        }

        Map<String, Object> resolvedData = jsonataMappingService.evaluate(evalCtxBuilder.build());

        // Deep-merge with output-level overrides (overrides win) — used by retry-form
        if (request.overrides() != null && !request.overrides().isEmpty()) {
            resolvedData = deepMerge(resolvedData, request.overrides());
        }

        // Get plugin config
        EpistolaPlugin plugin = (EpistolaPlugin) pluginService.createInstance(
                processLink.getPluginConfigurationId());

        ObjectNode actionProps = processLink.getActionProperties();
        String variantId = actionProps.has("variantId") && !actionProps.get("variantId").isNull()
                ? actionProps.get("variantId").asText() : null;
        String environmentId = actionProps.has("environmentId") && !actionProps.get("environmentId").isNull()
                ? actionProps.get("environmentId").asText() : plugin.getDefaultEnvironmentId();

        // Call Epistola preview API
        try {
            return epistolaService.previewDocument(
                    plugin.getBaseUrl(), plugin.getApiKey(), plugin.getTenantId(),
                    catalogId, templateId, variantId, environmentId, resolvedData);
        } catch (Exception e) {
            throw new PreviewException(PreviewException.Reason.RENDER_FAILED, e.getMessage(), e);
        }
    }

    /**
     * Discover all previewable document sources for a given Valtimo document.
     * Finds all running process instances associated with the document, then checks each
     * for generate-document process links.
     *
     * @param documentId The Valtimo document ID
     * @return List of preview sources (may be empty if no generate-document links exist)
     */
    public List<PreviewSource> getPreviewSources(String documentId) {
        // In Valtimo, the business key of a process instance is the document ID
        var processInstances = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(documentId)
                .active()
                .list();

        List<PreviewSource> sources = new ArrayList<>();
        for (var processInstance : processInstances) {
            String processInstanceId = processInstance.getProcessInstanceId();
            String processDefinitionId = processInstance.getProcessDefinitionId();
            // Extract process definition key from the full ID (format: "key:version:uuid")
            String processDefinitionKey = processDefinitionId.split(":")[0];

            // Find all generate-document links for this process definition
            List<PluginProcessLink> generateLinks = processLinkService.getProcessLinks(processDefinitionId).stream()
                    .filter(PluginProcessLink.class::isInstance)
                    .map(PluginProcessLink.class::cast)
                    .filter(link -> "generate-document".equals(link.getPluginActionDefinitionKey()))
                    .toList();

            for (var link : generateLinks) {
                String templateId = null;
                try {
                    templateId = extractTemplateId(link);
                } catch (PreviewException e) {
                    log.debug("Skipping link without templateId: {}", link.getActivityId());
                    continue;
                }

                sources.add(new PreviewSource(
                        processDefinitionKey,
                        link.getActivityId(),
                        templateId,
                        templateId, // Use templateId as display name; frontend can resolve the real name
                        processInstanceId
                ));
            }
        }
        return sources;
    }

    private String resolveProcessDefinitionId(PreviewRequest request) {
        if (request.processDefinitionKey() != null) {
            var processDefinition = repositoryService.findLatestProcessDefinition(request.processDefinitionKey());
            if (processDefinition == null) {
                throw new PreviewException(PreviewException.Reason.PROCESS_NOT_FOUND,
                        "Process definition not found: " + request.processDefinitionKey());
            }
            return processDefinition.getId();
        }
        if (request.processInstanceId() != null) {
            var processInstance = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(request.processInstanceId())
                    .singleResult();
            if (processInstance == null) {
                throw new PreviewException(PreviewException.Reason.PROCESS_NOT_FOUND,
                        "Process instance not found: " + request.processInstanceId());
            }
            return processInstance.getProcessDefinitionId();
        }
        throw new PreviewException(PreviewException.Reason.MISSING_CONTEXT,
                "Either processDefinitionKey or processInstanceId is required");
    }

    private PluginProcessLink resolveProcessLink(String processDefinitionId, String sourceActivityId) {
        if (sourceActivityId != null && !sourceActivityId.isBlank()) {
            PluginProcessLink link = findPluginProcessLink(processDefinitionId, sourceActivityId);
            if (link == null) {
                throw new PreviewException(PreviewException.Reason.LINK_NOT_FOUND,
                        "No generate-document process link found for activity '" + sourceActivityId + "'");
            }
            return link;
        }

        // Auto-discover
        List<PluginProcessLink> generateLinks = processLinkService.getProcessLinks(processDefinitionId).stream()
                .filter(PluginProcessLink.class::isInstance)
                .map(PluginProcessLink.class::cast)
                .filter(link -> "generate-document".equals(link.getPluginActionDefinitionKey()))
                .toList();

        if (generateLinks.isEmpty()) {
            throw new PreviewException(PreviewException.Reason.LINK_NOT_FOUND,
                    "No generate-document process links found in process definition");
        }
        if (generateLinks.size() > 1) {
            List<String> ids = generateLinks.stream().map(ProcessLink::getActivityId).toList();
            throw new PreviewException(PreviewException.Reason.AMBIGUOUS_ACTIVITY,
                    "Multiple generate-document activities found: " + ids + ". Specify sourceActivityId.");
        }

        return generateLinks.get(0);
    }

    private String extractCatalogId(PluginProcessLink link) {
        ObjectNode actionProps = link.getActionProperties();
        if (!actionProps.has("catalogId") || actionProps.get("catalogId").isNull()) {
            throw new PreviewException(PreviewException.Reason.MISSING_CONTEXT,
                    "No catalogId in process link for activity '" + link.getActivityId() + "'");
        }
        return actionProps.get("catalogId").asText();
    }

    private String extractTemplateId(PluginProcessLink link) {
        ObjectNode actionProps = link.getActionProperties();
        if (!actionProps.has("templateId") || actionProps.get("templateId").isNull()) {
            throw new PreviewException(PreviewException.Reason.MISSING_TEMPLATE,
                    "No templateId in process link for activity '" + link.getActivityId() + "'");
        }
        return actionProps.get("templateId").asText();
    }

    private String extractDataMapping(PluginProcessLink link) {
        ObjectNode actionProps = link.getActionProperties();
        if (!actionProps.has("dataMapping")) {
            return "";
        }
        var node = actionProps.get("dataMapping");
        if (node.isTextual()) {
            return node.asText("");
        }
        // Legacy: dataMapping stored as JSON object — not supported with JSONata
        log.warn("Process link {} has dataMapping in legacy object format. " +
                "Please redeploy process links to use JSONata string format.", link.getId());
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadDocumentContent(String documentId) {
        try {
            var doc = documentService.findBy(
                    com.ritense.document.domain.impl.JsonSchemaDocumentId.existingId(java.util.UUID.fromString(documentId)));
            if (doc.isPresent()) {
                return (Map<String, Object>) objectMapper.convertValue(
                        doc.get().content().asJson(), Map.class);
            }
            log.warn("Document not found: {}", documentId);
            return Map.of();
        } catch (Exception e) {
            log.warn("Failed to load document content for {}: {}", documentId, e.getMessage());
            return Map.of();
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
    static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> overrides) {
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
     * Exception thrown when a preview cannot be generated.
     */
    public static class PreviewException extends RuntimeException {
        public enum Reason {
            PROCESS_NOT_FOUND,
            LINK_NOT_FOUND,
            AMBIGUOUS_ACTIVITY,
            MISSING_TEMPLATE,
            MISSING_CONTEXT,
            RENDER_FAILED
        }

        private final Reason reason;

        public PreviewException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public PreviewException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }
    }
}
