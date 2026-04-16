package app.epistola.valtimo.domain;

/**
 * Basic information about an Epistola template.
 *
 * @param id          The unique identifier of the template
 * @param name        The display name of the template
 * @param description Optional description of the template
 * @param catalogId   The slug identifier of the catalog this template belongs to
 * @param catalogName The display name of the catalog this template belongs to
 */
public record TemplateInfo(
        String id,
        String name,
        String description,
        String catalogId,
        String catalogName
) {
}
