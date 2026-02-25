package app.epistola.valtimo.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Information about an Epistola environment.
 *
 * @param id   The unique identifier of the environment
 * @param name The display name of the environment
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EnvironmentInfo(
        String id,
        String name
) {
}
