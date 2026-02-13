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
import java.util.List;

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
    private EpistolaMessageCorrelationService correlationService;
    private PollingCompletionEventConsumer consumer;

    private ExecutionQuery executionQuery;

    @BeforeEach
    void setUp() {
        runtimeService = mock(RuntimeService.class);
        pluginService = mock(PluginService.class);
        epistolaService = mock(EpistolaService.class);
        correlationService = mock(EpistolaMessageCorrelationService.class);
        consumer = new PollingCompletionEventConsumer(
                runtimeService, pluginService, epistolaService, correlationService);

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

        verifyNoInteractions(pluginService, epistolaService, correlationService);
    }

    @Test
    void poll_shouldDoNothingWhenNotRunning() {
        consumer.stop();

        consumer.poll();

        verifyNoInteractions(pluginService, epistolaService, correlationService);
    }

    @Test
    void poll_shouldCorrelateCompletedJob() {
        // Set up a waiting execution
        Execution execution = mockExecution("exec-1", "proc-1");
        when(executionQuery.list()).thenReturn(List.of(execution));

        when(runtimeService.getVariable("proc-1", "epistolaRequestId")).thenReturn("req-123");
        when(runtimeService.getVariable("proc-1", "epistolaTenantId")).thenReturn("tenant-a");

        // Set up plugin configuration
        EpistolaPlugin plugin = mockPlugin("https://api.epistola.app", "api-key-1", "tenant-a");
        PluginConfiguration config = mock(PluginConfiguration.class);
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any())).thenReturn(List.of(config));
        when(pluginService.createInstance(config)).thenReturn(plugin);

        // Job is completed
        when(epistolaService.getJobStatus("https://api.epistola.app", "api-key-1", "tenant-a", "req-123"))
                .thenReturn(GenerationJobDetail.builder()
                        .requestId("req-123")
                        .status(GenerationJobStatus.COMPLETED)
                        .documentId("doc-456")
                        .build());

        consumer.poll();

        verify(correlationService).correlateCompletion("req-123", "COMPLETED", "doc-456", null);
    }

    @Test
    void poll_shouldCorrelateFailedJob() {
        Execution execution = mockExecution("exec-1", "proc-1");
        when(executionQuery.list()).thenReturn(List.of(execution));

        when(runtimeService.getVariable("proc-1", "epistolaRequestId")).thenReturn("req-fail");
        when(runtimeService.getVariable("proc-1", "epistolaTenantId")).thenReturn("tenant-a");

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

        verify(correlationService).correlateCompletion("req-fail", "FAILED", null, "Template not found");
    }

    @Test
    void poll_shouldNotCorrelateInProgressJob() {
        Execution execution = mockExecution("exec-1", "proc-1");
        when(executionQuery.list()).thenReturn(List.of(execution));

        when(runtimeService.getVariable("proc-1", "epistolaRequestId")).thenReturn("req-pending");
        when(runtimeService.getVariable("proc-1", "epistolaTenantId")).thenReturn("tenant-a");

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

        verifyNoInteractions(correlationService);
    }

    @Test
    void poll_shouldGroupByTenantAndUseCorrectPlugin() {
        // Two executions from different tenants
        Execution exec1 = mockExecution("exec-1", "proc-1");
        Execution exec2 = mockExecution("exec-2", "proc-2");
        when(executionQuery.list()).thenReturn(List.of(exec1, exec2));

        when(runtimeService.getVariable("proc-1", "epistolaRequestId")).thenReturn("req-1");
        when(runtimeService.getVariable("proc-1", "epistolaTenantId")).thenReturn("tenant-a");
        when(runtimeService.getVariable("proc-2", "epistolaRequestId")).thenReturn("req-2");
        when(runtimeService.getVariable("proc-2", "epistolaTenantId")).thenReturn("tenant-b");

        // Two plugin configs for different tenants
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

        verify(correlationService).correlateCompletion("req-1", "COMPLETED", "doc-1", null);
        verify(correlationService).correlateCompletion("req-2", "COMPLETED", "doc-2", null);
    }

    @Test
    void poll_shouldSkipExecutionWithMissingVariables() {
        Execution execution = mockExecution("exec-1", "proc-1");
        when(executionQuery.list()).thenReturn(List.of(execution));

        // Missing tenantId variable
        when(runtimeService.getVariable("proc-1", "epistolaRequestId")).thenReturn("req-123");
        when(runtimeService.getVariable("proc-1", "epistolaTenantId")).thenReturn(null);

        consumer.poll();

        verifyNoInteractions(epistolaService, correlationService);
    }

    @Test
    void poll_shouldSkipTenantWithNoPluginConfiguration() {
        Execution execution = mockExecution("exec-1", "proc-1");
        when(executionQuery.list()).thenReturn(List.of(execution));

        when(runtimeService.getVariable("proc-1", "epistolaRequestId")).thenReturn("req-123");
        when(runtimeService.getVariable("proc-1", "epistolaTenantId")).thenReturn("unknown-tenant");

        // No plugin configurations
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                .thenReturn(Collections.emptyList());

        consumer.poll();

        verifyNoInteractions(epistolaService, correlationService);
    }

    @Test
    void poll_shouldContinueAfterApiErrorForSingleJob() {
        Execution exec1 = mockExecution("exec-1", "proc-1");
        Execution exec2 = mockExecution("exec-2", "proc-2");
        when(executionQuery.list()).thenReturn(List.of(exec1, exec2));

        when(runtimeService.getVariable("proc-1", "epistolaRequestId")).thenReturn("req-error");
        when(runtimeService.getVariable("proc-1", "epistolaTenantId")).thenReturn("tenant-a");
        when(runtimeService.getVariable("proc-2", "epistolaRequestId")).thenReturn("req-ok");
        when(runtimeService.getVariable("proc-2", "epistolaTenantId")).thenReturn("tenant-a");

        EpistolaPlugin plugin = mockPlugin("https://api.epistola.app", "api-key-1", "tenant-a");
        PluginConfiguration config = mock(PluginConfiguration.class);
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any())).thenReturn(List.of(config));
        when(pluginService.createInstance(config)).thenReturn(plugin);

        // First job throws, second succeeds
        when(epistolaService.getJobStatus("https://api.epistola.app", "api-key-1", "tenant-a", "req-error"))
                .thenThrow(new RuntimeException("API timeout"));
        when(epistolaService.getJobStatus("https://api.epistola.app", "api-key-1", "tenant-a", "req-ok"))
                .thenReturn(GenerationJobDetail.builder()
                        .requestId("req-ok").status(GenerationJobStatus.COMPLETED).documentId("doc-ok").build());

        consumer.poll();

        // Should still correlate the second job despite the first one failing
        verify(correlationService, never()).correlateCompletion(eq("req-error"), any(), any(), any());
        verify(correlationService).correlateCompletion("req-ok", "COMPLETED", "doc-ok", null);
    }

    @Test
    void poll_shouldCorrelateCancelledJob() {
        Execution execution = mockExecution("exec-1", "proc-1");
        when(executionQuery.list()).thenReturn(List.of(execution));

        when(runtimeService.getVariable("proc-1", "epistolaRequestId")).thenReturn("req-cancel");
        when(runtimeService.getVariable("proc-1", "epistolaTenantId")).thenReturn("tenant-a");

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

        verify(correlationService).correlateCompletion("req-cancel", "CANCELLED", null, null);
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
}
