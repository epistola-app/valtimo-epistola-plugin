package app.epistola.valtimo.web.rest.dto;

/**
 * A catalog discovered on the application classpath, shown in the admin page so an
 * operator can manually (re)deploy it to a plugin configuration's Epistola
 * installation — e.g. when the startup auto-deploy failed or was skipped.
 *
 * <p>{@code deployedVersion} is tracked in memory per running instance and resets
 * on restart: it is {@code null} until a startup sync or a manual redeploy has run
 * in this process for the configuration. {@code upToDate} is therefore best-effort
 * (it can read {@code false} right after a restart even though Epistola already has
 * the catalog) — it is a hint, not a guarantee.
 */
public record ClasspathCatalog(
        String slug,
        String version,
        String deployedVersion,
        boolean upToDate
) {}
