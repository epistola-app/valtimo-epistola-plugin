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
package app.epistola.valtimo.service.suggestion;

import app.epistola.valtimo.domain.EpistolaProcessVariables;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonInputParameter;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonOutputParameter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Discovers process variable names from three complementary sources:
 * <ol>
 *   <li><strong>Historic variable query</strong>: Variable names actually used in past process instances</li>
 *   <li><strong>BPMN model parsing</strong>: Variable names defined in input/output parameters of the BPMN model</li>
 *   <li><strong>Plugin process links</strong>: Known output shapes written by Epistola actions</li>
 * </ol>
 * Results are merged and deduplicated.
 */
@Slf4j
@RequiredArgsConstructor
public class ProcessVariableDiscoveryService {

    private static final int MAX_NESTED_DEPTH = 8;
    private static final String GENERATE_DOCUMENT_ACTION_KEY = "epistola-generate-document";
    private static final String RESULT_PROCESS_VARIABLE_PROPERTY = "resultProcessVariable";
    private static final List<String> RESULT_CHILDREN = List.of(
            EpistolaProcessVariables.RESULT_KEY_REQUEST_ID,
            EpistolaProcessVariables.RESULT_KEY_STATUS,
            EpistolaProcessVariables.RESULT_KEY_DOCUMENT_ID,
            EpistolaProcessVariables.RESULT_KEY_ERROR_MESSAGE,
            EpistolaProcessVariables.RESULT_KEY_JOB_PATH
    );

    private final HistoryService historyService;
    private final RepositoryService repositoryService;
    private final ProcessLinkService processLinkService;

    /**
     * Discover process variable names for a given process definition key.
     * Merges variables from historic instances, BPMN model definitions, and known plugin-action outputs.
     *
     * @param processDefinitionKey the process definition key
     * @return sorted, deduplicated list of variable names
     */
    public List<String> discoverVariables(String processDefinitionKey) {
        Set<String> variables = new LinkedHashSet<>();

        variables.addAll(discoverFromHistory(processDefinitionKey));
        variables.addAll(discoverFromLatestDeployment(processDefinitionKey));

        return variables.stream()
                .sorted()
                .collect(Collectors.toList());
    }

    private Set<String> discoverFromHistory(String processDefinitionKey) {
        try {
            Set<String> variables = new LinkedHashSet<>();
            Set<String> sampledVariables = new LinkedHashSet<>();

            historyService.createHistoricVariableInstanceQuery()
                    .processDefinitionKey(processDefinitionKey)
                    .list()
                    .forEach(variable -> {
                        String name = variable.getName();
                        variables.add(name);

                        // A process variable may hold a JSON-like object. Sample one non-null
                        // historic value per variable name and expose its children as dotted
                        // paths (for example epistolaResult.documentId).
                        if (!sampledVariables.contains(name)) {
                            try {
                                Object value = variable.getValue();
                                if (value != null) {
                                    sampledVariables.add(name);
                                    extractNestedPaths(value, name, variables, 0);
                                }
                            } catch (Exception e) {
                                log.debug("Could not inspect historic value for process variable '{}': {}",
                                        name, e.getMessage());
                            }
                        }
                    });
            return variables;
        } catch (Exception e) {
            log.warn("Failed to discover variables from history for process definition '{}': {}",
                    processDefinitionKey, e.getMessage());
            return Set.of();
        }
    }

    private void extractNestedPaths(Object value, String prefix, Set<String> paths, int depth) {
        if (depth >= MAX_NESTED_DEPTH) {
            return;
        }

        if (value instanceof Map<?, ?> map) {
            map.forEach((key, childValue) -> {
                if (key instanceof String childName && !childName.isBlank()) {
                    String childPath = prefix + "." + childName;
                    paths.add(childPath);
                    if (childValue != null) {
                        extractNestedPaths(childValue, childPath, paths, depth + 1);
                    }
                }
            });
        }
    }

    private Set<String> discoverFromLatestDeployment(String processDefinitionKey) {
        ProcessDefinition processDefinition;
        try {
            processDefinition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(processDefinitionKey)
                    .latestVersion()
                    .singleResult();
        } catch (Exception e) {
            log.warn("Failed to resolve latest process definition for '{}': {}",
                    processDefinitionKey, e.getMessage());
            return Set.of();
        }
        if (processDefinition == null) {
            log.debug("No process definition found for key '{}'", processDefinitionKey);
            return Set.of();
        }

        Set<String> variables = new LinkedHashSet<>();
        variables.addAll(discoverFromBpmnModel(processDefinition));
        variables.addAll(discoverEpistolaResultPaths(processDefinition));
        return variables;
    }

    private Set<String> discoverFromBpmnModel(ProcessDefinition processDefinition) {
        try {
            BpmnModelInstance model = repositoryService.getBpmnModelInstance(processDefinition.getId());
            if (model == null) {
                return Set.of();
            }

            Set<String> variables = new LinkedHashSet<>();

            // Extract from input/output parameters
            Collection<OperatonInputParameter> inputParams =
                    model.getModelElementsByType(OperatonInputParameter.class);
            for (OperatonInputParameter param : inputParams) {
                if (param.getOperatonName() != null) {
                    variables.add(param.getOperatonName());
                }
            }

            Collection<OperatonOutputParameter> outputParams =
                    model.getModelElementsByType(OperatonOutputParameter.class);
            for (OperatonOutputParameter param : outputParams) {
                if (param.getOperatonName() != null) {
                    variables.add(param.getOperatonName());
                }
            }

            return variables;
        } catch (Exception e) {
            log.warn("Failed to discover variables from BPMN model for process definition id '{}': {}",
                    processDefinition.getId(), e.getMessage());
            return Set.of();
        }
    }

    private Set<String> discoverEpistolaResultPaths(ProcessDefinition processDefinition) {
        try {
            Set<String> variables = new LinkedHashSet<>();
            processLinkService.getProcessLinks(processDefinition.getId()).stream()
                    .filter(PluginProcessLink.class::isInstance)
                    .map(PluginProcessLink.class::cast)
                    .filter(link -> GENERATE_DOCUMENT_ACTION_KEY.equals(link.getPluginActionDefinitionKey()))
                    .map(PluginProcessLink::getActionProperties)
                    .filter(properties -> properties != null
                            && properties.hasNonNull(RESULT_PROCESS_VARIABLE_PROPERTY))
                    .map(properties -> properties.get(RESULT_PROCESS_VARIABLE_PROPERTY).asText())
                    .map(String::trim)
                    .filter(name -> !name.isEmpty())
                    .forEach(name -> addKnownResultPaths(name, variables));
            return variables;
        } catch (Exception e) {
            log.warn("Failed to discover Epistola result variables for process definition id '{}': {}",
                    processDefinition.getId(), e.getMessage());
            return Set.of();
        }
    }

    private void addKnownResultPaths(String root, Set<String> variables) {
        variables.add(root);
        RESULT_CHILDREN.forEach(child -> variables.add(root + "." + child));
    }
}
