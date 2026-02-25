package app.epistola.valtimo.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Detailed information about a document generation job.
 */
@Value
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class GenerationJobDetail {

    /**
     * The unique request/job ID.
     */
    String requestId;

    /**
     * Current status of the job.
     */
    GenerationJobStatus status;

    /**
     * The generated document ID (available when status is COMPLETED).
     */
    String documentId;

    /**
     * Error message (available when status is FAILED).
     */
    String errorMessage;

    /**
     * Timestamp when the job was created.
     */
    Instant createdAt;

    /**
     * Timestamp when the job was completed (or failed/cancelled).
     */
    Instant completedAt;
}
