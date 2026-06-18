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
package app.epistola.valtimo.deployment;

import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import lombok.RequiredArgsConstructor;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.FlowNode;
import org.operaton.bpm.model.bpmn.instance.IntermediateCatchEvent;
import org.operaton.bpm.model.bpmn.instance.ReceiveTask;
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
 * ({@link EpistolaProcessDefinitionValidator#findReachableEpistolaWait}): for each
 * {@code generate-document} process link, find its reachable Epistola wait (a round catch event or a
 * receive task) and map that wait activity's id to the link's {@code resultProcessVariable}. Results are
 * cached per process-definition id (a deployed version is immutable).
 *
 * <p><b>Cache only trustworthy results.</b> An empty mapping is cached only when the model genuinely has
 * no {@code EpistolaDocumentGenerated} catch event (definitively non-Epistola). If the model contains
 * Epistola catch events but the mapping came back empty — e.g. the process links aren't loaded yet, or
 * the BPMN model was momentarily unreadable — the result is treated as "not ready" and is
 * <em>not</em> cached, so a later call retries instead of poisoning the definition until restart.
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
        Map<String, String> mapping = cache.get(processDefinitionId);
        if (mapping == null) {
            Mapping built = buildMapping(processDefinitionId);
            if (built.cacheable()) {
                cache.putIfAbsent(processDefinitionId, built.mapping());
            }
            mapping = built.mapping();
        }
        return mapping.get(catchEventActivityId);
    }

    /** A built mapping plus whether it's safe to cache (see class doc — never cache a "not ready" result). */
    private record Mapping(Map<String, String> mapping, boolean cacheable) {}

    private Mapping buildMapping(String processDefinitionId) {
        Map<String, String> mapping = new HashMap<>();
        BpmnModelInstance model;
        try {
            model = repositoryService.getBpmnModelInstance(processDefinitionId);
        } catch (Exception e) {
            return new Mapping(mapping, false); // unreadable — retry later, don't poison the cache
        }
        if (model == null) {
            return new Mapping(mapping, false);
        }
        for (PluginProcessLink link : generateDocumentLinks(processDefinitionId)) {
            if (!(model.getModelElementById(link.getActivityId()) instanceof ServiceTask serviceTask)) {
                continue;
            }
            FlowNode wait =
                    EpistolaProcessDefinitionValidator.findReachableEpistolaWait(serviceTask);
            String resultVariable = resultProcessVariable(link);
            if (wait != null && resultVariable != null) {
                mapping.put(wait.getId(), resultVariable);
            }
        }
        // Cacheable when we have a confident answer: a resolved mapping, or a model that genuinely has
        // no Epistola wait. An empty mapping for a model that DOES have an Epistola wait (catch event or
        // receive task) means the pairing isn't resolvable yet (process links not loaded) — don't cache it.
        boolean cacheable = !mapping.isEmpty() || !hasEpistolaWait(model);
        return new Mapping(mapping, cacheable);
    }

    private static boolean hasEpistolaWait(BpmnModelInstance model) {
        for (IntermediateCatchEvent ice : model.getModelElementsByType(IntermediateCatchEvent.class)) {
            if (EpistolaProcessDefinitionValidator.isEpistolaWaitTarget(ice)) {
                return true;
            }
        }
        for (ReceiveTask rt : model.getModelElementsByType(ReceiveTask.class)) {
            if (EpistolaProcessDefinitionValidator.isEpistolaWaitTarget(rt)) {
                return true;
            }
        }
        return false;
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
