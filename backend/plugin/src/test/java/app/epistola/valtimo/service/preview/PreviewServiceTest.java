package app.epistola.valtimo.service.preview;
import app.epistola.valtimo.service.preview.PreviewService;
import app.epistola.valtimo.service.EpistolaService;

import app.epistola.valtimo.mapping.EvaluationContext;
import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.service.preview.PreviewService.PreviewException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstanceQuery;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    private JsonataMappingService jsonataMappingService;

    @Mock
    private com.ritense.document.service.DocumentService documentService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Captor
    private ArgumentCaptor<EvaluationContext> evaluationContextCaptor;

    @InjectMocks
    private PreviewService previewService;

    private void mockProcessInstance(String processInstanceId, String processDefinitionId) {
        ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(query);
        when(query.processInstanceId(processInstanceId)).thenReturn(query);
        ProcessInstance processInstance = mock(ProcessInstance.class);
        when(processInstance.getProcessDefinitionId()).thenReturn(processDefinitionId);
        when(query.singleResult()).thenReturn(processInstance);
    }

    private void mockProcessInstanceNotFound(String processInstanceId) {
        ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(query);
        when(query.processInstanceId(processInstanceId)).thenReturn(query);
        when(query.singleResult()).thenReturn(null);
    }

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
        void missingProcessInstanceId_throwsMissingContext() {
            PreviewRequest request = new PreviewRequest(
                    "task-id-test", "doc-123", null, "activity-1", null, null, null);

            PreviewException ex = assertThrows(PreviewException.class,
                    () -> previewService.generatePreview(request));

            assertEquals(PreviewException.Reason.MISSING_CONTEXT, ex.getReason());
        }

        @Test
        void processInstanceNotFound_throwsProcessNotFound() {
            mockProcessInstanceNotFound("instance-1");

            PreviewRequest request = new PreviewRequest(
                    "task-id-test", "doc-123", null, "activity-1", "instance-1", null, null);

            PreviewException ex = assertThrows(PreviewException.class,
                    () -> previewService.generatePreview(request));

            assertEquals(PreviewException.Reason.PROCESS_NOT_FOUND, ex.getReason());
        }

        @Test
        void processDefinitionKeyMismatch_throwsProcessNotFound() {
            mockProcessInstance("instance-1", "other-process:1:abc");

            PreviewRequest request = new PreviewRequest(
                    "task-id-test", "doc-123", "my-process", "activity-1", "instance-1", null, null);

            PreviewException ex = assertThrows(PreviewException.class,
                    () -> previewService.generatePreview(request));

            assertEquals(PreviewException.Reason.PROCESS_NOT_FOUND, ex.getReason());
            assertTrue(ex.getMessage().contains("other-process"));
            assertTrue(ex.getMessage().contains("my-process"));
        }

        @Test
        void noProcessLinkFound_throwsLinkNotFound() {
            mockProcessInstance("instance-1", "my-process:1:abc");
            when(processLinkService.getProcessLinks("my-process:1:abc", "activity-1"))
                    .thenReturn(List.of());

            PreviewRequest request = new PreviewRequest(
                    "task-id-test", "doc-123", "my-process", "activity-1", "instance-1", null, null);

            PreviewException ex = assertThrows(PreviewException.class,
                    () -> previewService.generatePreview(request));

            assertEquals(PreviewException.Reason.LINK_NOT_FOUND, ex.getReason());
        }

        @Test
        void multipleGenerateDocumentLinks_noSourceActivityId_throwsAmbiguous() {
            mockProcessInstance("instance-1", "my-process:1:abc");

            PluginProcessLink link1 = mock(PluginProcessLink.class);
            when(link1.getPluginActionDefinitionKey()).thenReturn("generate-document");
            when(link1.getActivityId()).thenReturn("task-1");

            PluginProcessLink link2 = mock(PluginProcessLink.class);
            when(link2.getPluginActionDefinitionKey()).thenReturn("generate-document");
            when(link2.getActivityId()).thenReturn("task-2");

            when(processLinkService.getProcessLinks("my-process:1:abc"))
                    .thenReturn(List.of(link1, link2));

            PreviewRequest request = new PreviewRequest(
                    "task-id-test", "doc-123", null, null, "instance-1", null, null);

            PreviewException ex = assertThrows(PreviewException.class,
                    () -> previewService.generatePreview(request));

            assertEquals(PreviewException.Reason.AMBIGUOUS_ACTIVITY, ex.getReason());
            assertTrue(ex.getMessage().contains("task-1"));
            assertTrue(ex.getMessage().contains("task-2"));
        }

        @Test
        void epistolaApiFailure_throwsRenderFailed() {
            mockProcessInstance("instance-1", "my-process:1:abc");

            ObjectNode actionProps = objectMapper.createObjectNode();
            actionProps.put("catalogId", "default");
            actionProps.put("templateId", "template-123");
            actionProps.putObject("dataMapping");

            PluginProcessLink processLink = mock(PluginProcessLink.class);
            when(processLink.getPluginActionDefinitionKey()).thenReturn("generate-document");
            when(processLink.getActionProperties()).thenReturn(actionProps);
            var configId = mock(com.ritense.plugin.domain.PluginConfigurationId.class);
            when(processLink.getPluginConfigurationId()).thenReturn(configId);

            when(processLinkService.getProcessLinks("my-process:1:abc"))
                    .thenReturn(List.of(processLink));

            when(jsonataMappingService.evaluate(any(app.epistola.valtimo.mapping.EvaluationContext.class)))
                    .thenReturn(new LinkedHashMap<>());

            EpistolaPlugin plugin = mock(EpistolaPlugin.class);
            when(plugin.getBaseUrl()).thenReturn("https://api.epistola.app");
            when(plugin.getApiKey()).thenReturn("secret-key");
            when(plugin.getTenantId()).thenReturn("tenant-1");
            when(plugin.getDefaultEnvironmentId()).thenReturn("env-1");
            when(pluginService.createInstance(configId)).thenReturn(plugin);

            when(epistolaService.previewDocument(
                    anyString(), anyString(), anyString(), anyString(),
                    anyString(), any(), anyString(), any()))
                    .thenThrow(new RuntimeException("Connection refused"));

            PreviewRequest request = new PreviewRequest(
                    "task-id-test", "doc-123", null, null, "instance-1", null, null);

            PreviewException ex = assertThrows(PreviewException.class,
                    () -> previewService.generatePreview(request));

            assertEquals(PreviewException.Reason.RENDER_FAILED, ex.getReason());
            assertTrue(ex.getMessage().contains("Connection refused"));
        }
    }

    @Nested
    class InputOverrides {

        private PluginProcessLink mockFullChain(String processInstanceId, String processDefinitionId) {
            mockProcessInstance(processInstanceId, processDefinitionId);

            ObjectNode actionProps = objectMapper.createObjectNode();
            actionProps.put("catalogId", "default");
            actionProps.put("templateId", "template-123");
            actionProps.put("dataMapping", "$spread($doc)");

            PluginProcessLink processLink = mock(PluginProcessLink.class);
            when(processLink.getPluginActionDefinitionKey()).thenReturn("generate-document");
            when(processLink.getActionProperties()).thenReturn(actionProps);
            var configId = mock(com.ritense.plugin.domain.PluginConfigurationId.class);
            when(processLink.getPluginConfigurationId()).thenReturn(configId);

            when(processLinkService.getProcessLinks(processDefinitionId))
                    .thenReturn(List.of(processLink));

            EpistolaPlugin plugin = mock(EpistolaPlugin.class);
            when(plugin.getBaseUrl()).thenReturn("https://api.epistola.app");
            when(plugin.getApiKey()).thenReturn("secret-key");
            when(plugin.getTenantId()).thenReturn("tenant-1");
            when(plugin.getDefaultEnvironmentId()).thenReturn("env-1");
            when(pluginService.createInstance(configId)).thenReturn(plugin);

            when(epistolaService.previewDocument(
                    anyString(), anyString(), anyString(), anyString(),
                    anyString(), any(), anyString(), any()))
                    .thenReturn(new ByteArrayInputStream(new byte[]{0x25, 0x50, 0x44, 0x46}));

            return processLink;
        }

        @Test
        void docScopeOverrides_wrapsDocumentResolverWithOverlayMap() {
            mockFullChain("instance-1", "my-process:1:abc");

            Map<String, Object> resolvedData = new LinkedHashMap<>();
            resolvedData.put("name", "override");
            when(jsonataMappingService.evaluate(any(EvaluationContext.class)))
                    .thenReturn(resolvedData);

            Map<String, Map<String, Object>> inputOverrides = new HashMap<>();
            inputOverrides.put("doc", Map.of("name", "override"));

            PreviewRequest request = new PreviewRequest(
                    "task-id-test", "doc-123", null, null, "instance-1", null, inputOverrides);

            previewService.generatePreview(request);

            verify(jsonataMappingService).evaluate(evaluationContextCaptor.capture());
            EvaluationContext ctx = evaluationContextCaptor.getValue();

            // Invoke the document resolver and verify the result is an OverlayMap
            Map<String, Object> resolved = ctx.getDocumentResolver().apply("doc-123");
            assertInstanceOf(OverlayMap.class, resolved);
            assertEquals("override", resolved.get("name"));
        }

        @Test
        void pvScopeOverrides_processVariableResolverReturnsOverrideValue() {
            mockFullChain("instance-1", "my-process:1:abc");

            when(jsonataMappingService.evaluate(any(EvaluationContext.class)))
                    .thenReturn(new LinkedHashMap<>());

            Map<String, Map<String, Object>> inputOverrides = new HashMap<>();
            inputOverrides.put("pv", Map.of("status", "approved"));

            PreviewRequest request = new PreviewRequest(
                    "task-id-test", "doc-123", null, null, "instance-1", null, inputOverrides);

            previewService.generatePreview(request);

            verify(jsonataMappingService).evaluate(evaluationContextCaptor.capture());
            EvaluationContext ctx = evaluationContextCaptor.getValue();

            // The process variable resolver should return the override value
            assertNotNull(ctx.getProcessVariableResolver());
            assertEquals("approved", ctx.getProcessVariableResolver().apply("status"));
        }

        @Test
        void nullInputOverrides_documentResolverDoesNotWrapWithOverlayMap() {
            mockFullChain("instance-1", "my-process:1:abc");

            when(jsonataMappingService.evaluate(any(EvaluationContext.class)))
                    .thenReturn(new LinkedHashMap<>());

            PreviewRequest request = new PreviewRequest(
                    "task-id-test", "doc-123", null, null, "instance-1", null, null);

            previewService.generatePreview(request);

            verify(jsonataMappingService).evaluate(evaluationContextCaptor.capture());
            EvaluationContext ctx = evaluationContextCaptor.getValue();

            // Invoke the document resolver — result should NOT be an OverlayMap
            Map<String, Object> resolved = ctx.getDocumentResolver().apply("doc-123");
            assertFalse(resolved instanceof OverlayMap);
        }

        @Test
        void variantIdExpression_evaluatedDynamically() {
            PluginProcessLink processLink = mockFullChain("instance-1", "my-process:1:abc");

            // Add a variantId expression to the action properties
            ObjectNode actionProps = processLink.getActionProperties();
            actionProps.put("variantId", "$pv.letterType");

            when(jsonataMappingService.evaluate(any(EvaluationContext.class)))
                    .thenReturn(new LinkedHashMap<>());

            when(jsonataMappingService.evaluateScalar(any(EvaluationContext.class)))
                    .thenReturn("letter-formal");

            PreviewRequest request = new PreviewRequest(
                    "task-id-test", "doc-123", null, null, "instance-1", null, null);

            previewService.generatePreview(request);

            // Verify that the variantId passed to epistolaService.previewDocument() is the
            // dynamically evaluated result, not the raw expression string "$pv.letterType"
            ArgumentCaptor<String> variantIdCaptor = ArgumentCaptor.forClass(String.class);
            verify(epistolaService).previewDocument(
                    anyString(), anyString(), anyString(), anyString(),
                    anyString(), variantIdCaptor.capture(), anyString(), any());

            assertEquals("letter-formal", variantIdCaptor.getValue());
        }

        @Test
        void inputOverridesCombinedWithOutputOverrides_bothApplied() {
            mockFullChain("instance-1", "my-process:1:abc");

            // JSONata evaluation returns data from input overrides
            Map<String, Object> resolvedData = new LinkedHashMap<>();
            resolvedData.put("name", "from-input-override");
            resolvedData.put("address", "original-address");
            when(jsonataMappingService.evaluate(any(EvaluationContext.class)))
                    .thenReturn(resolvedData);

            // Input overrides (pre-JSONata)
            Map<String, Map<String, Object>> inputOverrides = new HashMap<>();
            inputOverrides.put("doc", Map.of("name", "from-input-override"));

            // Output overrides (post-JSONata) — should override the address field
            Map<String, Object> outputOverrides = Map.of("address", "overridden-address");

            PreviewRequest request = new PreviewRequest(
                    "task-id-test", "doc-123", null, null, "instance-1", outputOverrides, inputOverrides);

            previewService.generatePreview(request);

            // Verify input overrides were applied (doc resolver wraps with OverlayMap)
            verify(jsonataMappingService).evaluate(evaluationContextCaptor.capture());
            EvaluationContext ctx = evaluationContextCaptor.getValue();
            Map<String, Object> docResolved = ctx.getDocumentResolver().apply("doc-123");
            assertInstanceOf(OverlayMap.class, docResolved);

            // Verify the final data passed to epistolaService includes the output override
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(epistolaService).previewDocument(
                    anyString(), anyString(), anyString(), anyString(),
                    anyString(), any(), anyString(), dataCaptor.capture());

            Map<String, Object> finalData = dataCaptor.getValue();
            assertEquals("from-input-override", finalData.get("name"));
            assertEquals("overridden-address", finalData.get("address"));
        }
    }
}
