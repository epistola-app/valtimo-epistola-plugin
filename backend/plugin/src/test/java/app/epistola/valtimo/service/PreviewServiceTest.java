package app.epistola.valtimo.service;

import app.epistola.valtimo.service.PreviewService.PreviewException;
import app.epistola.valtimo.web.rest.dto.PreviewRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.plugin.service.PluginService;
import com.ritense.processlink.service.ProcessLinkService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import com.ritense.valtimo.operaton.service.OperatonRepositoryService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.operaton.bpm.engine.RuntimeService;
import com.ritense.valtimo.operaton.domain.OperatonProcessDefinition;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreviewServiceTest {

    @Mock
    private PluginService pluginService;

    @Mock
    private EpistolaService epistolaService;

    @Mock
    private ProcessLinkService processLinkService;

    @Mock
    private OperatonRepositoryService repositoryService;

    @Mock
    private RuntimeService runtimeService;

    @Mock
    private DataMappingResolverService dataMappingResolverService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PreviewService previewService;

    @Nested
    class DeepMerge {

        @Test
        void flatMerge_combinesKeys() {
            Map<String, Object> base = Map.of("a", 1);
            Map<String, Object> override = Map.of("b", 2);

            Map<String, Object> result = PreviewService.deepMerge(base, override);

            assertEquals(1, result.get("a"));
            assertEquals(2, result.get("b"));
            assertEquals(2, result.size());
        }

        @Test
        void overrideWins_forSameKey() {
            Map<String, Object> base = Map.of("a", 1);
            Map<String, Object> override = Map.of("a", 2);

            Map<String, Object> result = PreviewService.deepMerge(base, override);

            assertEquals(2, result.get("a"));
            assertEquals(1, result.size());
        }

        @Test
        void nestedMerge_combinesNestedKeys() {
            Map<String, Object> base = Map.of("x", Map.of("a", 1));
            Map<String, Object> override = Map.of("x", Map.of("b", 2));

            Map<String, Object> result = PreviewService.deepMerge(base, override);

            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) result.get("x");
            assertEquals(1, nested.get("a"));
            assertEquals(2, nested.get("b"));
        }

        @Test
        void nestedOverrideWins_forSameNestedKey() {
            Map<String, Object> base = Map.of("x", Map.of("a", 1));
            Map<String, Object> override = Map.of("x", Map.of("a", 2));

            Map<String, Object> result = PreviewService.deepMerge(base, override);

            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) result.get("x");
            assertEquals(2, nested.get("a"));
            assertEquals(1, nested.size());
        }

        @Test
        void arrayReplaced_notMerged() {
            Map<String, Object> base = Map.of("items", List.of(1, 2));
            Map<String, Object> override = Map.of("items", List.of(3));

            Map<String, Object> result = PreviewService.deepMerge(base, override);

            assertEquals(List.of(3), result.get("items"));
        }

        @Test
        void nullOverride_replacesValue() {
            Map<String, Object> base = Map.of("a", 1);
            Map<String, Object> override = new HashMap<>();
            override.put("a", null);

            Map<String, Object> result = PreviewService.deepMerge(base, override);

            assertTrue(result.containsKey("a"));
            assertNull(result.get("a"));
        }
    }

    @Nested
    class GeneratePreview {

        @Test
        void missingProcessDefinitionKeyAndProcessInstanceId_throwsMissingContext() {
            PreviewRequest request = new PreviewRequest(
                    "doc-123", null, "activity-1", null, null);

            PreviewException ex = assertThrows(PreviewException.class,
                    () -> previewService.generatePreview(request));

            assertEquals(PreviewException.Reason.MISSING_CONTEXT, ex.getReason());
        }

        @Test
        void processDefinitionNotFound_throwsProcessNotFound() {
            when(repositoryService.findLatestProcessDefinition("my-process")).thenReturn(null);

            PreviewRequest request = new PreviewRequest(
                    "doc-123", "my-process", "activity-1", null, null);

            PreviewException ex = assertThrows(PreviewException.class,
                    () -> previewService.generatePreview(request));

            assertEquals(PreviewException.Reason.PROCESS_NOT_FOUND, ex.getReason());
        }

        @Test
        void noProcessLinkFound_throwsLinkNotFound() {
            OperatonProcessDefinition processDefinition = mock(OperatonProcessDefinition.class);
            when(processDefinition.getId()).thenReturn("my-process:1:abc");
            when(repositoryService.findLatestProcessDefinition("my-process"))
                    .thenReturn(processDefinition);
            when(processLinkService.getProcessLinks("my-process:1:abc", "activity-1"))
                    .thenReturn(List.of());

            PreviewRequest request = new PreviewRequest(
                    "doc-123", "my-process", "activity-1", null, null);

            PreviewException ex = assertThrows(PreviewException.class,
                    () -> previewService.generatePreview(request));

            assertEquals(PreviewException.Reason.LINK_NOT_FOUND, ex.getReason());
        }

        @Test
        void multipleGenerateDocumentLinks_noSourceActivityId_throwsAmbiguous() {
            OperatonProcessDefinition processDefinition = mock(OperatonProcessDefinition.class);
            when(processDefinition.getId()).thenReturn("my-process:1:abc");
            when(repositoryService.findLatestProcessDefinition("my-process"))
                    .thenReturn(processDefinition);

            PluginProcessLink link1 = mock(PluginProcessLink.class);
            when(link1.getPluginActionDefinitionKey()).thenReturn("generate-document");
            when(link1.getActivityId()).thenReturn("task-1");

            PluginProcessLink link2 = mock(PluginProcessLink.class);
            when(link2.getPluginActionDefinitionKey()).thenReturn("generate-document");
            when(link2.getActivityId()).thenReturn("task-2");

            when(processLinkService.getProcessLinks("my-process:1:abc"))
                    .thenReturn(List.of(link1, link2));

            // No sourceActivityId provided — should auto-discover and find ambiguity
            PreviewRequest request = new PreviewRequest(
                    "doc-123", "my-process", null, null, null);

            PreviewException ex = assertThrows(PreviewException.class,
                    () -> previewService.generatePreview(request));

            assertEquals(PreviewException.Reason.AMBIGUOUS_ACTIVITY, ex.getReason());
            assertTrue(ex.getMessage().contains("task-1"));
            assertTrue(ex.getMessage().contains("task-2"));
        }

        @Test
        void epistolaApiFailure_throwsRenderFailed() {
            // Set up process definition resolution
            OperatonProcessDefinition processDefinition = mock(OperatonProcessDefinition.class);
            when(processDefinition.getId()).thenReturn("my-process:1:abc");
            when(repositoryService.findLatestProcessDefinition("my-process"))
                    .thenReturn(processDefinition);

            // Set up process link with action properties
            ObjectNode actionProps = objectMapper.createObjectNode();
            actionProps.put("catalogId", "default");
            actionProps.put("templateId", "template-123");
            actionProps.putObject("dataMapping");

            PluginProcessLink processLink = mock(PluginProcessLink.class);
            when(processLink.getPluginActionDefinitionKey()).thenReturn("generate-document");
            when(processLink.getActionProperties()).thenReturn(actionProps);
            var configId = mock(com.ritense.plugin.domain.PluginConfigurationId.class);
            when(processLink.getPluginConfigurationId()).thenReturn(configId);

            // Auto-discover: single link
            when(processLinkService.getProcessLinks("my-process:1:abc"))
                    .thenReturn(List.of(processLink));

            // Data mapping resolution
            when(dataMappingResolverService.resolveMapping(eq("doc-123"), any()))
                    .thenReturn(new LinkedHashMap<>());

            // Plugin instance
            EpistolaPlugin plugin = mock(EpistolaPlugin.class);
            when(plugin.getBaseUrl()).thenReturn("https://api.epistola.app");
            when(plugin.getApiKey()).thenReturn("secret-key");
            when(plugin.getTenantId()).thenReturn("tenant-1");
            when(plugin.getDefaultEnvironmentId()).thenReturn("env-1");
            when(pluginService.createInstance(configId)).thenReturn(plugin);

            // Epistola API throws
            when(epistolaService.previewDocument(
                    anyString(), anyString(), anyString(), anyString(),
                    anyString(), any(), anyString(), any()))
                    .thenThrow(new RuntimeException("Connection refused"));

            PreviewRequest request = new PreviewRequest(
                    "doc-123", "my-process", null, null, null);

            PreviewException ex = assertThrows(PreviewException.class,
                    () -> previewService.generatePreview(request));

            assertEquals(PreviewException.Reason.RENDER_FAILED, ex.getReason());
            assertTrue(ex.getMessage().contains("Connection refused"));
        }
    }
}
