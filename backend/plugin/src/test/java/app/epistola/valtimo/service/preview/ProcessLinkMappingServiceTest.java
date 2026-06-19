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
package app.epistola.valtimo.service.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.processlink.domain.ProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.repository.ProcessDefinitionQuery;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessLinkMappingServiceTest {

    @Mock
    private RepositoryService repositoryService;

    @Mock
    private ProcessLinkService processLinkService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProcessLinkMappingService service;

    @BeforeEach
    void setUp() {
        service = new ProcessLinkMappingService(repositoryService, processLinkService);
    }

    private void mockLatestDefinition(String key, String definitionId) {
        ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
        when(query.processDefinitionKey(key)).thenReturn(query);
        when(query.latestVersion()).thenReturn(query);
        if (definitionId == null) {
            when(query.singleResult()).thenReturn(null);
        } else {
            ProcessDefinition definition = mock(ProcessDefinition.class);
            lenient().when(definition.getId()).thenReturn(definitionId);
            when(query.singleResult()).thenReturn(definition);
        }
    }

    private PluginProcessLink linkWithDataMapping(String dataMapping) {
        ObjectNode actionProps = objectMapper.createObjectNode();
        if (dataMapping != null) {
            actionProps.put("dataMapping", dataMapping);
        }
        PluginProcessLink link = mock(PluginProcessLink.class);
        when(link.getActionProperties()).thenReturn(actionProps);
        return link;
    }

    @Test
    void returnsDataMappingForResolvedLink() {
        mockLatestDefinition("my-process", "def-1");
        PluginProcessLink link = linkWithDataMapping("{ \"name\": $doc.customer.name }");
        when(processLinkService.getProcessLinks("def-1", "Activity_1")).thenReturn(List.of(link));

        assertThat(service.getDataMapping("my-process", "Activity_1"))
                .isEqualTo("{ \"name\": $doc.customer.name }");
    }

    @Test
    void returnsEmptyWhenDefinitionNotFound() {
        mockLatestDefinition("my-process", null);

        assertThat(service.getDataMapping("my-process", "Activity_1")).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoPluginLinkForActivity() {
        mockLatestDefinition("my-process", "def-1");
        // A non-plugin process link at the activity — filtered out.
        when(processLinkService.getProcessLinks("def-1", "Activity_1"))
                .thenReturn(List.of(mock(ProcessLink.class)));

        assertThat(service.getDataMapping("my-process", "Activity_1")).isEmpty();
    }

    @Test
    void returnsEmptyWhenMappingAbsent() {
        mockLatestDefinition("my-process", "def-1");
        PluginProcessLink link = linkWithDataMapping(null);
        when(processLinkService.getProcessLinks("def-1", "Activity_1")).thenReturn(List.of(link));

        assertThat(service.getDataMapping("my-process", "Activity_1")).isEmpty();
    }

    @Test
    void returnsEmptyForLegacyObjectFormatMapping() {
        mockLatestDefinition("my-process", "def-1");
        ObjectNode actionProps = objectMapper.createObjectNode();
        actionProps.putObject("dataMapping").put("name", "$doc.name");
        PluginProcessLink link = mock(PluginProcessLink.class);
        when(link.getActionProperties()).thenReturn(actionProps);
        when(processLinkService.getProcessLinks("def-1", "Activity_1")).thenReturn(List.of(link));

        assertThat(service.getDataMapping("my-process", "Activity_1")).isEmpty();
    }

    @Test
    void returnsEmptyForBlankArguments() {
        assertThat(service.getDataMapping("", "Activity_1")).isEmpty();
        assertThat(service.getDataMapping("my-process", " ")).isEmpty();
        assertThat(service.getDataMapping(null, null)).isEmpty();
    }
}
