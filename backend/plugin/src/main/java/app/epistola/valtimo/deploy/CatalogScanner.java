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
package app.epistola.valtimo.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Scans the classpath for Epistola catalog directories.
 * <p>
 * Catalogs are expected at:
 * {@code config/epistola/catalogs/{slug}/catalog.json}
 * <p>
 * Each {@code catalog.json} must contain at least the catalog's key and {@code release.version}. The
 * key is {@code catalog.key} from catalog wire v8 on, and {@code catalog.slug} before it; both are
 * read. A v8 manifest also names its catalog's {@code catalog.registry} and {@code catalog.namespace}.
 */
@Slf4j
public class CatalogScanner {

    private static final String CATALOG_PATTERN = "classpath*:config/epistola/catalogs/*/catalog.json";

    private final ObjectMapper objectMapper;

    public CatalogScanner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * A catalog found on the classpath, with its key, version, and base path for resource loading.
     *
     * @param slug      The catalog key from catalog.json (e.g. "valtimo-demo"); named slug for the
     *                  admin API, which keeps the name
     * @param version   The release version from catalog.json (e.g. "1.0.0")
     * @param basePath  The classpath directory containing the catalog (e.g. "config/epistola/catalogs/valtimo-demo")
     * @param registry  The registry a wire v8 manifest names; {@code null} before v8
     * @param namespace The namespace a wire v8 manifest names; {@code null} before v8
     */
    public record CatalogOnClasspath(String slug, String version, String basePath, String registry, String namespace) {

        /** A catalog whose manifest predates wire v8, and so names no registry or namespace. */
        public CatalogOnClasspath(String slug, String version, String basePath) {
            this(slug, version, basePath, null, null);
        }

        /** Whether the manifest names its own registry and namespace (catalog wire v8). */
        public boolean isNamed() {
            return registry != null && namespace != null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Scan the classpath for catalog.json files and extract metadata.
     *
     * @return List of catalogs found on the classpath (never null)
     */
    public List<CatalogOnClasspath> scan() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        Resource[] resources;
        try {
            resources = resolver.getResources(CATALOG_PATTERN);
        } catch (IOException e) {
            log.warn("Failed to scan for catalog definitions: {}", e.getMessage());
            return Collections.emptyList();
        }

        List<CatalogOnClasspath> catalogs = new ArrayList<>();
        for (Resource resource : resources) {
            try (InputStream is = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);

                String slug = extractString(root, "catalog", "key");
                if (slug == null || slug.isBlank()) {
                    slug = extractString(root, "catalog", "slug");
                }
                String version = extractString(root, "release", "version");
                String registry = extractString(root, "catalog", "registry");
                String namespace = extractString(root, "catalog", "namespace");

                if (slug == null || slug.isBlank()) {
                    log.warn("Skipping catalog without catalog.key or catalog.slug: {}", resource.getDescription());
                    continue;
                }
                if (version == null || version.isBlank()) {
                    log.warn("Skipping catalog without release.version: {} (slug={})",
                            resource.getDescription(), slug);
                    continue;
                }

                // Derive the base path from the resource URL.
                // The resource points to e.g. "config/epistola/catalogs/valtimo-demo/catalog.json"
                // We need the directory: "config/epistola/catalogs/valtimo-demo"
                String basePath = deriveBasePath(resource, slug);
                if (basePath == null) {
                    log.warn("Could not determine base path for catalog '{}': {}", slug, resource.getDescription());
                    continue;
                }

                catalogs.add(new CatalogOnClasspath(slug, version, basePath, blankToNull(registry), blankToNull(namespace)));
                log.debug("Found catalog on classpath: slug={}, version={}, basePath={}", slug, version, basePath);
            } catch (IOException e) {
                log.warn("Failed to parse catalog.json from {}: {}", resource.getDescription(), e.getMessage());
            }
        }

        log.debug("Found {} catalogs on classpath", catalogs.size());
        return catalogs;
    }

    /**
     * Extract a nested string value from a JSON tree.
     * For example, extractString(root, "catalog", "slug") returns root.catalog.slug as text.
     */
    private String extractString(JsonNode root, String... path) {
        JsonNode node = root;
        for (String field : path) {
            if (node == null || !node.has(field)) {
                return null;
            }
            node = node.get(field);
        }
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /**
     * Derive the classpath base path for a catalog resource.
     * Uses the known pattern to reconstruct the directory path.
     */
    private String deriveBasePath(Resource resource, String slug) {
        try {
            String url = resource.getURL().toString();
            // Look for the config/epistola/catalogs/{slug} portion
            String marker = "config/epistola/catalogs/";
            int idx = url.indexOf(marker);
            if (idx >= 0) {
                // The basePath is everything from "config/epistola/catalogs/{dirName}"
                String fromMarker = url.substring(idx);
                // Remove the trailing "/catalog.json"
                int lastSlash = fromMarker.lastIndexOf('/');
                if (lastSlash > 0) {
                    return fromMarker.substring(0, lastSlash);
                }
            }
        } catch (IOException e) {
            log.debug("Could not resolve URL for resource: {}", resource.getDescription());
        }
        // Fallback: construct from the slug (works when directory name matches slug)
        return "config/epistola/catalogs/" + slug;
    }
}
