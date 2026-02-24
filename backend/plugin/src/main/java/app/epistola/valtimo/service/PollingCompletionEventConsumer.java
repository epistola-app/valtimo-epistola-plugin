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
 *   <li>Reading the {@code epistolaJobPath} variable from each execution (format: {@code epistola:job:{tenantId}/{requestId}})</li>
 *   <li>Grouping by tenantId and loading the matching plugin config per tenant</li>
 *   <li>Delivering messages directly to the specific waiting execution via {@code messageEventReceived()}</li>
 * </ol>
 * <p>
 * The single composite {@code epistolaJobPath} variable encodes both tenantId and requestId,
 * ensuring both are always available atomically (avoiding scoping issues where separate
 * variables might not both be visible on the message catch event's execution).
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
     * Group waiting executions by tenantId, extracted from the {@code epistolaJobPath} variable.
     */
    private Map<String, List<WaitingJob>> groupByTenant(List<Execution> executions) {
        Map<String, List<WaitingJob>> result = new HashMap<>();

        for (Execution execution : executions) {
            try {
                String executionId = execution.getId();
                String jobPath = (String) runtimeService.getVariable(executionId,
                        EpistolaMessageCorrelationService.VAR_JOB_PATH);

                if (jobPath == null) {
                    log.warn("Execution {} (processInstance={}) is waiting for {} but has no {} variable. Skipping.",
                            executionId, execution.getProcessInstanceId(),
                            EpistolaMessageCorrelationService.MESSAGE_NAME,
                            EpistolaMessageCorrelationService.VAR_JOB_PATH);
                    continue;
                }

                String[] parts = EpistolaMessageCorrelationService.parseJobPath(jobPath);
                String tenantId = parts[0];
                String requestId = parts[1];

                result.computeIfAbsent(tenantId, k -> new ArrayList<>())
                        .add(new WaitingJob(requestId, executionId, execution.getProcessInstanceId()));
            } catch (Exception e) {
                log.warn("Failed to read variables for execution {}: {}",
                        execution.getId(), e.getMessage());
            }
        }

        return result;
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
