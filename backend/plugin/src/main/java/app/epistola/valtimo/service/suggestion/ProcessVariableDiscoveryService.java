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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.FlowElement;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonInputParameter;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonOutputParameter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Discovers process variable names from two complementary sources:
 * <ol>
 *   <li><strong>Historic variable query</strong>: Variable names actually used in past process instances</li>
 *   <li><strong>BPMN model parsing</strong>: Variable names defined in input/output parameters of the BPMN model</li>
 * </ol>
 * Results are merged and deduplicated.
 */
@Slf4j
@RequiredArgsConstructor
public class ProcessVariableDiscoveryService {

    private final HistoryService historyService;
    private final RepositoryService repositoryService;

    /**
     * Discover process variable names for a given process definition key.
     * Merges variables from historic instances and BPMN model definitions.
     *
     * @param processDefinitionKey the process definition key
     * @return sorted, deduplicated list of variable names
     */
    public List<String> discoverVariables(String processDefinitionKey) {
        Set<String> variables = new LinkedHashSet<>();

        variables.addAll(discoverFromHistory(processDefinitionKey));
        variables.addAll(discoverFromBpmnModel(processDefinitionKey));

        return variables.stream()
                .sorted()
                .collect(Collectors.toList());
    }

    private Set<String> discoverFromHistory(String processDefinitionKey) {
        try {
            return historyService.createHistoricVariableInstanceQuery()
                    .processDefinitionKey(processDefinitionKey)
                    .list()
                    .stream()
                    .map(HistoricVariableInstance::getName)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            log.warn("Failed to discover variables from history for process definition '{}': {}",
                    processDefinitionKey, e.getMessage());
            return Set.of();
        }
    }

    private Set<String> discoverFromBpmnModel(String processDefinitionKey) {
        try {
            ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(processDefinitionKey)
                    .latestVersion()
                    .singleResult();

            if (processDefinition == null) {
                log.debug("No process definition found for key '{}'", processDefinitionKey);
                return Set.of();
            }

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
            log.warn("Failed to discover variables from BPMN model for process definition '{}': {}",
                    processDefinitionKey, e.getMessage());
            return Set.of();
        }
    }
}
