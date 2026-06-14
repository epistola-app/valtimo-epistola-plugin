package app.epistola.valtimo.domain;

/**
 * Constants for process variable names used by the Epistola plugin.
 * <p>
 * Centralises all magic strings so that plugin actions, REST endpoints,
 * and BPMN input parameters reference the same keys.
 */
public final class EpistolaProcessVariables {

    private EpistolaProcessVariables() {}

    /** Prefix for the composite job path: {@code epistola:job:{tenantId}/{requestId}}. */
    public static final String JOB_PATH_PREFIX = "epistola:job:";

    /** Tenant ID of the Epistola instance that handled the request. */
    public static final String TENANT_ID = "epistolaTenantId";

    /** JSON string with user-edited data from the retry form. Consumed and cleared by generate-document on retry. */
    public static final String EDITED_DATA = "epistolaEditedData";

    /** BPMN input parameter on the retry user task identifying the source generate-document activity. */
    public static final String SOURCE_ACTIVITY_ID = "epistolaSourceActivityId";

    /** BPMN message name correlated when document generation completes. */
    public static final String MESSAGE_NAME = "EpistolaDocumentGenerated";

    /**
     * Execution-local variable pinned on a waiting {@code EpistolaDocumentGenerated} catch event,
     * holding the composite jobPath of the generation it is waiting for. The result collector
     * correlates a completion by matching this value, so a result wakes exactly that branch's catch
     * event — independent of the execution-tree shape. Auto-populated by the catch-event parse
     * listener; a process author may set it explicitly (via a {@code camunda:inputParameter}) to
     * override the auto-resolution.
     */
    public static final String WAIT_FOR = "epistolaWaitFor";

    /** Suffix for the per-activity jobPath variable: {@code <generateActivityId> + this}. */
    public static final String ACTIVITY_JOB_PATH_SUFFIX = "_epistolaJobPath";

    /**
     * The process variable holding the jobPath written by the {@code generate-document} at the given
     * activity. Named after the activity so parallel branches never clobber each other's value
     * (a single shared {@code epistolaJobPath} is overwritten by concurrent branches — the original
     * parallel-correlation bug). The catch-event parse listener reads this (it knows its source
     * generate activity) to pin {@link #WAIT_FOR} on the waiting catch event.
     */
    public static String activityJobPathVariable(String generateActivityId) {
        return generateActivityId + ACTIVITY_JOB_PATH_SUFFIX;
    }

    /** Result-object key for the Epistola request id (UUID string). */
    public static final String RESULT_KEY_REQUEST_ID = "requestId";

    /** Result-object key for the current job status (PENDING / IN_PROGRESS / COMPLETED / FAILED / CANCELLED). */
    public static final String RESULT_KEY_STATUS = "status";

    /** Result-object key for the generated document id (set on COMPLETED, null otherwise). */
    public static final String RESULT_KEY_DOCUMENT_ID = "documentId";

    /** Result-object key for the failure message (set on FAILED, null otherwise). */
    public static final String RESULT_KEY_ERROR_MESSAGE = "errorMessage";

    /** Whether a result-object {@code status} value is terminal (the generation has finished). */
    public static boolean isTerminalStatus(Object status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }
}
