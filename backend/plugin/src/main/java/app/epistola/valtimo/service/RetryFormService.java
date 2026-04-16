package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.EpistolaProcessVariables;
import app.epistola.valtimo.domain.TemplateDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.plugin.service.PluginService;
import com.ritense.processlink.domain.ProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;

import java.util.List;
import java.util.Map;

/**
 * Service that generates a dynamic Formio form for retrying a failed document generation.
 * <p>
 * Looks up the original generate-document process link, resolves its data mapping
 * expressions against the current process instance, fetches the template field schema,
 * and generates a Formio form JSON with prefilled values.
 */
@Slf4j
@RequiredArgsConstructor
public class RetryFormService {

    private final PluginService pluginService;
    private final EpistolaService epistolaService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final ProcessLinkService processLinkService;
    private final DataMappingResolverService dataMappingResolverService;
    private final FormioFormGenerator formioFormGenerator;
    private final ObjectMapper objectMapper;

    /**
     * Generate a retry form for a failed document generation.
     *
     * @param processInstanceId The process instance ID
     * @param documentId        The Valtimo document ID (optional, falls back to business key)
     * @param sourceActivityId  The BPMN activity ID of the original generate-document task (optional)
     * @return A Formio form definition with prefilled values
     * @throws RetryFormException if the form cannot be generated
     */
    public ObjectNode generateRetryForm(String processInstanceId, String documentId, String sourceActivityId) {
        ProcessInstance processInstance = lookupProcessInstance(processInstanceId);
        String processDefinitionId = processInstance.getProcessDefinitionId();

        PluginProcessLink originalLink = resolveSourceProcessLink(
                processDefinitionId, processInstanceId, sourceActivityId);

        String catalogId = extractCatalogId(originalLink);
        String templateId = extractTemplateId(originalLink);
        Map<String, Object> dataMapping = extractDataMapping(originalLink);

        String effectiveDocumentId = resolveDocumentId(documentId, processInstance);
        Map<String, Object> resolvedData = dataMappingResolverService.resolveMapping(
                effectiveDocumentId, dataMapping);

        EpistolaPlugin plugin = (EpistolaPlugin) pluginService.createInstance(
                originalLink.getPluginConfigurationId());
        String effectiveCatalogId = catalogId != null ? catalogId : plugin.getDefaultCatalogId();
        TemplateDetails template = epistolaService.getTemplateDetails(
                plugin.getBaseUrl(), plugin.getApiKey(), plugin.getTenantId(), effectiveCatalogId, templateId);

        ObjectNode form = formioFormGenerator.generateForm(template.fields(), resolvedData);

        log.debug("Generated retry form with {} top-level components for template '{}'",
                form.get("components").size(), templateId);

        return form;
    }

    private ProcessInstance lookupProcessInstance(String processInstanceId) {
        var processInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (processInstance == null) {
            throw new RetryFormException(RetryFormException.Reason.PROCESS_NOT_FOUND,
                    "Process instance not found: " + processInstanceId);
        }
        return processInstance;
    }

    private PluginProcessLink resolveSourceProcessLink(
            String processDefinitionId, String processInstanceId, String sourceActivityId) {

        String effectiveActivityId = sourceActivityId;

        if (effectiveActivityId == null || effectiveActivityId.isBlank()) {
            effectiveActivityId = findSourceActivityIdFromActiveTask(processInstanceId);
        }

        if (effectiveActivityId != null && !effectiveActivityId.isBlank()) {
            PluginProcessLink link = findPluginProcessLink(processDefinitionId, effectiveActivityId);
            if (link == null) {
                throw new RetryFormException(RetryFormException.Reason.LINK_NOT_FOUND,
                        "No plugin process link found for activity '" + effectiveActivityId
                                + "' in process '" + processDefinitionId + "'");
            }
            return link;
        }

        // Auto-discover
        List<PluginProcessLink> generateLinks = findGenerateDocumentProcessLinks(processDefinitionId);
        if (generateLinks.isEmpty()) {
            throw new RetryFormException(RetryFormException.Reason.LINK_NOT_FOUND,
                    "No generate-document process links found in process '" + processDefinitionId + "'");
        }
        if (generateLinks.size() > 1) {
            List<String> activityIds = generateLinks.stream()
                    .map(ProcessLink::getActivityId).toList();
            throw new RetryFormException(RetryFormException.Reason.AMBIGUOUS_ACTIVITY,
                    "Multiple generate-document activities found: " + activityIds
                            + ". Set epistolaSourceActivityId as a BPMN input parameter on the retry user task.");
        }

        log.debug("Auto-discovered generate-document activity: {}", generateLinks.get(0).getActivityId());
        return generateLinks.get(0);
    }

