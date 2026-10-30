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
package app.epistola.valtimo.service;

import app.epistola.client.model.VariantSelectionAttribute;
import app.epistola.valtimo.domain.*;

import java.util.List;
import java.util.Map;

/**
 * Service for interacting with Epistola document generation.
 * <p>
 * All methods require baseUrl and apiKey for authentication with the Epistola API.
 * These values are typically obtained from the plugin configuration.
 * <p>
 * A {@code catalogId} is a catalog reference as a process link stores it ({@link CatalogRef}):
 * {@code registry/namespace/key}, {@code namespace/key}, or a bare key saved against contract 1.x,
 * which is looked up among the tenant's catalogs.
 * <p>
 * The reads take an optional {@code environmentId}. Without one, every catalog the tenant has is
 * offered — including those installed only as another catalog's dependency, which the contract does
 * not tell apart — and content is read from a catalog's highest release. With one, only the catalogs
 * deployed to that environment are offered, and content is read from the release it serves: its
 * deployed draft, else its highest deployed release. That is what generation in that environment
 * renders, so a configurator never offers a template the environment cannot produce.
 * <p>
 * They also take an optional {@code versionRange} ({@link VersionRanges}), which a process link sets
 * to pin a release or a range of them. Content is then read from the highest release that satisfies
 * it — of those deployed to the environment, when there is one — which is the release generation
 * with the same range renders. A range never matches a draft. A range that matches nothing is a
 * 404, one that is not a range a 400.
 */
public interface EpistolaService {

    /**
     * Get all available catalogs for a tenant.
     *
     * @param baseUrl  The Epistola API base URL
     * @param apiKey   The API key for authentication
     * @param tenantId The tenant ID in Epistola
     * @return List of available catalogs
     */
    default List<CatalogInfo> getCatalogs(String baseUrl, String apiKey, String tenantId) {
        return getCatalogs(baseUrl, apiKey, tenantId, null);
    }

    /** As the overload without it, narrowed to what {@code environmentId} serves when not null. */
    List<CatalogInfo> getCatalogs(String baseUrl, String apiKey, String tenantId, String environmentId);

    /**
     * Fetch Epistola system metadata, including the server application version and
     * the contract version exposed by the server.
     *
     * @param baseUrl The Epistola API base URL
     * @param apiKey  The API key for authentication
     * @return System metadata from the server ping endpoint
     */
    SystemInfo getSystemInfo(String baseUrl, String apiKey);

    /**
     * Epistola server metadata returned by the ping endpoint.
     */
    record SystemInfo(
            String serverVersion,
            String contractVersion
    ) {}

    /**
     * Get all available templates for a tenant and catalog.
     *
     * @param baseUrl   The Epistola API base URL
     * @param apiKey    The API key for authentication
     * @param tenantId  The tenant ID in Epistola
     * @param catalogId The catalog ID in Epistola
     * @return List of available templates
     */
    default List<TemplateInfo> getTemplates(String baseUrl, String apiKey, String tenantId, String catalogId) {
        return getTemplates(baseUrl, apiKey, tenantId, catalogId, null, null);
    }

    /**
     * As the overload without them, narrowed to what {@code environmentId} serves and read from the
     * release {@code versionRange} selects, each when not null.
     */
    List<TemplateInfo> getTemplates(
            String baseUrl, String apiKey, String tenantId, String catalogId, String environmentId, String versionRange);

    /**
     * Get template details including its fields.
     *
     * @param baseUrl    The Epistola API base URL
     * @param apiKey     The API key for authentication
     * @param tenantId   The tenant ID in Epistola
     * @param catalogId  The catalog ID in Epistola
     * @param templateId The ID of the template
     * @return Template details with fields
     */
    default TemplateDetails getTemplateDetails(String baseUrl, String apiKey, String tenantId, String catalogId, String templateId) {
        return getTemplateDetails(baseUrl, apiKey, tenantId, catalogId, templateId, null, null);
    }

    /**
     * As the overload without them, narrowed to what {@code environmentId} serves and read from the
     * release {@code versionRange} selects, each when not null.
     */
    TemplateDetails getTemplateDetails(
            String baseUrl, String apiKey, String tenantId, String catalogId, String templateId,
            String environmentId, String versionRange);

    /**
     * Get all attribute definitions for a tenant and catalog.
     * These define the keys that can be used for variant selection.
     *
     * @param baseUrl   The Epistola API base URL
     * @param apiKey    The API key for authentication
     * @param tenantId  The tenant ID in Epistola
     * @param catalogId The catalog ID in Epistola
     * @return List of attribute definitions
     */
    default List<AttributeDefinition> getAttributes(String baseUrl, String apiKey, String tenantId, String catalogId) {
        return getAttributes(baseUrl, apiKey, tenantId, catalogId, null, null);
    }

    /**
     * As the overload without them, narrowed to what {@code environmentId} serves and read from the
     * release {@code versionRange} selects, each when not null.
     */
    List<AttributeDefinition> getAttributes(
            String baseUrl, String apiKey, String tenantId, String catalogId, String environmentId, String versionRange);

    /**
     * Get all available environments for a tenant.
     *
     * @param baseUrl  The Epistola API base URL
     * @param apiKey   The API key for authentication
     * @param tenantId The tenant ID in Epistola
     * @return List of available environments
     */
    List<EnvironmentInfo> getEnvironments(String baseUrl, String apiKey, String tenantId);

    /**
     * Get all variants for a specific template.
     *
     * @param baseUrl    The Epistola API base URL
     * @param apiKey     The API key for authentication
     * @param tenantId   The tenant ID in Epistola
     * @param catalogId  The catalog ID in Epistola
     * @param templateId The ID of the template
     * @return List of variants for the template
     */
    default List<VariantInfo> getVariants(String baseUrl, String apiKey, String tenantId, String catalogId, String templateId) {
        return getVariants(baseUrl, apiKey, tenantId, catalogId, templateId, null, null);
    }

