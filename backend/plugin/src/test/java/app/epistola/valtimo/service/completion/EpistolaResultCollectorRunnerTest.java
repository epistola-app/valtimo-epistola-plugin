package app.epistola.valtimo.service.completion;

import app.epistola.client.collect.ResultCollector;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.config.EpistolaProperties;
import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.events.PluginConfigurationDeletedEvent;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.contract.event.PluginsDeployedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused tests on the parts of the runner that don't require driving real
 * collector threads or mocking out {@link PluginService}'s reconciliation surface:
 * the per-result correlation behavior, and the cold-start fallback for routing keys.
 */
class EpistolaResultCollectorRunnerTest {

    private PluginService pluginService;
    private EpistolaApiClientFactory apiClientFactory;
    private EpistolaMessageCorrelationService correlationService;
    private EpistolaProperties properties;
    private EpistolaResultCollectorRunner runner;

    @BeforeEach
    void setUp() {
        pluginService = mock(PluginService.class);
        apiClientFactory = mock(EpistolaApiClientFactory.class);
        correlationService = mock(EpistolaMessageCorrelationService.class);
        properties = new EpistolaProperties();
        runner = new EpistolaResultCollectorRunner(
                pluginService, apiClientFactory, correlationService, properties);
    }

    @Test
    void handleResult_correlatesWithJobPathFromTenantAndRequest() {
        ResultCollector.GenerationResult result = makeResult("req-123", "COMPLETED", "doc-7", null);
        when(correlationService.correlateCompletion(
                eq("acme"), eq("req-123"), eq("COMPLETED"), eq("doc-7"), eq((String) null)))
                .thenReturn(1);

        runner.handleResult("acme", result);

        verify(correlationService, times(1))
                .correlateCompletion("acme", "req-123", "COMPLETED", "doc-7", null);
    }

    @Test
    void handleResult_swallowsExceptionsSoCollectorAcksAndMovesOn() {
        // If correlation throws (e.g. no waiting execution, infra error), we must NOT propagate
        // — re-throwing would block the collector's sequence from advancing and replay the same
        // result on the next poll forever. Server-side delivery already happened; redelivery
        // can't resurrect a missing waiting execution.
        ResultCollector.GenerationResult result = makeResult("req-456", "FAILED", null, "boom");
        when(correlationService.correlateCompletion(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("operaton was sad"));

        // No exception should escape.
        runner.handleResult("acme", result);

        verify(correlationService).correlateCompletion("acme", "req-456", "FAILED", null, "boom");
    }

    @Test
    void routingKeyFor_returnsNullWhenNoCollectorMatches() {
        // No collectors started yet (would need a full reconcile to spin one up). Cold start.
        String key = runner.routingKeyFor("https://epistola.example/api", "key-1", "acme", "req-1");

        assertThat(key).isNull();
    }

    @Test
    void kickFor_isNoOpWhenNoCollectorMatches() {
        // Same cold-start as the routingKeyFor test — no collector running for
        // this connection. Calling kickFor must not throw; it just silently
        // does nothing (the connection presumably wasn't reconciled yet, or
        // was just removed). Correctness invariant: the collector is an
        // optimization, never a required path for a successful generate.
        runner.kickFor("https://epistola.example/api", "key-1", "acme");

        // No exception, no assertion needed beyond "didn't throw."
    }

    @Test
    void onPluginsDeployed_triggersReconcile() {
        // The event listener should call reconcile(), which queries pluginService.
        // We use the no-config baseline: reconcile returns immediately after that query.
        when(pluginService.findPluginConfigurations(eq(com.ritense.valtimo.epistola.plugin.EpistolaPlugin.class), any()))
                .thenReturn(Collections.emptyList());

        runner.onPluginsDeployed(new PluginsDeployedEvent());

        verify(pluginService, atLeastOnce())
                .findPluginConfigurations(eq(com.ritense.valtimo.epistola.plugin.EpistolaPlugin.class), any());
    }

    @Test
    void onPluginConfigurationDeleted_triggersReconcile() {
        when(pluginService.findPluginConfigurations(eq(com.ritense.valtimo.epistola.plugin.EpistolaPlugin.class), any()))
                .thenReturn(Collections.emptyList());

        runner.onPluginConfigurationDeleted(new PluginConfigurationDeletedEvent(mock(PluginConfiguration.class)));

        verify(pluginService, atLeastOnce())
                .findPluginConfigurations(eq(com.ritense.valtimo.epistola.plugin.EpistolaPlugin.class), any());
    }

    @Test
    void onPluginsDeployed_isNoOpWhenCollectorDisabled() {
        properties.getResultCollector().setEnabled(false);

        runner.onPluginsDeployed(new PluginsDeployedEvent());

        // reconcile() short-circuits at the enabled check, never asking pluginService.
        verify(pluginService, times(0))
                .findPluginConfigurations(eq(com.ritense.valtimo.epistola.plugin.EpistolaPlugin.class), any());
    }

    private static ResultCollector.GenerationResult makeResult(
            String requestId, String status, String documentId, String error) {
        return new ResultCollector.GenerationResult(
                /* sequence */ 1L,
                requestId,
                /* batchId */ null,
                status,
                documentId,
                /* correlationId */ null,
                /* routingKey */ null,
                /* templateId */ null,
                /* variantId */ null,
                /* versionId */ null,
                /* filename */ null,
                /* contentType */ null,
                /* sizeBytes */ null,
                error,
                /* completedAt */ null
        );
    }
}
