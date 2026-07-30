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

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.history.HistoricVariableInstanceQuery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcessVariableDiscoveryServiceTest {

    private final HistoryService historyService = mock(HistoryService.class);
    private final RepositoryService repositoryService = mock(RepositoryService.class);
    private final ProcessVariableDiscoveryService service =
            new ProcessVariableDiscoveryService(historyService, repositoryService);

    @Test
    void discoversNestedPathsFromHistoricMapValues() {
        HistoricVariableInstance result = variable("epistolaResult", new LinkedHashMap<>(Map.of(
                "requestId", "request-1",
                "documentId", "document-1",
                "status", "COMPLETED",
                "metadata", Map.of("id", "metadata-1")
        )));
        HistoricVariableInstance filename = variable("filename", "letter.pdf");
        stubHistory(List.of(result, filename));

        assertThat(service.discoverVariables("letter-process")).containsExactly(
                "epistolaResult",
                "epistolaResult.documentId",
                "epistolaResult.metadata",
                "epistolaResult.metadata.id",
                "epistolaResult.requestId",
                "epistolaResult.status",
                "filename"
        );
    }

    @Test
    void keepsVariableNameWhenHistoricValueCannotBeRead() {
        HistoricVariableInstance result = mock(HistoricVariableInstance.class);
        when(result.getName()).thenReturn("epistolaResult");
        when(result.getValue()).thenThrow(new IllegalStateException("serialized class unavailable"));
        stubHistory(List.of(result));

        assertThat(service.discoverVariables("letter-process")).containsExactly("epistolaResult");
    }

    private HistoricVariableInstance variable(String name, Object value) {
        HistoricVariableInstance variable = mock(HistoricVariableInstance.class);
        when(variable.getName()).thenReturn(name);
        when(variable.getValue()).thenReturn(value);
        return variable;
    }

    private void stubHistory(List<HistoricVariableInstance> variables) {
        HistoricVariableInstanceQuery query = mock(HistoricVariableInstanceQuery.class);
        when(historyService.createHistoricVariableInstanceQuery()).thenReturn(query);
        when(query.processDefinitionKey("letter-process")).thenReturn(query);
        when(query.list()).thenReturn(variables);
        when(repositoryService.createProcessDefinitionQuery()).thenThrow(new IllegalStateException("not deployed"));
    }
}
