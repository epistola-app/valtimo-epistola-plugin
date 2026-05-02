package app.epistola.valtimo.web.rest.dto;

import java.util.Map;

/**
 * Request to validate the JSONata syntax of one or more action-config fields.
 * All fields are optional; null/blank fields are skipped.
 */
public record ValidateJsonataRequest(
        String dataMapping,
        String filename,
        String variantId,
        Map<String, String> variantAttributeValues
) {}
