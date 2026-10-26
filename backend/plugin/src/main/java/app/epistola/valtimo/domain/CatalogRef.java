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

/**
 * How the plugin names an Epistola catalog: {@code registry/namespace/key}.
 * <p>
 * Since contract 2.0 a catalog is identified by its registry (a DNS name, or {@code local}), its
 * namespace and its key, and every catalog-scoped call needs all three. A process link stores the
 * catalog as one string, so the plugin writes it in that form. Two shorter forms are read too:
 * <ul>
 *   <li>{@code namespace/key} — the server's default registry;</li>
 *   <li>{@code key} — what every process link saved against contract 1.x holds. It is
 *       <em>unqualified</em>: the namespace is not in it, and is looked up among the tenant's
 *       catalogs by key ({@code EpistolaServiceImpl}).</li>
 * </ul>
 * None of the three parts may contain a slash, so the forms cannot be confused.
 *
 * @param registry  the registry, or {@code null} for the server's default registry
 * @param namespace the namespace, or {@code null} when unqualified
 * @param key       the catalog key
 */
public record CatalogRef(String registry, String namespace, String key) {

    public CatalogRef {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A catalog reference needs a key");
        }
        if (registry != null && namespace == null) {
            throw new IllegalArgumentException("A catalog reference with a registry needs a namespace");
        }
    }

    /**
     * Read a stored catalog reference.
     *
     * @throws IllegalArgumentException when it is blank, has more than three parts or an empty one
     */
    public static CatalogRef parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A catalog reference is required");
        }
        String[] parts = value.trim().split("/", -1);
        for (String part : parts) {
            if (part.isBlank()) {
                throw new IllegalArgumentException("Not a catalog reference: '" + value + "'");
            }
        }
        return switch (parts.length) {
            case 1 -> new CatalogRef(null, null, parts[0]);
            case 2 -> new CatalogRef(null, parts[0], parts[1]);
            case 3 -> new CatalogRef(parts[0], parts[1], parts[2]);
            default -> throw new IllegalArgumentException("Not a catalog reference: '" + value + "'");
        };
    }

    /** Whether the namespace is known, so the catalog can be addressed without a lookup. */
    public boolean isQualified() {
        return namespace != null;
    }

    /** Whether {@code other}, a catalog as the server names it (always in full), is this one. */
    public boolean matches(String otherRegistry, String otherNamespace, String otherKey) {
        if (!key.equals(otherKey)) {
            return false;
        }
        if (namespace != null && !namespace.equals(otherNamespace)) {
            return false;
        }
        return registry == null || registry.equals(otherRegistry);
    }

    /** The stored form: as many parts as are known. */
    @Override
    public String toString() {
        if (namespace == null) {
            return key;
        }
        return registry == null ? namespace + "/" + key : registry + "/" + namespace + "/" + key;
    }
}
