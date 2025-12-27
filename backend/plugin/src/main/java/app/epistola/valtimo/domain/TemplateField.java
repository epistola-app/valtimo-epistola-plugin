package app.epistola.valtimo.domain;

/**
 * Represents a field in an Epistola template that can be mapped to data.
 *
 * @param name        The name/key of the field in the template
 * @param type        The data type of the field (e.g., "string", "number", "date")
 * @param required    Whether this field is required for document generation
 * @param description Optional description of the field's purpose
 */
public record TemplateField(
        String name,
        String type,
        boolean required,
        String description
) {
}
