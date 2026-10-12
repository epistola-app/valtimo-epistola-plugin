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
package app.epistola.valtimo.richtext;

import app.epistola.client.model.VariantSelectionAttribute;
import app.epistola.valtimo.domain.AttributeDefinition;
import app.epistola.valtimo.domain.CatalogInfo;
import app.epistola.valtimo.domain.EnvironmentInfo;
import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GenerationJobDetail;
import app.epistola.valtimo.domain.GenerationJobResult;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateInfo;
import app.epistola.valtimo.domain.VariantInfo;
import app.epistola.valtimo.logging.Failures;
import app.epistola.valtimo.service.EpistolaService;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts a letter's rich-text fields to Epistola's document format on its way out, around the
 * {@link EpistolaService} that sends it (ADR 0008).
 *
 * <p>A decorator, so the client stays a client: it talks to Epistola and nothing else, and this
 * class transforms data and nothing else. It sits around the service rather than at each caller
 * because generation, the preview component, the composer and the retry form all reach Epistola
 * through {@link #submitGenerationJob} or {@link #previewDocument}, and converting at one point is
 * what keeps the preview and the letter from differing.
 *
 * <p>Which fields are rich text is only known from the template's contract, so it is read first,
 * and kept for a short while: the composer previews on every change, and the contract does not.
 * When it cannot be read, the data goes as mapped, because Epistola validates it regardless and
 * refuses what it cannot render with a message of its own.
 */
@Slf4j
public class RichTextEpistolaService implements EpistolaService {

    /** How long a template's fields are trusted: long enough to cover a session of previews. */
    static final Duration FIELDS_TTL = Duration.ofMinutes(1);
    private static final int MAX_CACHED_TEMPLATES = 512;

    private record TemplateKey(String baseUrl, String tenantId, String catalogId, String templateId) {
    }

    private record CachedFields(List<TemplateField> fields, Instant until) {
    }

    private final EpistolaService delegate;
    private final RichTextConverter converter;
    private final Clock clock;
    private final Map<TemplateKey, CachedFields> fieldsByTemplate = new ConcurrentHashMap<>();

    public RichTextEpistolaService(EpistolaService delegate, RichTextConverter converter) {
        this(delegate, converter, Clock.systemUTC());
    }

    RichTextEpistolaService(EpistolaService delegate, RichTextConverter converter, Clock clock) {
        this.delegate = delegate;
        this.converter = converter;
        this.clock = clock;
    }

    @Override
    public GenerationJobResult submitGenerationJob(
            String baseUrl, String apiKey, String tenantId, String catalogId, String templateId,
            String variantId, List<VariantSelectionAttribute> variantAttributes, String environmentId,
            Map<String, Object> data, FileFormat format, String filename, String correlationId,
            String routingKey
    ) {
        Map<String, Object> converted = convert(baseUrl, apiKey, tenantId, catalogId, templateId, data, true);
        return delegate.submitGenerationJob(baseUrl, apiKey, tenantId, catalogId, templateId, variantId,
                variantAttributes, environmentId, converted, format, filename, correlationId, routingKey);
    }

    @Override
    public InputStream previewDocument(
            String baseUrl, String apiKey, String tenantId, String catalogId, String templateId,
            String variantId, String environmentId, Map<String, Object> data
    ) {
        Map<String, Object> converted = convert(baseUrl, apiKey, tenantId, catalogId, templateId, data, false);
        return delegate.previewDocument(
                baseUrl, apiKey, tenantId, catalogId, templateId, variantId, environmentId, converted);
    }

    private Map<String, Object> convert(
            String baseUrl, String apiKey, String tenantId, String catalogId, String templateId,
            Map<String, Object> data, boolean generating
    ) {
        if (data == null || data.isEmpty()) {
            return data;
        }
        List<TemplateField> fields = fields(baseUrl, apiKey, tenantId, catalogId, templateId);
        if (fields == null) {
            return data;
        }
        // Field paths and kinds of content, never the text. A warning when a letter is generated —
        // the author should know their form offers more than the letter shows — and debug for a
        // preview, which the composer repeats on every change.
        return converter.normalize(data, fields, (path, losses) -> {
            if (generating) {
                log.warn("Rich text for field '{}' of template '{}' lost what Epistola cannot render: {}",
                        path, templateId, losses);
            } else {
                log.debug("Rich text for field '{}' of template '{}' lost what Epistola cannot render: {}",
                        path, templateId, losses);
            }
        });
    }

    /** The template's fields, from the cache while fresh; null when the contract cannot be read. */
    private List<TemplateField> fields(
            String baseUrl, String apiKey, String tenantId, String catalogId, String templateId) {
        TemplateKey key = new TemplateKey(baseUrl, tenantId, catalogId, templateId);
        Instant now = clock.instant();
        CachedFields cached = fieldsByTemplate.get(key);
        if (cached != null && now.isBefore(cached.until())) {
            return cached.fields();
        }
        try {
            List<TemplateField> fields =
                    delegate.getTemplateDetails(baseUrl, apiKey, tenantId, catalogId, templateId).fields();
            if (fieldsByTemplate.size() >= MAX_CACHED_TEMPLATES) {
                fieldsByTemplate.clear();
            }
            fieldsByTemplate.put(key, new CachedFields(fields, now.plus(FIELDS_TTL)));
            return fields;
        } catch (RuntimeException e) {
            log.warn("Could not read template {} to convert its rich-text fields; sending the data as mapped ({})",
                    templateId, Failures.describe(e));
            return null;
        }
    }

    // --- Everything else is the client's ---------------------------------------------------------

    @Override
    public List<CatalogInfo> getCatalogs(String baseUrl, String apiKey, String tenantId) {
        return delegate.getCatalogs(baseUrl, apiKey, tenantId);
    }

    @Override
    public SystemInfo getSystemInfo(String baseUrl, String apiKey) {
        return delegate.getSystemInfo(baseUrl, apiKey);
    }

    @Override
    public List<TemplateInfo> getTemplates(String baseUrl, String apiKey, String tenantId, String catalogId) {
        return delegate.getTemplates(baseUrl, apiKey, tenantId, catalogId);
    }

    @Override
    public TemplateDetails getTemplateDetails(
            String baseUrl, String apiKey, String tenantId, String catalogId, String templateId) {
        return delegate.getTemplateDetails(baseUrl, apiKey, tenantId, catalogId, templateId);
    }

    @Override
    public List<AttributeDefinition> getAttributes(String baseUrl, String apiKey, String tenantId, String catalogId) {
        return delegate.getAttributes(baseUrl, apiKey, tenantId, catalogId);
    }

    @Override
    public List<EnvironmentInfo> getEnvironments(String baseUrl, String apiKey, String tenantId) {
        return delegate.getEnvironments(baseUrl, apiKey, tenantId);
    }

    @Override
    public List<VariantInfo> getVariants(
            String baseUrl, String apiKey, String tenantId, String catalogId, String templateId) {
        return delegate.getVariants(baseUrl, apiKey, tenantId, catalogId, templateId);
    }

    @Override
    public GenerationJobDetail getJobStatus(String baseUrl, String apiKey, String tenantId, String requestId) {
        return delegate.getJobStatus(baseUrl, apiKey, tenantId, requestId);
    }

    @Override
    public byte[] downloadDocument(String baseUrl, String apiKey, String tenantId, String documentId) {
        return delegate.downloadDocument(baseUrl, apiKey, tenantId, documentId);
    }

    @Override
    public ImportCatalogResult importCatalog(
            String baseUrl, String apiKey, String tenantId, byte[] zipBytes, String catalogType) {
        return delegate.importCatalog(baseUrl, apiKey, tenantId, zipBytes, catalogType);
    }
}
