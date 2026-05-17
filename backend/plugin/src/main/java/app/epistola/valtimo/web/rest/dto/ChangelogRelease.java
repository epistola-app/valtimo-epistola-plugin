package app.epistola.valtimo.web.rest.dto;

import java.util.List;

/**
 * One release block parsed from the bundled CHANGELOG.md, in file order
 * (newest first). {@code version} is the bracketed heading (e.g. "Unreleased"
 * or "0.8.0"); {@code date} is the ISO date after it, or {@code null} when none
 * (e.g. the Unreleased block).
 */
public record ChangelogRelease(
        String version,
        String date,
        List<ChangelogSection> sections
) {}
