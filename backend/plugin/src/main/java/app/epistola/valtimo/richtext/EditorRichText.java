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

import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.logging.Failures;
import app.epistola.valtimo.service.EpistolaService;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns what a person typed into one of the plugin's own editors — the letter composer and the
 * retry form — into the documents a template's rich-text fields take (ADR 0008).
 *
 * <p>Those editors are the plugin's, so the plugin knows what they write: the default format's
 * editor asked for the field, and its value is in that format. A data mapping is different. Its
 * author knows the case's format and says it, with {@code $richText(value, format)}, so mapped data
 * is never converted here.
 *
 * <p>Called where editor data leaves for Epistola: the composer's preview, the generation of a
 * composed letter, and the retry form's preview and generation. Which fields are rich text is only
 * known from the template's contract, so it is read first and kept for a short while, since the
 * composer previews on every change. When it cannot be read, the data goes as typed, and Epistola's
 * own validation refuses what it cannot render.
 */
@Slf4j
public class EditorRichText {

    /** Why the data is sent, which decides how loudly a loss is reported. */
    public enum Purpose {
        /** Repeated on every change in the composer: a loss is logged at debug. */
        PREVIEW,
        /** A letter: a loss is a warning, because the author's form offers more than it shows. */
        GENERATION
    }

    /** How long a template's fields are trusted: long enough to cover a session of previews. */
    static final Duration FIELDS_TTL = Duration.ofMinutes(1);
    private static final int MAX_CACHED_TEMPLATES = 512;

    private record TemplateKey(String baseUrl, String tenantId, String catalogId, String templateId) {
    }

    private record CachedFields(List<TemplateField> fields, Instant until) {
    }

    private final EpistolaService epistolaService;
    private final RichTextConverter converter;
    private final Clock clock;
    private final Map<TemplateKey, CachedFields> fieldsByTemplate = new ConcurrentHashMap<>();

    public EditorRichText(EpistolaService epistolaService, RichTextConverter converter) {
        this(epistolaService, converter, Clock.systemUTC());
    }

    EditorRichText(EpistolaService epistolaService, RichTextConverter converter, Clock clock) {
        this.epistolaService = epistolaService;
        this.converter = converter;
        this.clock = clock;
    }

    /**
     * The data with each rich-text field's value as a document: text in the editor's format is
     * converted, a document is narrowed to the schema, and an empty value is removed.
     *
     * @throws RichTextConversionException when the format fails on a value
     */
    public Map<String, Object> toDocuments(
            String baseUrl, String apiKey, String tenantId, String catalogId, String templateId,
            Map<String, Object> data, Purpose purpose
    ) {
        if (data == null || data.isEmpty()) {
            return data;
        }
        List<TemplateField> fields = fields(baseUrl, apiKey, tenantId, catalogId, templateId);
        if (fields == null) {
            return data;
        }
        // Field paths and kinds of content, never the text.
        return converter.normalize(data, fields, (path, losses) -> {
            if (purpose == Purpose.GENERATION) {
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
                    epistolaService.getTemplateDetails(baseUrl, apiKey, tenantId, catalogId, templateId).fields();
            if (fieldsByTemplate.size() >= MAX_CACHED_TEMPLATES) {
                fieldsByTemplate.clear();
            }
            fieldsByTemplate.put(key, new CachedFields(fields, now.plus(FIELDS_TTL)));
            return fields;
        } catch (RuntimeException e) {
            log.warn("Could not read template {} to convert its rich-text fields; sending the data as typed ({})",
                    templateId, Failures.describe(e));
            return null;
        }
    }
}
