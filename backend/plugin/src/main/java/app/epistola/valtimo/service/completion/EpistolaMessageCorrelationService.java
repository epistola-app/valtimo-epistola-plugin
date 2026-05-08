package app.epistola.valtimo.service.completion;

import app.epistola.valtimo.domain.EpistolaProcessVariables;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.MismatchingMessageCorrelationException;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.MessageCorrelationResult;
import org.operaton.bpm.engine.runtime.ProcessInstance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static app.epistola.valtimo.domain.EpistolaProcessVariables.*;

/**
 * Shared service for correlating BPMN messages when Epistola document generation completes.
 * <p>
 * Each result the {@link EpistolaResultCollectorRunner} pulls from
 * {@code POST /generation/collect} is dispatched here, which uses
 * {@code processInstanceVariableEquals} on the {@link EpistolaProcessVariables#JOB_PATH}
 * composite variable to wake up the matching BPMN execution. The single composite
 * variable encoding ({@code epistola:job:{tenantId}/{requestId}}) ensures both
 * tenant and request id are always stored and retrieved atomically.
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
     * Correlate a completion event with all waiting process instances.
     *
     * @param tenantId     The Epistola tenant ID
     * @param requestId    The Epistola request ID
     * @param status       The job status (COMPLETED, FAILED, CANCELLED)
     * @param documentId   The document ID (null if not completed)
     * @param errorMessage The error message (null if not failed)
     * @return The number of process instances that were correlated
     */
    public int correlateCompletion(String tenantId, String requestId, String status,
                                   String documentId, String errorMessage) {
        String jobPath = buildJobPath(tenantId, requestId);

        // Update the rich-object result variable on every matching process instance,
        // independent of whether a catch-event subscription is waiting. Users on the
        // variable pattern (no catch event) read these fields via JUEL later.
        // Catch-event users get the same fields populated AND the message correlation.
        updateResultVariable(jobPath, requestId, status, documentId, errorMessage);

        try {
            // Result data is on the rich-object variable that updateResultVariable just wrote to;
            // the catch-event subscriber reads it as ${<resultProcessVariable>.status} etc. We
            // intentionally do not duplicate-set per-execution scalars here — single source of truth.
            List<MessageCorrelationResult> results = runtimeService.createMessageCorrelation(EpistolaProcessVariables.MESSAGE_NAME)
                    .processInstanceVariableEquals(JOB_PATH, jobPath)
                    .correlateAllWithResult();

            int count = results.size();
            if (count > 0) {
                log.info("Correlated message {} for jobPath={}: {} instance(s)", EpistolaProcessVariables.MESSAGE_NAME, jobPath, count);
            } else {
                // Silent 0-match: the result was delivered from Epistola and acked, but no BPMN
                // subscription matched. Most common cause is a transactional race where the
                // result lands and the collector polls before the engine has committed the
                // catch-event subscription — typically widened by an async boundary between
                // the generate-document service task and the EpistolaDocumentGenerated catch
                // event. WARN, not DEBUG: this should be a dead log line in healthy systems.
                log.warn("Correlated 0 instances for jobPath={} (status={}); event acked but no waiting subscription. "
                                + "If this recurs, check the BPMN for asyncBefore/asyncAfter between the generate-document "
                                + "service task and the {} catch event.",
                        jobPath, status, EpistolaProcessVariables.MESSAGE_NAME);
            }
            return count;
        } catch (MismatchingMessageCorrelationException e) {
            log.warn("Correlated 0 instances for jobPath={} (status={}); MismatchingMessageCorrelationException — "
                            + "no waiting subscription. Same diagnosis as the empty-result path applies.",
                    jobPath, status);
            return 0;
        }
    }

    /**
     * Update the rich-object result variable on every process instance that's
     * waiting on this jobPath. The variable's name is read from the
     * {@link EpistolaProcessVariables#RESULT_VARIABLE_NAME} companion variable that
     * {@code generate-document} sets at submit time. No-op when no instance matches
     * (e.g. result acked by a previous backend run, or process instance already ended).
     *
     * <p>Runs in its own implicit transaction (separate from the message correlation
     * call below), via {@link RuntimeService#setVariable}. That's intentional: the
     * variable update is independent of catch-event subscription state.
     */
    private void updateResultVariable(String jobPath, String requestId, String status,
                                       String documentId, String errorMessage) {
        List<ProcessInstance> matches = runtimeService.createProcessInstanceQuery()
                .variableValueEquals(JOB_PATH, jobPath)
                .list();
        if (matches.isEmpty()) {
            return;
        }

        for (ProcessInstance pi : matches) {
            try {
                Object varNameObj = runtimeService.getVariable(pi.getId(), RESULT_VARIABLE_NAME);
                if (!(varNameObj instanceof String varName) || varName.isBlank()) {
                    log.debug("Process instance {} matches jobPath={} but has no {} companion variable; "
                                    + "skipping rich-object update (catch-event correlation will still run).",
                            pi.getId(), jobPath, RESULT_VARIABLE_NAME);
                    continue;
                }

                Map<String, Object> resultData = new LinkedHashMap<>();
                resultData.put(RESULT_KEY_REQUEST_ID, requestId);
                resultData.put(RESULT_KEY_STATUS, status);
                resultData.put(RESULT_KEY_DOCUMENT_ID, documentId);
                resultData.put(RESULT_KEY_ERROR_MESSAGE, errorMessage);
                runtimeService.setVariable(pi.getId(), varName, resultData);
            } catch (Exception e) {
                log.warn("Failed to update result variable for process instance {} (jobPath={}): {}",
                        pi.getId(), jobPath, e.getMessage());
            }
        }
    }
}