    private String extractCatalogId(PluginProcessLink link) {
        ObjectNode actionProps = link.getActionProperties();
        if (actionProps.has("catalogId") && !actionProps.get("catalogId").isNull()) {
            return actionProps.get("catalogId").asText();
        }
        return null;
    }

    private String extractTemplateId(PluginProcessLink link) {
        ObjectNode actionProps = link.getActionProperties();
        if (!actionProps.has("templateId") || actionProps.get("templateId").isNull()) {
            throw new RetryFormException(RetryFormException.Reason.MISSING_TEMPLATE,
                    "No templateId found in process link action properties for activity '"
                            + link.getActivityId() + "'");
        }
        return actionProps.get("templateId").asText();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractDataMapping(PluginProcessLink link) {
        ObjectNode actionProps = link.getActionProperties();
        return actionProps.has("dataMapping")
                ? objectMapper.convertValue(actionProps.get("dataMapping"), Map.class)
                : Map.of();
    }

    private String resolveDocumentId(String documentId, ProcessInstance processInstance) {
        String effectiveDocumentId = (documentId != null && !documentId.isBlank())
                ? documentId
                : processInstance.getBusinessKey();
        if (effectiveDocumentId == null || effectiveDocumentId.isBlank()) {
            throw new RetryFormException(RetryFormException.Reason.NO_DOCUMENT_ID,
                    "No document ID available for process instance '"
                            + processInstance.getId() + "' — cannot resolve doc: expressions");
        }
        return effectiveDocumentId;
    }

    private String findSourceActivityIdFromActiveTask(String processInstanceId) {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .active()
                .list();
        for (Task task : tasks) {
            Object value = taskService.getVariableLocal(task.getId(), EpistolaProcessVariables.SOURCE_ACTIVITY_ID);
            if (value instanceof String str && !str.isBlank()) {
                log.debug("Found epistolaSourceActivityId='{}' from task '{}'", str, task.getId());
                return str;
            }
        }
        return null;
    }

    private PluginProcessLink findPluginProcessLink(String processDefinitionId, String activityId) {
        List<ProcessLink> links = processLinkService.getProcessLinks(processDefinitionId, activityId);
        return links.stream()
                .filter(PluginProcessLink.class::isInstance)
                .map(PluginProcessLink.class::cast)
                .findFirst()
                .orElse(null);
    }

    private List<PluginProcessLink> findGenerateDocumentProcessLinks(String processDefinitionId) {
        return processLinkService.getProcessLinks(processDefinitionId).stream()
                .filter(PluginProcessLink.class::isInstance)
                .map(PluginProcessLink.class::cast)
                .filter(link -> "generate-document".equals(link.getPluginActionDefinitionKey()))
                .toList();
    }

    /**
     * Exception thrown when a retry form cannot be generated.
     */
    public static class RetryFormException extends RuntimeException {
        public enum Reason {
            PROCESS_NOT_FOUND,
            LINK_NOT_FOUND,
            AMBIGUOUS_ACTIVITY,
            MISSING_TEMPLATE,
            NO_DOCUMENT_ID
        }

        private final Reason reason;

        public RetryFormException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }
    }
}
