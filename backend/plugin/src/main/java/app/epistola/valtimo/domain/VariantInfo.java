package app.epistola.valtimo.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Information about an Epistola template variant.
 *
 * @param id         The unique identifier of the variant
 * @param templateId The ID of the template this variant belongs to
 * @param name       The display name of the variant
 * @param attributes Key-value attributes for categorizing/selecting the variant
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VariantInfo(
        String id,
        String templateId,
        String name,
        Map<String, String> attributes
) {
}
