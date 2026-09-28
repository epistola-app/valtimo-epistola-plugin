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

import java.util.List;
import java.util.UUID;

/**
 * The configuration of one {@code epistola-letter-composer} component, as it is stored in a
 * Valtimo form definition (ADR 0006: the form definition is the composer's storage — it is
 * versioned with the case definition, deployable as config-as-code, and editable in the builder).
 *
 * <p>The browser never sends this over the wire. It sends the task and the chosen template, and
 * the backend reads the configuration from the form definition behind that task, so a caller can
 * neither render a template the form does not offer nor smuggle in its own data mapping.
 *
 * @param componentKey  The component's Formio key, used in diagnostics
 * @param schemaVersion What the component declares it was written for, or null when it predates
 *                      the field; validated where the composer is used, not where it is read
 * @param pluginConfigurationId Which Epistola plugin configuration (tenant/credentials) to use
 * @param catalogId     The catalog a letter that names none of its own lives in; may be null when
 *                      every offered letter names one
 * @param dataMapping   The baseline JSONata mapping, applied for every offered template
 * @param templates     The templates this component offers, in display order
 * @param askOptionalFields Whether to also ask for optional template fields the mapping left empty
 */
public record LetterComposerConfiguration(
        String componentKey,
        Integer schemaVersion,
        UUID pluginConfigurationId,
        String catalogId,
        String dataMapping,
        List<OfferedTemplate> templates,
        boolean askOptionalFields
) {
    /**
     * One selectable letter.
     *
     * <p>The catalog is held per letter rather than per set: a catalog is a property of the letter,
     * and a picker offering letters from two catalogs is the obvious next step. A letter that names
     * none is resolved against the set's catalog when it is read, so this is always the catalog to
     * use — callers never have to fall back themselves.
     *
     * @param catalogId   The catalog this letter lives in, already resolved
     * @param templateId  The Epistola template id
     * @param label       What the employee sees in the picker; falls back to the template id
     * @param dataMapping An optional JSONata fragment merged over the baseline for this template
     */
    public record OfferedTemplate(String catalogId, String templateId, String label, String dataMapping) {
    }

    /** Whether this component offers the given template. */
    public boolean offers(String templateId) {
        return findTemplate(templateId) != null;
    }

    /**
     * Refuse a component this plugin may not understand.
     *
     * @throws ComposerException when it was written for a later schema than this plugin reads
     */
    public void requireReadable() {
        ComposerSchema.readable(schemaVersion, "Letter composer '" + componentKey + "'");
    }

    /**
     * The catalog the given letter lives in, or the set's own when it is not offered here.
     *
     * <p>For callers that hold a template id but not the offered letter. Never fall back to
     * {@link #catalogId()} directly: that is the set's default, not necessarily this letter's.
     */
    public String catalogFor(String templateId) {
        OfferedTemplate template = findTemplate(templateId);
        return template != null ? template.catalogId() : catalogId;
    }

    /** The offered template with this id, or null when the component does not offer it. */
    public OfferedTemplate findTemplate(String templateId) {
        if (templateId == null || templates == null) {
            return null;
        }
        return templates.stream()
                .filter(template -> templateId.equals(template.templateId()))
                .findFirst()
                .orElse(null);
    }
}
