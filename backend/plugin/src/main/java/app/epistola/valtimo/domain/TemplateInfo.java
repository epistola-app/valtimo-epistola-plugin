package app.epistola.valtimo.domain;

/**
 * Basic information about an Epistola template.
 *
 * @param id          The unique identifier of the template
 * @param name        The display name of the template
 * @param description Optional description of the template
 */
public record TemplateInfo(
        String id,
        String name,
        String description
) {
}
