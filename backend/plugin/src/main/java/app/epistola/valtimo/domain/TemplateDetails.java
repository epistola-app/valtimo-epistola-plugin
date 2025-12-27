package app.epistola.valtimo.domain;

import java.util.List;

/**
 * Detailed information about an Epistola template including its fields.
 *
 * @param id     The unique identifier of the template
 * @param name   The display name of the template
 * @param fields The list of fields that can be mapped for this template
 */
public record TemplateDetails(
        String id,
        String name,
        List<TemplateField> fields
) {
}
