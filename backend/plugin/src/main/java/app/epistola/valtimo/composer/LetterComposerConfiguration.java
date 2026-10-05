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
import java.util.Map;
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
        boolean askOptionalFields,
        Map<String, String> writeBack
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

    /**
     * The destinations this composer is allowed to write to.
     *
     * <p>The authority for <em>where</em> a letter's values may land in the case. The values
     * themselves are computed in the browser — that is what makes the previewed letter the
     * generated one — so the keys on a submitted letter arrived from the browser too, and are only
     * honoured where they appear here. See
     * {@link ComposedLetter#writeBackLimitedTo(java.util.Set)}.
     */
    public java.util.Set<String> writeBackDestinations() {
        return writeBack == null ? java.util.Set.of() : java.util.Set.copyOf(writeBack.keySet());
    }

    /**
     * Refuse a component this plugin may not understand.
     *
     * @throws ComposerException when it was written for a later schema than this plugin reads
     */
    public void requireReadable() {
        ComposerSchema.readable(schemaVersion, "Letter composer '" + componentKey + "'");
    }

    /** Whether this component offers the given template, in any catalog. */
    public boolean offers(String templateId) {
        return offers(null, templateId);
    }

    /** Whether this component offers the given letter; a null catalog means any of them. */
    public boolean offers(String catalogId, String templateId) {
        return !matching(catalogId, templateId).isEmpty();
    }

    /**
     * The one offered letter with this id, narrowed by catalog when the caller names one.
     *
     * <p>Only safe to call once {@code ComposerConfigurationResolver.requireOffering} has accepted
     * the request, which is what guarantees exactly one match — it refuses both "not offered" and
     * "offered by two catalogs". Hence the exception rather than a null: reaching either branch
     * means the caller skipped that check.
     */
    public OfferedTemplate requireOne(String catalogId, String templateId) {
        List<OfferedTemplate> matches = matching(catalogId, templateId);
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Letter composer '" + componentKey + "' offers " + matches.size() + " letters for "
                            + "template '" + templateId + "'; requireOffering should have refused this");
        }
        return matches.get(0);
    }

    /**
     * Every offered letter with this id, narrowed to one catalog when the caller names one.
     *
     * <p>A template id is only unique <i>within</i> a catalog, so a component offering letters from
     * two of them can hold the same id twice. Returning a list rather than the first match is what
     * lets the caller refuse that rather than render whichever happened to be configured first.
     *
     * @param catalogId the catalog to narrow to, or null for any
     */
    public List<OfferedTemplate> matching(String catalogId, String templateId) {
        if (templateId == null || templates == null) {
            return List.of();
        }
        return templates.stream()
                .filter(template -> templateId.equals(template.templateId()))
                .filter(template -> catalogId == null || catalogId.equals(template.catalogId()))
                .toList();
    }
}
