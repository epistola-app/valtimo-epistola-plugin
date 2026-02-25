package app.epistola.valtimo.domain;

/**
 * Status of a document generation job in Epistola.
 */
public enum GenerationJobStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}
