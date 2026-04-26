package app.epistola.valtimo.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Information about an Epistola catalog.
 *
 * @param id   The unique identifier of the catalog
 * @param name The display name of the catalog
 * @param type The type of the catalog (e.g. "standard", "custom")
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogInfo(
        String id,
        String name,
        String type
) {
}
