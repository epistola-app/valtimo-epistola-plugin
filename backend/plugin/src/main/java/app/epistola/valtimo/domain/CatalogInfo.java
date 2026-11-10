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
package app.epistola.valtimo.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An Epistola catalog as the configurators list it.
 *
 * @param id        the reference a process link stores: {@code registry/namespace/key}, see
 *                  {@link CatalogRef}
 * @param name      the catalog's display name
 * @param registry  the registry the catalog is named in (a DNS name, or {@code local})
 * @param namespace the namespace within that registry
 * @param key       the catalog's key within its namespace
 * @param editable  whether the catalog can be changed in that Epistola installation
 * @param deployedToEnvironment whether a release of it is deployed to the environment the list was
 *                  asked for, or null when it was asked for without one. A configurator lists a
 *                  catalog that is not deployed too, marked, so a link can be configured before
 *                  its catalog is rolled out (ADR 0009)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogInfo(
        String id,
        String name,
        String registry,
        String namespace,
        String key,
        boolean editable,
        Boolean deployedToEnvironment
) {

    /** A catalog listed without an environment to mark it against. */
    public CatalogInfo(String id, String name, String registry, String namespace, String key, boolean editable) {
        this(id, name, registry, namespace, key, editable, null);
    }

    /** This catalog, marked as deployed to the environment the list was asked for, or not. */
    public CatalogInfo deployedToEnvironment(boolean deployed) {
        return new CatalogInfo(id, name, registry, namespace, key, editable, deployed);
    }
}
