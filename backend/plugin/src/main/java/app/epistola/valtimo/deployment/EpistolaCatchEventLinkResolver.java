package app.epistola.valtimo.deployment;

import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import lombok.RequiredArgsConstructor;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.IntermediateCatchEvent;
import org.operaton.bpm.model.bpmn.instance.ServiceTask;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves, for an {@code EpistolaDocumentGenerated} catch event, the name of the result variable
 * written by the {@code generate-document} that flows into it — using only public APIs (the public
 * BPMN model via {@link RepositoryService#getBpmnModelInstance} and {@link ProcessLinkService}).
 *
 * <p>Pairing reuses the validator's forward walk
 * ({@link EpistolaProcessDefinitionValidator#findReachableEpistolaCatchEvent}): for each
 * {@code generate-document} process link, find its reachable catch event and map that catch event's id
 * to the link's {@code resultProcessVariable}. Results are cached per process-definition id (a deployed
 * version is immutable).
 */
@RequiredArgsConstructor
public class EpistolaCatchEventLinkResolver {

    private static final String GENERATE_DOCUMENT_ACTION_KEY = "epistola-generate-document";
    private static final String RESULT_PROCESS_VARIABLE_PROPERTY = "resultProcessVariable";

    private final RepositoryService repositoryService;
    private final ProcessLinkService processLinkService;

    /** catch-event activityId → result-variable name, per process-definition id. */
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    /**
     * The result-variable name for the {@code generate-document} that flows into the given catch event,
     * or {@code null} when the catch event is not the target of an Epistola generate-document (i.e. not
     * an Epistola catch-event pattern).
     */
    public String resultVariableFor(String processDefinitionId, String catchEventActivityId) {
        if (processDefinitionId == null || catchEventActivityId == null) {
            return null;
        }
        return cache.computeIfAbsent(processDefinitionId, this::buildMapping).get(catchEventActivityId);
    }

    private Map<String, String> buildMapping(String processDefinitionId) {
        Map<String, String> mapping = new HashMap<>();
        BpmnModelInstance model;
        try {
            model = repositoryService.getBpmnModelInstance(processDefinitionId);
        } catch (Exception e) {
            return mapping; // definition gone / unreadable — nothing to resolve
        }
        if (model == null) {
            return mapping;
        }
        for (PluginProcessLink link : generateDocumentLinks(processDefinitionId)) {
            if (!(model.getModelElementById(link.getActivityId()) instanceof ServiceTask serviceTask)) {
                continue;
            }
            IntermediateCatchEvent catchEvent =
                    EpistolaProcessDefinitionValidator.findReachableEpistolaCatchEvent(serviceTask);
            String resultVariable = resultProcessVariable(link);
            if (catchEvent != null && resultVariable != null) {
                mapping.put(catchEvent.getId(), resultVariable);
            }
        }
        return mapping;
    }

    private java.util.List<PluginProcessLink> generateDocumentLinks(String processDefinitionId) {
        return processLinkService.getProcessLinks(processDefinitionId).stream()
                .filter(PluginProcessLink.class::isInstance)
                .map(PluginProcessLink.class::cast)
                .filter(link -> GENERATE_DOCUMENT_ACTION_KEY.equals(link.getPluginActionDefinitionKey()))
                .toList();
    }

    private String resultProcessVariable(PluginProcessLink link) {
        var props = link.getActionProperties();
        if (props == null || !props.hasNonNull(RESULT_PROCESS_VARIABLE_PROPERTY)) {
            return null;
        }
        String value = props.get(RESULT_PROCESS_VARIABLE_PROPERTY).asText();
        return value.isBlank() ? null : value;
    }
}
