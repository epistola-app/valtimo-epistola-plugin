package app.epistola.valtimo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.MismatchingMessageCorrelationException;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.MessageCorrelationResult;

import java.util.List;

/**
 * Shared service for correlating BPMN messages when Epistola document generation completes.
 * <p>
 * The callback endpoint uses this service for message correlation via
 * {@code processInstanceVariableEquals}. This works for sequential processes
 * and single-branch cases, but does <b>not</b> support parallel branches
 * within a single process instance (callbacks don't know execution IDs).
 * <p>
 * For parallel/multi-instance support, the {@link PollingCompletionEventConsumer}
 * uses direct {@code messageEventReceived()} targeting specific executions instead.
 * <p>
 * Job identification uses a single composite variable ({@link #VAR_JOB_PATH}) with format
 * {@code epistola:job:{tenantId}/{requestId}}. This ensures both tenant and request ID
 * are always stored and retrieved atomically.
 */
@Slf4j
@RequiredArgsConstructor
public class EpistolaMessageCorrelationService {

    public static final String MESSAGE_NAME = "EpistolaDocumentGenerated";

    /**
     * Single composite variable that encodes both tenantId and requestId.
     * Format: {@code epistola:job:{tenantId}/{requestId}}
     */
    public static final String VAR_JOB_PATH = "epistolaJobPath";
    public static final String JOB_PATH_PREFIX = "epistola:job:";

    public static final String VAR_STATUS = "epistolaStatus";
    public static final String VAR_DOCUMENT_ID = "epistolaDocumentId";
    public static final String VAR_ERROR_MESSAGE = "epistolaErrorMessage";

    private final RuntimeService runtimeService;

    /**
     * Build a job path from tenantId and requestId.
     *
     * @return a string like {@code epistola:job:demo-tenant/550e8400-...}
     */
    public static String buildJobPath(String tenantId, String requestId) {
        return JOB_PATH_PREFIX + tenantId + "/" + requestId;
    }

    /**
     * Parse a job path into its tenantId and requestId components.
     *
     * @param jobPath the composite variable value
     * @return a two-element array: [tenantId, requestId]
     * @throws IllegalArgumentException if the format is invalid
     */
    public static String[] parseJobPath(String jobPath) {
        if (jobPath == null || !jobPath.startsWith(JOB_PATH_PREFIX)) {
            throw new IllegalArgumentException("Invalid job path: " + jobPath);
        }
        String remainder = jobPath.substring(JOB_PATH_PREFIX.length());
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
        try {
            List<MessageCorrelationResult> results = runtimeService.createMessageCorrelation(MESSAGE_NAME)
                    .processInstanceVariableEquals(VAR_JOB_PATH, jobPath)
                    .setVariable(VAR_STATUS, status)
                    .setVariable(VAR_DOCUMENT_ID, documentId)
                    .setVariable(VAR_ERROR_MESSAGE, errorMessage)
                    .correlateAllWithResult();

            int count = results.size();
            if (count > 0) {
                log.info("Correlated message {} for jobPath={}: {} instance(s)", MESSAGE_NAME, jobPath, count);
            }
            return count;
        } catch (MismatchingMessageCorrelationException e) {
            log.debug("No process instances waiting for jobPath={}", jobPath);
            return 0;
        }
    }
}
