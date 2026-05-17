package app.epistola.valtimo.web.rest.dto;

/**
 * A catalog discovered on the application classpath, with whether it currently
 * exists in the connected Epistola installation. Existence is resolved by
 * querying Epistola at request time (Epistola does not expose a catalog version,
 * so this is presence, not a version comparison).
 *
 * <p>{@code status}:
 * <ul>
 *   <li>{@code IN_EPISTOLA} — a catalog with this slug exists in Epistola.</li>
 *   <li>{@code NOT_IN_EPISTOLA} — Epistola was reached and has no such catalog
 *       (e.g. the startup auto-deploy failed or was skipped); use Redeploy.</li>
 *   <li>{@code UNKNOWN} — Epistola could not be reached, so existence could not
 *       be determined (not asserted as missing — reachability is the health tab's
 *       job).</li>
 * </ul>
 */
public record ClasspathCatalog(
        String slug,
        String version,
        String status
) {
    public static final String IN_EPISTOLA = "IN_EPISTOLA";
    public static final String NOT_IN_EPISTOLA = "NOT_IN_EPISTOLA";
    public static final String UNKNOWN = "UNKNOWN";
}
