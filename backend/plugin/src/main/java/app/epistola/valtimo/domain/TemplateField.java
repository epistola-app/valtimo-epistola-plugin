package app.epistola.valtimo.domain;

import java.util.List;

/**
 * Represents a field in an Epistola template that can be mapped to data.
 * Supports nested structures through the children property.
 *
 * @param name        The leaf name of the field (e.g., "total")
 * @param path        The dot-notation path (e.g., "invoice.lineItems[].total")
 * @param type        The JSON Schema type (e.g., "string", "number", "object", "array")
 * @param fieldType   Whether this is a SCALAR, OBJECT, or ARRAY field
 * @param required    Whether this field is required for document generation
 * @param description Optional description of the field's purpose
 * @param children    Child fields for OBJECT and ARRAY-of-object types (empty list for SCALAR)
 */
public record TemplateField(
        String name,
        String path,
        String type,
        FieldType fieldType,
        boolean required,
        String description,
        List<TemplateField> children
) {
    public enum FieldType {
        SCALAR,
        OBJECT,
        ARRAY
    }
}
