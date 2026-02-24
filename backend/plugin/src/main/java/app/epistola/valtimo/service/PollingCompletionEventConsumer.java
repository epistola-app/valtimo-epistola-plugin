package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.GenerationJobDetail;
import app.epistola.valtimo.domain.GenerationJobStatus;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.Execution;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Polls Epistola for completion of document generation jobs and correlates
 * BPMN messages for waiting process instances.
 * <p>
 * This is the initial implementation of {@link EpistolaCompletionEventConsumer}.
 * It works by:
 * <ol>
 *   <li>Querying Operaton for all executions waiting on message {@code "EpistolaDocumentGenerated"}</li>
 *   <li>Grouping them by {@code epistolaTenantId} local variable on the execution that set it</li>
 *   <li>For each group: loading the matching plugin config and calling {@code getJobStatus()} per job</li>
 *   <li>Delivering messages directly to the specific waiting execution via {@code messageEventReceived()}</li>
 * </ol>
 * <p>
 * Variables are read as local variables from the execution that produced them (via
 * {@code setVariableLocal} in the generate-document action). This supports parallel
 * gateways and multi-instance subprocesses where each branch has its own
 * {@code epistolaRequestId} and {@code epistolaTenantId}.
 * <p>
 * This approach centralizes polling into a single scheduled task instead of
 * per-process timer loops, which doesn't scale. When Epistola's event API
 * becomes available, a new implementation can replace this one without any
 * BPMN changes.
 */
@Slf4j
@RequiredArgsConstructor
public class PollingCompletionEventConsumer implements EpistolaCompletionEventConsumer {

    private static final Set<GenerationJobStatus> TERMINAL_STATUSES = Set.of(
            GenerationJobStatus.COMPLETED,
            GenerationJobStatus.FAILED,
            GenerationJobStatus.CANCELLED
    );

    private final RuntimeService runtimeService;
    private final PluginService pluginService;
    private final EpistolaService epistolaService;

    private volatile boolean running = false;

    @Override
    @PostConstruct
    public void start() {
        running = true;
        log.info("Started Epistola polling completion event consumer");
    }

    @Override
    @PreDestroy
    public void stop() {
        running = false;
        log.info("Stopped Epistola polling completion event consumer");
    }

    @Scheduled(fixedDelayString = "${epistola.poller.interval:30000}")
    public void poll() {
        if (!running) {
            return;
        }

        List<Execution> waitingExecutions = findWaitingExecutions();
        if (waitingExecutions.isEmpty()) {
            return;
        }

        log.debug("Found {} execution(s) waiting for Epistola completion", waitingExecutions.size());

        Map<String, List<WaitingJob>> jobsByTenant = groupByTenant(waitingExecutions);
        Map<String, EpistolaPlugin> pluginsByTenant = loadPluginConfigurations();

        for (var entry : jobsByTenant.entrySet()) {
            String tenantId = entry.getKey();
            List<WaitingJob> jobs = entry.getValue();

            EpistolaPlugin plugin = pluginsByTenant.get(tenantId);
            if (plugin == null) {
                log.warn("No Epistola plugin configuration found for tenantId='{}'. " +
                        "{} waiting job(s) cannot be polled.", tenantId, jobs.size());
                continue;
            }

            checkJobStatuses(plugin, tenantId, jobs);
        }
    }

    private List<Execution> findWaitingExecutions() {
        return runtimeService.createExecutionQuery()
                .messageEventSubscriptionName(EpistolaMessageCorrelationService.MESSAGE_NAME)
                .list();
    }

