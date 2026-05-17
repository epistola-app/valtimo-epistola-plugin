package app.epistola.valtimo.web.rest.dto;

import java.util.List;

/**
 * A "Keep a Changelog" section within a release — e.g. "Added",
 * "Changed (breaking)" — and its bullet items (raw text, structure only).
 */
public record ChangelogSection(
        String title,
        List<String> items
) {}
