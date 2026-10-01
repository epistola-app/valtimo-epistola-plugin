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
package app.epistola.valtimo.composer;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads letter composers out of a form definition's JSON.
 *
 * <p>Split from {@link ComposerConfigurationResolver} because it shares none of its dependencies:
 * finding which form applies needs four Valtimo services, while reading composers out of one needs
 * nothing at all. The rules here — where a composer may sit, which shapes of settings are
 * understood, what makes one unusable — are the part worth testing, and testing them through four
 * mocks was only ever incidental.
 */
@Slf4j
public final class ComposerParser {

    /** The Formio component type a composer configuration lives on. */
    public static final String COMPOSER_COMPONENT_TYPE = "epistola-letter-composer";

    private ComposerParser() {
    }

    /** Every composer on a form, in document order. */
    public static List<LetterComposerConfiguration> composersOn(JsonNode components) {
        List<LetterComposerConfiguration> found = new ArrayList<>();
        collectComposers(components, found);
        return found;
    }

    /**
     * Walk the component tree. Composers are found wherever an author put them — inside a panel,
     * a columns layout or a fieldset — mirroring how Valtimo itself walks a form's components.
     */
    static void collectComposers(JsonNode components, List<LetterComposerConfiguration> into) {
        if (components == null || !components.isArray()) {
            return;
        }
        for (JsonNode component : components) {
            if (COMPOSER_COMPONENT_TYPE.equals(component.path("type").asText())) {
                LetterComposerConfiguration configuration = parse(component);
                if (configuration != null) {
                    into.add(configuration);
                }
                // A composer's own children are plumbing (the prefilled task-id carrier), never
                // another composer, so there is nothing to descend into here.
                continue;
            }
            collectComposers(component.path("components"), into);
            for (JsonNode column : component.path("columns")) {
                collectComposers(column.path("components"), into);
            }
            for (JsonNode row : component.path("rows")) {
                for (JsonNode cell : row) {
                    collectComposers(cell.path("components"), into);
                }
            }
        }
    }

    private static LetterComposerConfiguration parse(JsonNode component) {
        // The settings widget stores the "which letters, from where" half as one object; a form
        // written before it existed carries the same three keys at the component's own level.
        JsonNode letterSet = component.has("letterSet") ? component.path("letterSet") : component;

        UUID pluginConfigurationId = uuidOrNull(text(letterSet.path("pluginConfigurationId")));
        // The set's catalog is a default, not the answer: a letter may name its own, which is what
        // lets one picker offer letters from more than one catalog.
        String defaultCatalogId = text(letterSet.path("catalogId"));
        String dataMapping = text(component.path("dataMapping"));
        String componentKey = text(component.path("key"));
        Map<String, String> writeBack = writeBackOn(component, componentKey);

        List<LetterComposerConfiguration.OfferedTemplate> templates = new ArrayList<>();
        int withoutCatalog = 0;
        for (JsonNode template : letterSet.path("templates")) {
            String templateId = text(template.path("templateId"));
            if (templateId == null) {
                continue;
            }
            String catalogId = text(template.path("catalogId"));
            if (catalogId == null) {
                catalogId = defaultCatalogId;
            }
            if (catalogId == null) {
                // Dropped rather than guessed: which catalog a letter comes from decides what is
                // rendered, and there is nothing to fall back to.
                withoutCatalog++;
                continue;
            }
            String label = text(template.path("label"));
            templates.add(new LetterComposerConfiguration.OfferedTemplate(
                    catalogId,
                    templateId,
                    label != null ? label : templateId,
                    text(template.path("dataMapping"))));
        }

        if (withoutCatalog > 0) {
            log.warn("Letter composer '{}' offers {} letter(s) with no catalog: give the component a "
                    + "catalog, or name one on each letter", componentKey, withoutCatalog);
        }

        if (pluginConfigurationId == null || templates.isEmpty()) {
            log.warn("Skipping letter composer '{}': it needs a plugin configuration and at least one "
                    + "letter with a catalog (has configuration={}, usable letters={})",
                    componentKey, pluginConfigurationId, templates.size());
            return null;
        }

        return new LetterComposerConfiguration(
                componentKey,
                // Read structurally; refused at the point of use, so one composer written by a
                // newer plugin does not take the rest of the form down with it.
                component.has(ComposerSchema.FIELD)
                        ? component.path(ComposerSchema.FIELD).asInt(ComposerSchema.CURRENT)
                        : null,
                pluginConfigurationId,
                defaultCatalogId,
                dataMapping,
                List.copyOf(templates),
                component.path("askOptionalFields").asBoolean(false),
                writeBack);
    }

    /**
     * Where this composer declares that a letter's values also belong in the case: a value-resolver
     * key to a JSONata expression over the composed letter.
     *
     * <p>Read here, from the stored form definition, because this map is the authority on
     * <em>where</em> data may go. The expressions are evaluated in the browser, over the letter it
     * assembled, so the destinations on a submitted letter are browser-supplied and are checked
     * against these before anything is written.
     *
     * <p>An entry with no destination or no expression is dropped with a warning rather than
     * guessed at: a half-written rule is an authoring mistake, and writing to the wrong place in a
     * case is not worth recovering from silently.
     */
    private static Map<String, String> writeBackOn(JsonNode component, String componentKey) {
        JsonNode declared = component.path("writeBack");
        if (!declared.isObject() || declared.isEmpty()) {
            return Map.of();
        }
        Map<String, String> writeBack = new java.util.LinkedHashMap<>();
        int dropped = 0;
        var fields = declared.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String destination = entry.getKey() == null ? null : entry.getKey().trim();
            String expression = text(entry.getValue());
            if (destination == null || destination.isEmpty() || expression == null) {
                dropped++;
                continue;
            }
            writeBack.put(destination, expression);
        }
        if (dropped > 0) {
            log.warn("Letter composer '{}' declares {} write-back rule(s) with no destination or no "
                    + "expression; they are ignored", componentKey, dropped);
        }
        return Map.copyOf(writeBack);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    private static UUID uuidOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            log.warn("Letter composer has an unusable plugin configuration id: {}", value);
            return null;
        }
    }
}
