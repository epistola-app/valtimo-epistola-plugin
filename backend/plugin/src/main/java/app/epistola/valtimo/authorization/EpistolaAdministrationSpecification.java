/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
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