    /**
     * As the overload without them, narrowed to what {@code environmentId} serves and read from the
     * release {@code versionRange} selects, each when not null.
     */
    List<VariantInfo> getVariants(
            String baseUrl, String apiKey, String tenantId, String catalogId, String templateId,
            String environmentId, String versionRange);

    /**
     * The released versions of a catalog a process link may pin, highest first, and the one it renders
     * with {@code versionRange}: in an environment, of those deployed to it.
     *
     * @param environmentId the environment the link generates in, or null for every release
     * @param versionRange  the link's range, or null for none
     */
    CatalogReleases getReleases(
            String baseUrl, String apiKey, String tenantId, String catalogId, String environmentId, String versionRange);

    /**
     * Generate a document using a template.
     * <p>
     * Variant selection modes (mutually exclusive):
     * - If neither variantId nor variantAttributes is provided, the template's default variant is used.
     * - If variantId is provided, that specific variant is used.
     * - If variantAttributes is provided, the API selects the matching variant automatically.
     *
     * @param baseUrl            The Epistola API base URL
     * @param apiKey             The API key for authentication
     * @param tenantId           The tenant ID in Epistola
     * @param catalogId          The catalog ID in Epistola
     * @param templateId         The ID of the template to use
     * @param variantId          The ID of the variant to use (nullable when using attribute selection)
     * @param variantAttributes  Attributes for automatic variant selection (nullable when using explicit variantId)
     * @param environmentId      The ID of the environment (optional)
     * @param versionRange       The release of the catalog to render, as a version range
     *                           ({@link VersionRanges}); null for the environment's draft, else
     *                           the highest release it serves (the catalog's highest without one)
     * @param data               The data to populate the template with (already resolved values).
     *                           Rich text an editor tagged with its format ({@code RichTextValues})
     *                           must be sent as a document; an implementation does that with
     *                           {@code RichTextValues#toDocuments} before building the request.
     * @param format             The output format (PDF or HTML)
     * @param filename           The desired filename for the generated document
     * @param correlationId      Optional correlation ID for tracking
     * @param routingKey         Optional routing key controlling which collector node receives the
     *                           generation result. Pass a key produced by
     *                           {@code EpistolaResultCollectorRunner#routingKeyFor(...)} so the result
     *                           comes back to this Valtimo node. May be {@code null}, in which case
     *                           the server uses the request id as the routing key.
     * @return The generation job result containing the request ID
     */
    GenerationJobResult submitGenerationJob(
            String baseUrl,
            String apiKey,
            String tenantId,
            String catalogId,
            String templateId,
            String variantId,
            List<VariantSelectionAttribute> variantAttributes,
            String environmentId,
            String versionRange,
            Map<String, Object> data,
            FileFormat format,
            String filename,
            String correlationId,
            String routingKey
    );

    /**
     * Get the status of a document generation job.
     *
     * @param baseUrl   The Epistola API base URL
     * @param apiKey    The API key for authentication
     * @param tenantId  The tenant ID in Epistola
     * @param requestId The request/job ID returned from generateDocument
     * @return Detailed job status information
     */
    GenerationJobDetail getJobStatus(String baseUrl, String apiKey, String tenantId, String requestId);

    /**
     * Download a generated document.
     *
     * @param baseUrl    The Epistola API base URL
     * @param apiKey     The API key for authentication
     * @param tenantId   The tenant ID in Epistola
     * @param documentId The ID of the document to download
     * @return The document bytes
     */
    byte[] downloadDocument(String baseUrl, String apiKey, String tenantId, String documentId);

    /**
     * Import a catalog ZIP archive into Epistola.
     * Posts a multipart form with the ZIP file to the catalog import endpoint.
     *
     * @param baseUrl     The Epistola API base URL
     * @param apiKey      The API key for authentication
     * @param tenantId    The tenant ID in Epistola
     * @param zipBytes    The catalog ZIP archive bytes
     * @param registry    The registry to name the catalog in when the archive predates catalog wire
     *                    v8 and does not name its own; {@code null} for a v8 archive
     * @param namespace   The namespace, likewise; {@code null} for a v8 archive
     * @return The release the import installed, or found already installed
     */
    ImportCatalogResult importCatalog(
            String baseUrl, String apiKey, String tenantId, byte[] zipBytes, String registry, String namespace);

    /**
     * Result of a catalog import operation. Since contract 2.0 an import installs the archive's
     * release as a whole, so there are no per-resource counts.
     *
     * @param newlyInstalled false when the same release was already installed
     * @param total          the number of resources in the release
     */
    record ImportCatalogResult(
            String registry,
            String namespace,
            String catalogKey,
            String catalogName,
            String version,
            boolean newlyInstalled,
            int total
    ) {}

    /**
     * Preview a document by rendering it without creating a generation job.
     * Returns the rendered PDF bytes.
     *
     * @param baseUrl       The Epistola API base URL
     * @param apiKey        The API key for authentication
     * @param tenantId      The tenant ID in Epistola
     * @param templateId    The template to preview
     * @param variantId     The variant ID (optional)
     * @param environmentId The environment ID (optional)
     * @param versionRange  The release to render, as for {@link #submitGenerationJob} (optional)
     * @param data          The data to render with; tagged rich text is sent as a document, as
     *                      for {@link #submitGenerationJob}
     * @return PDF bytes
     */
    java.io.InputStream previewDocument(
            String baseUrl,
            String apiKey,
            String tenantId,
            String catalogId,
            String templateId,
            String variantId,
            String environmentId,
            String versionRange,
            Map<String, Object> data
    );
}
