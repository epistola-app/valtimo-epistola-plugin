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
 */
@Slf4j
@RequiredArgsConstructor
public class EpistolaMessageCorrelationService {

    public static final String MESSAGE_NAME = "EpistolaDocumentGenerated";
    public static final String VAR_REQUEST_ID = "epistolaRequestId";
    public static final String VAR_TENANT_ID = "epistolaTenantId";
    public static final String VAR_STATUS = "epistolaStatus";
    public static final String VAR_DOCUMENT_ID = "epistolaDocumentId";
    public static final String VAR_ERROR_MESSAGE = "epistolaErrorMessage";

    private final RuntimeService runtimeService;

    /**
     * Correlate a completion event with all waiting process instances.
     *
     * @param requestId    The Epistola request ID
     * @param status       The job status (COMPLETED, FAILED, CANCELLED)
     * @param documentId   The document ID (null if not completed)
     * @param errorMessage The error message (null if not failed)
     * @return The number of process instances that were correlated
     */
    public int correlateCompletion(String requestId, String status,
                                   String documentId, String errorMessage) {
        try {
            List<MessageCorrelationResult> results = runtimeService.createMessageCorrelation(MESSAGE_NAME)
                    .processInstanceVariableEquals(VAR_REQUEST_ID, requestId)
                    .setVariable(VAR_STATUS, status)
                    .setVariable(VAR_DOCUMENT_ID, documentId)
                    .setVariable(VAR_ERROR_MESSAGE, errorMessage)
                    .correlateAllWithResult();

            int count = results.size();
            if (count > 0) {
                log.info("Correlated message {} for requestId={}: {} instance(s)", MESSAGE_NAME, requestId, count);
            }
            return count;
        } catch (MismatchingMessageCorrelationException e) {
            // No matching process instances found — this is not an error
            log.debug("No process instances waiting for requestId={}", requestId);
            return 0;
        }
    }
}
