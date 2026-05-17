package app.epistola.valtimo.web.rest.dto;

/**
 * Outcome of a manual single-catalog redeploy from the admin page.
 *
 * <p>{@code success=true} means the catalog ZIP was built from the classpath and
 * accepted by Epistola's import endpoint; {@code installed} / {@code updated} /
 * {@code failed} / {@code total} are the per-resource counts Epistola reported.
 * {@code success=false} means the build or import failed — {@code errorMessage}
 * carries the reason and the resource counts are zero. The controller maps a
 * failed redeploy to HTTP 502 so standard client error handling applies while
 * still returning this body.
 */
public record CatalogRedeployResult(
        String slug,
        String version,
        boolean success,
        String catalogKey,
        int installed,
        int updated,
        int failed,
        int total,
        String errorMessage
) {}
