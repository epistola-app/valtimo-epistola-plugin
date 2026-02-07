package app.epistola.valtimo.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Information about an Epistola template variant.
 *
 * @param id         The unique identifier of the variant
 * @param templateId The ID of the template this variant belongs to
 * @param name       The display name of the variant
 * @param tags       Optional tags for categorizing the variant
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VariantInfo(
        String id,
        String templateId,
        String name,
        List<String> tags
) {
}
