package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.GenerationJobDetail;
import app.epistola.valtimo.domain.GenerationJobStatus;
import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.Execution;
import org.operaton.bpm.engine.runtime.ExecutionQuery;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PollingCompletionEventConsumerTest {

    private RuntimeService runtimeService;
    private PluginService pluginService;
    private EpistolaService epistolaService;
    private PollingCompletionEventConsumer consumer;

    private ExecutionQuery executionQuery;

    @BeforeEach
    void setUp() {
        runtimeService = mock(RuntimeService.class);
        pluginService = mock(PluginService.class);
        epistolaService = mock(EpistolaService.class);
        consumer = new PollingCompletionEventConsumer(
                runtimeService, pluginService, epistolaService);

        executionQuery = mock(ExecutionQuery.class);
        when(runtimeService.createExecutionQuery()).thenReturn(executionQuery);
        when(executionQuery.messageEventSubscriptionName(EpistolaMessageCorrelationService.MESSAGE_NAME))
                .thenReturn(executionQuery);

        // Start the consumer so poll() will actually execute
        consumer.start();
    }

    @Test
    void poll_shouldDoNothingWhenNoWaitingExecutions() {
        when(executionQuery.list()).thenReturn(Collections.emptyList());

        consumer.poll();

        verifyNoInteractions(pluginService, epistolaService);
    }

    @Test
    void poll_shouldDoNothingWhenNotRunning() {
        consumer.stop();

        consumer.poll();

        verifyNoInteractions(pluginService, epistolaService);
    }

    @Test
    void poll_shouldDeliverMessageToExecutionOnCompletedJob() {
        Execution execution = mockExecution("exec-1", "proc-1");
        when(executionQuery.list()).thenReturn(List.of(execution));

        // Variables are read locally from the execution first
        when(runtimeService.getVariableLocal("exec-1", "epistolaRequestId")).thenReturn("req-123");
        when(runtimeService.getVariableLocal("exec-1", "epistolaTenantId")).thenReturn("tenant-a");

        EpistolaPlugin plugin = mockPlugin("https://api.epistola.app", "api-key-1", "tenant-a");
        PluginConfiguration config = mock(PluginConfiguration.class);
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any())).thenReturn(List.of(config));
        when(pluginService.createInstance(config)).thenReturn(plugin);

        when(epistolaService.getJobStatus("https://api.epistola.app", "api-key-1", "tenant-a", "req-123"))
                .thenReturn(GenerationJobDetail.builder()
                        .requestId("req-123")
                        .status(GenerationJobStatus.COMPLETED)
                        .documentId("doc-456")
                        .build());

        consumer.poll();

        verify(runtimeService).messageEventReceived(
                EpistolaMessageCorrelationService.MESSAGE_NAME,
                "exec-1",
                expectedVariables("COMPLETED", "doc-456", null)
        );
    }

    @Test
    void poll_shouldDeliverMessageOnFailedJob() {
        Execution execution = mockExecution("exec-1", "proc-1");
        when(executionQuery.list()).thenReturn(List.of(execution));

        when(runtimeService.getVariableLocal("exec-1", "epistolaRequestId")).thenReturn("req-fail");
        when(runtimeService.getVariableLocal("exec-1", "epistolaTenantId")).thenReturn("tenant-a");

        EpistolaPlugin plugin = mockPlugin("https://api.epistola.app", "api-key-1", "tenant-a");
        PluginConfiguration config = mock(PluginConfiguration.class);
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any())).thenReturn(List.of(config));
        when(pluginService.createInstance(config)).thenReturn(plugin);

        when(epistolaService.getJobStatus("https://api.epistola.app", "api-key-1", "tenant-a", "req-fail"))
                .thenReturn(GenerationJobDetail.builder()
                        .requestId("req-fail")
                        .status(GenerationJobStatus.FAILED)
                        .errorMessage("Template not found")
                        .build());

        consumer.poll();

        verify(runtimeService).messageEventReceived(
                EpistolaMessageCorrelationService.MESSAGE_NAME,
                "exec-1",
                expectedVariables("FAILED", null, "Template not found")
        );
    }

    @Test
    void poll_shouldNotCorrelateInProgressJob() {
        Execution execution = mockExecution("exec-1", "proc-1");
        when(executionQuery.list()).thenReturn(List.of(execution));

        when(runtimeService.getVariableLocal("exec-1", "epistolaRequestId")).thenReturn("req-pending");
        when(runtimeService.getVariableLocal("exec-1", "epistolaTenantId")).thenReturn("tenant-a");

        EpistolaPlugin plugin = mockPlugin("https://api.epistola.app", "api-key-1", "tenant-a");
        PluginConfiguration config = mock(PluginConfiguration.class);
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any())).thenReturn(List.of(config));
        when(pluginService.createInstance(config)).thenReturn(plugin);

        when(epistolaService.getJobStatus("https://api.epistola.app", "api-key-1", "tenant-a", "req-pending"))
                .thenReturn(GenerationJobDetail.builder()
                        .requestId("req-pending")
                        .status(GenerationJobStatus.IN_PROGRESS)
                        .build());

        consumer.poll();

        verify(runtimeService, never()).messageEventReceived(any(), any(), any());
    }

    @Test
    void poll_shouldFallBackToProcessVariableWhenLocalNotSet() {
        Execution execution = mockExecution("exec-1", "proc-1");
        when(executionQuery.list()).thenReturn(List.of(execution));

        // Local variables return null, fall back to process instance level
        when(runtimeService.getVariableLocal("exec-1", "epistolaRequestId")).thenReturn(null);
        when(runtimeService.getVariable("proc-1", "epistolaRequestId")).thenReturn("req-fallback");
        when(runtimeService.getVariableLocal("exec-1", "epistolaTenantId")).thenReturn(null);
        when(runtimeService.getVariable("proc-1", "epistolaTenantId")).thenReturn("tenant-a");

        EpistolaPlugin plugin = mockPlugin("https://api.epistola.app", "api-key-1", "tenant-a");
        PluginConfiguration config = mock(PluginConfiguration.class);
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any())).thenReturn(List.of(config));
        when(pluginService.createInstance(config)).thenReturn(plugin);

        when(epistolaService.getJobStatus("https://api.epistola.app", "api-key-1", "tenant-a", "req-fallback"))
                .thenReturn(GenerationJobDetail.builder()
                        .requestId("req-fallback")
                        .status(GenerationJobStatus.COMPLETED)
                        .documentId("doc-fb")
                        .build());

        consumer.poll();

        verify(runtimeService).messageEventReceived(
                eq(EpistolaMessageCorrelationService.MESSAGE_NAME),
                eq("exec-1"),
                any()
        );
    }

    @Test
    void poll_shouldGroupByTenantAndUseCorrectPlugin() {
        Execution exec1 = mockExecution("exec-1", "proc-1");
        Execution exec2 = mockExecution("exec-2", "proc-2");
        when(executionQuery.list()).thenReturn(List.of(exec1, exec2));

        when(runtimeService.getVariableLocal("exec-1", "epistolaRequestId")).thenReturn("req-1");
        when(runtimeService.getVariableLocal("exec-1", "epistolaTenantId")).thenReturn("tenant-a");
        when(runtimeService.getVariableLocal("exec-2", "epistolaRequestId")).thenReturn("req-2");
        when(runtimeService.getVariableLocal("exec-2", "epistolaTenantId")).thenReturn("tenant-b");

        EpistolaPlugin pluginA = mockPlugin("https://a.epistola.app", "key-a", "tenant-a");
        EpistolaPlugin pluginB = mockPlugin("https://b.epistola.app", "key-b", "tenant-b");
        PluginConfiguration configA = mock(PluginConfiguration.class);
        PluginConfiguration configB = mock(PluginConfiguration.class);
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                .thenReturn(List.of(configA, configB));
        when(pluginService.createInstance(configA)).thenReturn(pluginA);
        when(pluginService.createInstance(configB)).thenReturn(pluginB);

        when(epistolaService.getJobStatus("https://a.epistola.app", "key-a", "tenant-a", "req-1"))
                .thenReturn(GenerationJobDetail.builder()
                        .requestId("req-1").status(GenerationJobStatus.COMPLETED).documentId("doc-1").build());
        when(epistolaService.getJobStatus("https://b.epistola.app", "key-b", "tenant-b", "req-2"))
                .thenReturn(GenerationJobDetail.builder()
                        .requestId("req-2").status(GenerationJobStatus.COMPLETED).documentId("doc-2").build());

        consumer.poll();

        verify(runtimeService).messageEventReceived(eq(EpistolaMessageCorrelationService.MESSAGE_NAME), eq("exec-1"), any());
        verify(runtimeService).messageEventReceived(eq(EpistolaMessageCorrelationService.MESSAGE_NAME), eq("exec-2"), any());
    }

    @Test
    void poll_shouldSkipExecutionWithMissingVariables() {
        Execution execution = mockExecution("exec-1", "proc-1");
        when(executionQuery.list()).thenReturn(List.of(execution));

        // Both local and process-instance level return null for tenantId
        when(runtimeService.getVariableLocal("exec-1", "epistolaRequestId")).thenReturn("req-123");
        when(runtimeService.getVariableLocal("exec-1", "epistolaTenantId")).thenReturn(null);
        when(runtimeService.getVariable("proc-1", "epistolaTenantId")).thenReturn(null);

        consumer.poll();

        verifyNoInteractions(epistolaService);
    }

    @Test
    void poll_shouldSkipTenantWithNoPluginConfiguration() {
        Execution execution = mockExecution("exec-1", "proc-1");
        when(executionQuery.list()).thenReturn(List.of(execution));

        when(runtimeService.getVariableLocal("exec-1", "epistolaRequestId")).thenReturn("req-123");
        when(runtimeService.getVariableLocal("exec-1", "epistolaTenantId")).thenReturn("unknown-tenant");

        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                .thenReturn(Collections.emptyList());

        consumer.poll();

        verifyNoInteractions(epistolaService);
    }

    @Test
    void poll_shouldContinueAfterApiErrorForSingleJob() {
        Execution exec1 = mockExecution("exec-1", "proc-1");
        Execution exec2 = mockExecution("exec-2", "proc-2");
        when(executionQuery.list()).thenReturn(List.of(exec1, exec2));

        when(runtimeService.getVariableLocal("exec-1", "epistolaRequestId")).thenReturn("req-error");
        when(runtimeService.getVariableLocal("exec-1", "epistolaTenantId")).thenReturn("tenant-a");
        when(runtimeService.getVariableLocal("exec-2", "epistolaRequestId")).thenReturn("req-ok");
        when(runtimeService.getVariableLocal("exec-2", "epistolaTenantId")).thenReturn("tenant-a");

        EpistolaPlugin plugin = mockPlugin("https://api.epistola.app", "api-key-1", "tenant-a");
        PluginConfiguration config = mock(PluginConfiguration.class);
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any())).thenReturn(List.of(config));
        when(pluginService.createInstance(config)).thenReturn(plugin);

        when(epistolaService.getJobStatus("https://api.epistola.app", "api-key-1", "tenant-a", "req-error"))
                .thenThrow(new RuntimeException("API timeout"));
        when(epistolaService.getJobStatus("https://api.epistola.app", "api-key-1", "tenant-a", "req-ok"))
                .thenReturn(GenerationJobDetail.builder()
                        .requestId("req-ok").status(GenerationJobStatus.COMPLETED).documentId("doc-ok").build());

        consumer.poll();

        // Should still deliver message for the second job despite the first one failing
        verify(runtimeService, never()).messageEventReceived(
                eq(EpistolaMessageCorrelationService.MESSAGE_NAME), eq("exec-1"), any());
        verify(runtimeService).messageEventReceived(
                eq(EpistolaMessageCorrelationService.MESSAGE_NAME), eq("exec-2"), any());
    }

    @Test
    void poll_shouldDeliverMessageOnCancelledJob() {
        Execution execution = mockExecution("exec-1", "proc-1");
        when(executionQuery.list()).thenReturn(List.of(execution));

        when(runtimeService.getVariableLocal("exec-1", "epistolaRequestId")).thenReturn("req-cancel");
        when(runtimeService.getVariableLocal("exec-1", "epistolaTenantId")).thenReturn("tenant-a");

        EpistolaPlugin plugin = mockPlugin("https://api.epistola.app", "api-key-1", "tenant-a");
        PluginConfiguration config = mock(PluginConfiguration.class);
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any())).thenReturn(List.of(config));
        when(pluginService.createInstance(config)).thenReturn(plugin);

        when(epistolaService.getJobStatus("https://api.epistola.app", "api-key-1", "tenant-a", "req-cancel"))
                .thenReturn(GenerationJobDetail.builder()
                        .requestId("req-cancel")
                        .status(GenerationJobStatus.CANCELLED)
                        .build());

        consumer.poll();

        verify(runtimeService).messageEventReceived(
                EpistolaMessageCorrelationService.MESSAGE_NAME,
                "exec-1",
                expectedVariables("CANCELLED", null, null)
        );
    }

    // Helper methods

    private Execution mockExecution(String executionId, String processInstanceId) {
        Execution execution = mock(Execution.class);
        when(execution.getId()).thenReturn(executionId);
        when(execution.getProcessInstanceId()).thenReturn(processInstanceId);
        return execution;
    }

    private EpistolaPlugin mockPlugin(String baseUrl, String apiKey, String tenantId) {
        EpistolaPlugin plugin = mock(EpistolaPlugin.class);
        when(plugin.getBaseUrl()).thenReturn(baseUrl);
        when(plugin.getApiKey()).thenReturn(apiKey);
        when(plugin.getTenantId()).thenReturn(tenantId);
        return plugin;
    }

    private Map<String, Object> expectedVariables(String status, String documentId, String errorMessage) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("epistolaStatus", status);
        variables.put("epistolaDocumentId", documentId);
        variables.put("epistolaErrorMessage", errorMessage);
        return variables;
    }
}
