package app.epistola.valtimo.authorization;

import com.ritense.authorization.permission.Permission;
import com.ritense.authorization.request.AuthorizationRequest;
import com.ritense.authorization.specification.AuthorizationSpecification;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.util.List;

/**
 * Degenerate authorization specification for {@link EpistolaAdministration}.
 *
 * <p>{@code EpistolaAdministration} is not a JPA entity, so the JPA-side methods are
 * no-ops: {@link #toPredicate} returns an always-true predicate and
 * {@link #identifierToEntity} throws.
 *
 * <p>The framework only consults this specification for entity-less requests
 * (i.e. {@code requirePermission(...)} called with no entities), in which case
 * {@code AuthorizationSpecification.isAuthorizedForEntity} reduces to
 * {@code permission.appliesTo(EpistolaAdministration.class, null, ...)} —
 * the standard "does a grant exist?" path.
 */
@SuppressWarnings("deprecation")
public class EpistolaAdministrationSpecification extends AuthorizationSpecification<EpistolaAdministration> {

    public EpistolaAdministrationSpecification(
            AuthorizationRequest<EpistolaAdministration> authRequest,
            List<Permission> permissions
    ) {
        super(authRequest, permissions);
    }

    @Override
    public Predicate toPredicate(Root<EpistolaAdministration> root, AbstractQuery<?> query, CriteriaBuilder criteriaBuilder) {
        return criteriaBuilder.conjunction();
    }

    @Override
    public EpistolaAdministration identifierToEntity(String identifier) {
        throw new UnsupportedOperationException("EpistolaAdministration has no persisted identifier");
    }
}
