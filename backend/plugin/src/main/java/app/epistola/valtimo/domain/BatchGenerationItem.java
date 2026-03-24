package app.epistola.valtimo.domain;

import java.util.Map;

/**
 * Represents a single item in a batch generation request.
 * Maps to the contract's BatchGenerationItem schema.
 *
 * @param templateId    The ID of the template to use
 * @param data          The data to populate the template with
 * @param variantId     Optional variant ID (nullable when using attribute selection)
 * @param environmentId Optional environment ID
 * @param filename      Optional filename for the generated document
 * @param correlationId Optional correlation ID for tracking
 */
public record BatchGenerationItem(
        String templateId,
        Map<String, Object> data,
        String variantId,
        String environmentId,
        String filename,
        String correlationId
) {
}
