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
package app.epistola.valtimo.service.form;

import app.epistola.valtimo.action.generate.GenerateDocumentActionConfigurationRegistry;
import app.epistola.valtimo.service.EpistolaService;

import app.epistola.valtimo.domain.EpistolaProcessVariables;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.mapping.JsonataMappingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import app.epistola.valtimo.service.generation.GenerationSubject;
import app.epistola.valtimo.service.generation.GenerationSubjectException;
import app.epistola.valtimo.service.generation.GenerationSubjectSource;
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
    private final JsonataMappingService jsonataMappingService;
    private final com.ritense.document.service.DocumentService documentService;
    private final FormioFormGenerator formioFormGenerator;
    private final java.util.List<app.epistola.valtimo.service.generation.GenerationSubjectSource>
            generationSubjectSources;
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

        String effectiveDocumentId = resolveDocumentId(documentId, processInstance);

        // What that activity was rendering, however it knew: a process link carries its own
        // template and mapping, a dynamic document was told by whoever set the variable. Asking a
        // source rather than reading action properties here is what lets this one service cover
        // both — see ADR 0007.
        GenerationSubject subject = subjectOf(originalLink, processInstanceId, effectiveDocumentId);
        String templateId = subject.templateId();
        Map<String, Object> resolvedData = subject.data();

        EpistolaPlugin plugin = (EpistolaPlugin) pluginService.createInstance(
                originalLink.getPluginConfigurationId());
        TemplateDetails template = epistolaService.getTemplateDetails(
                plugin.getBaseUrl(), plugin.getApiKey(), plugin.getTenantId(),
                subject.catalogId(), templateId);

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

    /**
     * Ask the source that understands this activity what it was rendering.
     *
     * <p>Its own exceptions are translated here rather than thrown through, so a caller of the
     * retry form keeps getting {@link RetryFormException} and the reasons it already knows.
     */
    private GenerationSubject subjectOf(PluginProcessLink link, String processInstanceId, String documentId) {
        String actionKey = link.getPluginActionDefinitionKey();
        GenerationSubjectSource source = generationSubjectSources.stream()
                .filter(candidate -> candidate.supports(actionKey))
                .findFirst()
                .orElseThrow(() -> new RetryFormException(RetryFormException.Reason.MISSING_TEMPLATE,
                        "Activity '" + link.getActivityId() + "' runs '" + actionKey
                                + "', which this plugin cannot rebuild a form for"));
        try {
            return source.resolve(link, processInstanceId, documentId);
        } catch (GenerationSubjectException e) {
            RetryFormException.Reason reason = switch (e.getReason()) {
                case NO_TEMPLATE, UNREADABLE_DOCUMENT -> RetryFormException.Reason.MISSING_TEMPLATE;
                case MAPPING_FAILED -> RetryFormException.Reason.MAPPING_FAILED;
            };
            throw new RetryFormException(reason, e.getMessage(), e);
        }
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

    /**
     * Deliberately {@code epistola-generate-document} only, not every generating action.
     *
     * <p>A retry form is rebuilt from the link's own template and data mapping, and asks the
     * employee to correct the values that mapping produced. A composed letter has neither: its
     * template was chosen by the employee and its data was resolved while they watched, and both
     * live on a process variable rather than in the link. Retrying one means composing it again,
     * which is a different form — see docs/letter-composer.md.
     */
    private List<PluginProcessLink> findGenerateDocumentProcessLinks(String processDefinitionId) {
        return processLinkService.getProcessLinks(processDefinitionId).stream()
                .filter(PluginProcessLink.class::isInstance)
                .map(PluginProcessLink.class::cast)
                .filter(link -> "epistola-generate-document".equals(link.getPluginActionDefinitionKey()))
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
            NO_DOCUMENT_ID,
            /** The mapping behind the failed generation could not be evaluated now either. */
            MAPPING_FAILED
        }

        private final Reason reason;

        public RetryFormException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public RetryFormException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }
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
}