    /**
     * Group waiting executions by their {@code epistolaTenantId} variable.
     * <p>
     * The variables are set as local variables on the execution that ran the
     * generate-document action. Because the message catch event may be a different
     * execution in the same scope, we first try local, then fall back to
     * process-instance level for backwards compatibility.
     */
    private Map<String, List<WaitingJob>> groupByTenant(List<Execution> executions) {
        Map<String, List<WaitingJob>> result = new HashMap<>();

        for (Execution execution : executions) {
            try {
                String executionId = execution.getId();
                String processInstanceId = execution.getProcessInstanceId();

                // Try local first (set by generate-document in the same execution scope),
                // then fall back to process-instance level for backwards compatibility
                String requestId = getVariableWithFallback(executionId, processInstanceId,
                        EpistolaMessageCorrelationService.VAR_REQUEST_ID);
                String tenantId = getVariableWithFallback(executionId, processInstanceId,
                        EpistolaMessageCorrelationService.VAR_TENANT_ID);

                if (requestId == null || tenantId == null) {
                    log.warn("Execution {} is waiting for {} but missing required variables " +
                                    "(requestId={}, tenantId={}). Skipping.",
                            executionId,
                            EpistolaMessageCorrelationService.MESSAGE_NAME,
                            requestId, tenantId);
                    continue;
                }

                result.computeIfAbsent(tenantId, k -> new ArrayList<>())
                        .add(new WaitingJob(requestId, executionId, processInstanceId));
            } catch (Exception e) {
                log.warn("Failed to read variables for execution {}: {}",
                        execution.getId(), e.getMessage());
            }
        }

        return result;
    }

    /**
     * Read a variable from the execution. Uses getVariable (not getVariableLocal)
     * which walks up the execution hierarchy to the process instance scope.
     */
    private String getVariableWithFallback(String executionId, String processInstanceId, String variableName) {
        // getVariable walks up the execution hierarchy (local → parent → process instance)
        Object value = runtimeService.getVariable(executionId, variableName);
        if (value != null) {
            return (String) value;
        }
        // Fallback: try process instance directly (e.g. if variable was set on a different branch)
        return (String) runtimeService.getVariable(processInstanceId, variableName);
    }

    /**
     * Load all Epistola plugin configurations and index them by tenantId.
     */
    private Map<String, EpistolaPlugin> loadPluginConfigurations() {
        Map<String, EpistolaPlugin> result = new HashMap<>();

        try {
            List<?> configurations = pluginService.findPluginConfigurations(
                    EpistolaPlugin.class, props -> true);

            for (Object config : configurations) {
                try {
                    @SuppressWarnings("unchecked")
                    EpistolaPlugin plugin = (EpistolaPlugin) pluginService.createInstance(
                            (com.ritense.plugin.domain.PluginConfiguration) config);
                    result.put(plugin.getTenantId(), plugin);
                } catch (Exception e) {
                    log.warn("Failed to create plugin instance from configuration: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to load Epistola plugin configurations: {}", e.getMessage());
        }

        return result;
    }

    /**
     * Check job statuses for a batch of waiting jobs belonging to the same tenant.
     * <p>
     * Uses {@code messageEventReceived()} to deliver the message directly to the
     * specific waiting execution, rather than correlating by process variable.
     * This ensures correct behavior in parallel gateways and multi-instance
     * subprocesses where multiple executions may be waiting simultaneously.
     */
    private void checkJobStatuses(EpistolaPlugin plugin, String tenantId, List<WaitingJob> jobs) {
        for (WaitingJob job : jobs) {
            try {
                GenerationJobDetail detail = epistolaService.getJobStatus(
                        plugin.getBaseUrl(), plugin.getApiKey(), tenantId, job.requestId());

                if (TERMINAL_STATUSES.contains(detail.getStatus())) {
                    Map<String, Object> variables = new HashMap<>();
                    variables.put(EpistolaMessageCorrelationService.VAR_STATUS, detail.getStatus().name());
                    variables.put(EpistolaMessageCorrelationService.VAR_DOCUMENT_ID, detail.getDocumentId());
                    variables.put(EpistolaMessageCorrelationService.VAR_ERROR_MESSAGE, detail.getErrorMessage());

                    runtimeService.messageEventReceived(
                            EpistolaMessageCorrelationService.MESSAGE_NAME,
                            job.executionId(),
                            variables
                    );

                    log.info("Delivered message {} to execution {} for requestId={} (status={})",
                            EpistolaMessageCorrelationService.MESSAGE_NAME,
                            job.executionId(), job.requestId(), detail.getStatus());
                } else {
                    log.debug("Job {} still in progress (status={})", job.requestId(), detail.getStatus());
                }
            } catch (Exception e) {
                log.warn("Failed to check status for requestId={} (tenantId={}): {}",
                        job.requestId(), tenantId, e.getMessage());
            }
        }
    }

    /**
     * A job waiting for completion: the Epistola request ID, the execution ID
     * (for direct message delivery), and the process instance ID.
     */
    record WaitingJob(String requestId, String executionId, String processInstanceId) {}
}
