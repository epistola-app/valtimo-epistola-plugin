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
