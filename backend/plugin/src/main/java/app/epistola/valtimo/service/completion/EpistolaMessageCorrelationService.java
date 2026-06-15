package app.epistola.valtimo.service.completion;

import app.epistola.valtimo.domain.EpistolaProcessVariables;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.MismatchingMessageCorrelationException;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.Execution;
import org.operaton.bpm.engine.runtime.VariableInstance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static app.epistola.valtimo.domain.EpistolaProcessVariables.MESSAGE_NAME;
import static app.epistola.valtimo.domain.EpistolaProcessVariables.RESULT_KEY_DOCUMENT_ID;
import static app.epistola.valtimo.domain.EpistolaProcessVariables.RESULT_KEY_ERROR_MESSAGE;
import static app.epistola.valtimo.domain.EpistolaProcessVariables.RESULT_KEY_JOB_PATH;
import static app.epistola.valtimo.domain.EpistolaProcessVariables.RESULT_KEY_REQUEST_ID;
import static app.epistola.valtimo.domain.EpistolaProcessVariables.RESULT_KEY_STATUS;
import static app.epistola.valtimo.domain.EpistolaProcessVariables.WAIT_FOR;

/**
 * Correlates BPMN messages when Epistola document generation completes.
 * <p>
 * Each result the {@link EpistolaResultCollectorRunner} pulls from {@code POST /generation/collect} is
 * dispatched here. Correlation is <strong>per branch</strong> and independent of the execution-tree
 * shape:
 * <ul>
 *   <li>Each waiting {@code EpistolaDocumentGenerated} catch event carries its own job's composite
 *       jobPath pinned as an execution-local {@link EpistolaProcessVariables#WAIT_FOR} variable on its
 *       own (subscription) execution — set by the catch-event parse listener, or by a process author
 *       as an override. We match the subscription with a single indexed query
 *       ({@code messageEventSubscriptionName + variableValueEquals(WAIT_FOR, jobPath)}) and wake it by
 *       id, so a result wakes exactly the branch that submitted it — never its siblings.</li>
 *   <li>The result-variable <em>name</em> (and process instance, for the variable-pattern fallback) is
 *       resolved from a locator variable {@code generate-document} writes whose <em>name</em> is the
 *       jobPath itself (globally unique → no clobber) and whose value is the result-variable name.</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class EpistolaMessageCorrelationService {

    private final RuntimeService runtimeService;

    /**
     * Build a job path from tenantId and requestId.
     *
     * @return a string like {@code epistola:job:demo-tenant/550e8400-...}
     */
    public static String buildJobPath(String tenantId, String requestId) {
        return EpistolaProcessVariables.JOB_PATH_PREFIX + tenantId + "/" + requestId;
    }

    /**
     * Parse a job path into its tenantId and requestId components.
     *
     * @param jobPath the composite variable value
     * @return a two-element array: [tenantId, requestId]
     * @throws IllegalArgumentException if the format is invalid
     */
    public static String[] parseJobPath(String jobPath) {
        if (jobPath == null || !jobPath.startsWith(EpistolaProcessVariables.JOB_PATH_PREFIX)) {
            throw new IllegalArgumentException("Invalid job path: " + jobPath);
        }
        String remainder = jobPath.substring(EpistolaProcessVariables.JOB_PATH_PREFIX.length());
        int slashIndex = remainder.indexOf('/');
        if (slashIndex <= 0 || slashIndex == remainder.length() - 1) {
            throw new IllegalArgumentException("Invalid job path: " + jobPath);
        }
        return new String[]{
                remainder.substring(0, slashIndex),
                remainder.substring(slashIndex + 1)
        };
    }

    /**
     * Correlate a generation completion with the waiting branch.
     *
     * @param tenantId     The Epistola tenant ID
     * @param requestId    The Epistola request ID
     * @param status       The job status (COMPLETED, FAILED, CANCELLED)
     * @param documentId   The document ID (null if not completed)
     * @param errorMessage The error message (null if not failed)
     * @return The number of catch-event subscriptions woken (0 for the variable pattern, even when the
     *         result variable was updated)
     */
    public int correlateCompletion(String tenantId, String requestId, String status,
                                   String documentId, String errorMessage) {
        String jobPath = buildJobPath(tenantId, requestId);
        Map<String, Object> resultData = buildResult(requestId, status, documentId, errorMessage, jobPath);

        // The result-variable name to update, resolved from the jobPath-named locator (see
        // generate-document). May be null if the job is unknown to this engine (e.g. already ended).
        String resultVariableName = resolveResultVariableName(jobPath);

        // Match the waiting catch event(s) by their own pinned WAIT_FOR token — a single indexed
        // query, independent of where the executions sit in the tree.
        List<Execution> waiting = runtimeService.createExecutionQuery()
                .messageEventSubscriptionName(MESSAGE_NAME)
                .variableValueEquals(WAIT_FOR, jobPath)
                .list();

        int correlated = 0;
        for (Execution execution : waiting) {
            if (resultVariableName != null) {
                // Set on the subscription execution: engine scope-bubbling lands it at the right scope
                // (process instance for a parallel gateway, the instance scope for multi-instance).
                runtimeService.setVariable(execution.getId(), resultVariableName, resultData);
            }
            try {
                runtimeService.messageEventReceived(MESSAGE_NAME, execution.getId());
                correlated++;
            } catch (MismatchingMessageCorrelationException e) {
                log.debug("Execution {} no longer has a {} subscription (jobPath={}): {}",
                        execution.getId(), MESSAGE_NAME, jobPath, e.getMessage());
            }
        }

        if (correlated > 0) {
            log.info("Correlated message {} for jobPath={}: {} execution(s)", MESSAGE_NAME, jobPath, correlated);
            return correlated;
        }

        // No waiting catch-event subscription. Normal for the "variable pattern" (no catch event; the
        // process reads ${var.status} via JUEL later) — still update the result variable in place.
        int updated = updateResultVariableWithoutSubscription(jobPath, resultData);
        if (updated == 0) {
            log.warn("Correlated 0 executions for jobPath={} (status={}); event acked but no waiting "
                    + "subscription and no matching job found.", jobPath, status);
        } else {
            log.info("Updated result variable for jobPath={} (status={}) without a catch-event "
                    + "subscription ({} instance(s); variable pattern).", jobPath, status, updated);
        }
        return 0;
    }

    /**
     * Self-heal a catch event whose generation result arrived <em>before</em> it subscribed — only
     * possible when an async boundary precedes the catch event (advised against; flagged by the
     * validator). Called from the catch event's start execution listener via an after-commit callback,
     * by which point the subscription is committed and the result variable has been updated in place by
     * the collector. Reads the pinned {@link EpistolaProcessVariables#WAIT_FOR} token, resolves the
     * result variable, and if its status is already terminal delivers the message so the branch
     * continues instead of stalling. In the normal synchronous flow the result is still {@code PENDING}
     * here, so this is a no-op.
     *
     * @param executionId the subscribing catch-event execution
     * @return {@code true} if the catch event was woken
     */
    public boolean selfHeal(String executionId) {
        if (!(runtimeService.getVariableLocal(executionId, WAIT_FOR) instanceof String jobPath) || jobPath.isBlank()) {
            return false; // not an Epistola catch event, or no correlation token pinned
        }
        String resultVariableName = resolveResultVariableName(jobPath);
        if (resultVariableName == null) {
            return false;
        }
        if (!(runtimeService.getVariable(executionId, resultVariableName) instanceof Map<?, ?> result)
                || !EpistolaProcessVariables.isTerminalStatus(result.get(RESULT_KEY_STATUS))) {
            return false; // result not (yet) terminal — wait normally
        }
        try {
            runtimeService.messageEventReceived(MESSAGE_NAME, executionId);
            log.info("Self-healed catch event execution {} (jobPath={}): result already present on subscribe",
                    executionId, jobPath);
            return true;
        } catch (MismatchingMessageCorrelationException e) {
            log.debug("Self-heal: execution {} no longer waiting on {} (jobPath={}): {}",
                    executionId, MESSAGE_NAME, jobPath, e.getMessage());
            return false;
        }
    }

    private Map<String, Object> buildResult(String requestId, String status, String documentId, String errorMessage,
                                            String jobPath) {
        Map<String, Object> resultData = new LinkedHashMap<>();
        resultData.put(RESULT_KEY_REQUEST_ID, requestId);
        resultData.put(RESULT_KEY_STATUS, status);
        resultData.put(RESULT_KEY_DOCUMENT_ID, documentId);
        resultData.put(RESULT_KEY_ERROR_MESSAGE, errorMessage);
        // Preserve jobPath so a catch event that subscribes AFTER the result landed can still pin its
        // ${<resultVar>.jobPath} token (the self-heal path); also keeps the rich object shape stable.
        resultData.put(RESULT_KEY_JOB_PATH, jobPath);
        return resultData;
    }

    /**
     * The result-variable name for this job, read from the locator variable whose <em>name</em> is the
     * jobPath (written by {@code generate-document}; globally unique → exactly one). Null if unknown.
     */
    private String resolveResultVariableName(String jobPath) {
        return runtimeService.createVariableInstanceQuery()
                .variableName(jobPath)
                .list().stream()
                .map(VariableInstance::getValue)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(name -> !name.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * Fallback for the variable pattern (no catch event): update the result variable on the process
     * instance(s) holding this job's locator. Returns the number of instances updated.
     */
    private int updateResultVariableWithoutSubscription(String jobPath, Map<String, Object> resultData) {
        int updated = 0;
        for (VariableInstance locator : runtimeService.createVariableInstanceQuery().variableName(jobPath).list()) {
            if (!(locator.getValue() instanceof String resultVariableName) || resultVariableName.isBlank()) {
                continue;
            }
            try {
                runtimeService.setVariable(locator.getProcessInstanceId(), resultVariableName, resultData);
                updated++;
            } catch (Exception e) {
                log.warn("Failed to update result variable for process instance {} (jobPath={}): {}",
                        locator.getProcessInstanceId(), jobPath, e.getMessage());
            }
        }
        return updated;
    }
}
