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
import com.ritense.authorization.specification.AuthorizationSpecificationFactory;
import kotlin.jvm.functions.Function0;

import java.util.List;

@SuppressWarnings("deprecation")
public class EpistolaAdministrationSpecificationFactory implements AuthorizationSpecificationFactory<EpistolaAdministration> {

    @Override
    public AuthorizationSpecification<EpistolaAdministration> create(
            AuthorizationRequest<EpistolaAdministration> request,
            Function0<? extends List<Permission>> permissionSupplier
    ) {
        return new EpistolaAdministrationSpecification(request, permissionSupplier.invoke());
    }

    @Override
    public AuthorizationSpecification<EpistolaAdministration> create(
            AuthorizationRequest<EpistolaAdministration> request,
            List<Permission> permissions
    ) {
        return new EpistolaAdministrationSpecification(request, permissions);
    }

    @Override
    public boolean canCreate(AuthorizationRequest<?> request, Function0<? extends List<Permission>> permissionSupplier) {
        return EpistolaAdministration.class.equals(request.getResourceType());
    }

    @Override
    public boolean canCreate(AuthorizationRequest<?> request, List<Permission> permissions) {
        return EpistolaAdministration.class.equals(request.getResourceType());
    }
}
