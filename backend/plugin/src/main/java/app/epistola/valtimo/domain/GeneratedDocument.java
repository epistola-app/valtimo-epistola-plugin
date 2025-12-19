package app.epistola.valtimo.domain;

import lombok.Builder;
import lombok.Value;

/**
 * Represents a generated document result.
 */
@Value
@Builder
public class GeneratedDocument {

    /**
     * Unique identifier of the generated document in Epistola.
     */
    String documentId;
}
