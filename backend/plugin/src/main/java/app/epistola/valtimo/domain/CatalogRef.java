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

import java.util.Collection;
import java.util.List;
import java.util.function.Function;

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
 *       catalogs by key ({@link #select}).</li>
 * </ul>
 * None of the three parts may contain a slash, so the forms cannot be confused.
 *
 * @param registry  the registry, or {@code null} for the server's default registry
 * @param namespace the namespace, or {@code null} when unqualified
 * @param key       the catalog key
 */
public record CatalogRef(String registry, String namespace, String key) {

    /** The registry of names valid only within the organisation; it needs no namespace grant. */
    public static final String LOCAL_REGISTRY = "local";

    /** The length Epistola pads a tenant key to when it makes a namespace of it. */
    private static final int MIN_NAMESPACE_LENGTH = 3;

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

    /**
     * The namespace Epistola's 2.0 upgrade named a tenant's own catalogs in: the tenant key, padded
     * with {@code 0} to the 3 characters a namespace needs ({@code ab} becomes {@code ab0}).
     */
    public static String migratedNamespace(String tenantId) {
        StringBuilder namespace = new StringBuilder(tenantId);
        while (namespace.length() < MIN_NAMESPACE_LENGTH) {
            namespace.append('0');
        }
        return namespace.toString();
    }

    /**
     * What Epistola's 2.0 upgrade named the catalog of {@code tenantId} with this key, had the tenant
     * authored it: {@code local/<tenant>/<key>}. A catalog installed from or bound to Epistola Exchange
     * kept its Exchange name instead.
     */
    public CatalogRef asMigrated(String tenantId) {
        return new CatalogRef(LOCAL_REGISTRY, migratedNamespace(tenantId), key);
    }

    /**
     * The catalogs among {@code catalogs} this reference names, for {@code tenantId}; several when
     * it is ambiguous. A key saved against contract 1.x names every catalog with that key. Before
     * 2.0 a key named one catalog of a tenant, and the upgrade named an authored one
     * {@link #asMigrated}; so when the key names several and one of them is that, it is that one.
     *
     * @param nameOf a catalog's full name; one catalog may appear more than once (a deployment per
     *               release), and all its entries are kept
     */
    public <T> List<T> select(Collection<T> catalogs, Function<T, CatalogRef> nameOf, String tenantId) {
        List<T> matches = catalogs.stream()
                .filter(c -> {
                    CatalogRef name = nameOf.apply(c);
                    return matches(name.registry(), name.namespace(), name.key());
                })
                .toList();
        if (isQualified() || matches.stream().map(nameOf).distinct().count() < 2) {
            return matches;
        }
        CatalogRef migrated = asMigrated(tenantId);
        List<T> own = matches.stream().filter(c -> nameOf.apply(c).equals(migrated)).toList();
        return own.isEmpty() ? matches : own;
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
