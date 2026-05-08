package app.epistola.valtimo.domain;

/**
 * Constants for process variable names used by the Epistola plugin.
 * <p>
 * Centralises all magic strings so that plugin actions, REST endpoints,
 * and BPMN input parameters reference the same keys.
 */
public final class EpistolaProcessVariables {

    private EpistolaProcessVariables() {}

    /** Composite key encoding tenantId + requestId. Format: {@code epistola:job:{tenantId}/{requestId}} */
    public static final String JOB_PATH = "epistolaJobPath";

    /** Prefix for the composite job path variable. */
    public static final String JOB_PATH_PREFIX = "epistola:job:";

    /** Generation status set by the result collector (COMPLETED, FAILED, etc.). */
    public static final String STATUS = "epistolaStatus";

    /** Document ID set when generation completes successfully. */
    public static final String DOCUMENT_ID = "epistolaDocumentId";

    /** Error message set when generation fails. */
    public static final String ERROR_MESSAGE = "epistolaErrorMessage";

    /** Tenant ID of the Epistola instance that handled the request. */
    public static final String TENANT_ID = "epistolaTenantId";

    /** JSON string with user-edited data from the retry form. Consumed and cleared by generate-document on retry. */
    public static final String EDITED_DATA = "epistolaEditedData";

    /** BPMN input parameter on the retry user task identifying the source generate-document activity. */
    public static final String SOURCE_ACTIVITY_ID = "epistolaSourceActivityId";

    /** BPMN message name correlated when document generation completes. */
    public static final String MESSAGE_NAME = "EpistolaDocumentGenerated";

    /**
     * Internal companion variable: the *name* of the user-configured result process
     * variable (i.e. the value of {@code resultProcessVariable} from the process-link).
     * Set by {@code generate-document} at submit time so the result collector knows
     * where to write the rich result object on the matching process instance later.
     * Hardcoded; not exposed in user-facing docs.
     */
    public static final String RESULT_VARIABLE_NAME = "epistolaResultVariableName";

    /** Result-object key for the Epistola request id (UUID string). */
    public static final String RESULT_KEY_REQUEST_ID = "requestId";

    /** Result-object key for the current job status (PENDING / IN_PROGRESS / COMPLETED / FAILED / CANCELLED). */
    public static final String RESULT_KEY_STATUS = "status";

    /** Result-object key for the generated document id (set on COMPLETED, null otherwise). */
    public static final String RESULT_KEY_DOCUMENT_ID = "documentId";

    /** Result-object key for the failure message (set on FAILED, null otherwise). */
    public static final String RESULT_KEY_ERROR_MESSAGE = "errorMessage";
}
