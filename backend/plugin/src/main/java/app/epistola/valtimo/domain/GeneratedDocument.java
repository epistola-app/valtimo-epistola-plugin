package app.epistola.valtimo.domain;

import lombok.Builder;
import lombok.Value;

/**
 * Result of submitting a document generation request to Epistola.
 */
@Value
@Builder
public class GeneratedDocument {

    /**
     * Unique identifier of the generation request (job) in Epistola.
     * This is NOT the document ID — the document ID is only available
     * after the job completes (via polling or callback).
     */
    String requestId;

    /**
     * Current status of the generation job (e.g. PENDING, IN_PROGRESS, COMPLETED, FAILED).
     */
    String status;
}
