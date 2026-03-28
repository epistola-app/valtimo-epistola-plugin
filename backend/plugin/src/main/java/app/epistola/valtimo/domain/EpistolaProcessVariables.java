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

    /** Generation status set by the completion callback/poller (COMPLETED, FAILED, etc.). */
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
}
