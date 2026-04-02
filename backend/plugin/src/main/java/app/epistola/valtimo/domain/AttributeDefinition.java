package app.epistola.valtimo.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An attribute definition for variant selection within a tenant.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AttributeDefinition(
        String key,
        String description
) {}
