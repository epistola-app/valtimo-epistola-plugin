package app.epistola.valtimo.authorization;

/**
 * Marker resource type for Epistola plugin administration permissions.
 *
 * <p>Not a JPA entity; the Valtimo PBAC framework only uses the {@link Class} token to
 * scope {@code Permission} rows and to look up the {@link com.ritense.authorization.ResourceActionProvider}
 * and {@link com.ritense.authorization.specification.AuthorizationSpecificationFactory} beans.
 *
 * <p>Granted by default to {@code ROLE_ADMIN} via
 * {@code config/epistola/permission/epistola-admin-default.permission.json}.
 */
public final class EpistolaAdministration {
    private EpistolaAdministration() {
    }
}
