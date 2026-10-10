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

/**
 * Chooses the one composer a request is about, or refuses to.
 *
 * <p>Every refusal the composer endpoints can give for "which letter did you mean" lives here, and
 * none of it needs a database: a form's composers go in, one comes out or an exception explains
 * why not. Kept apart from {@link ComposerConfigurationResolver}, which is about finding the form
 * in the first place.
 */
final class ComposerSelection {

    private ComposerSelection() {
    }

    /**
     * Pick the composer that both is the one asking and offers the template.
     *
     * <p>Matching on the component's own key matters on a start form: a form cannot know which
     * process it starts (Valtimo hands a Form.io component only the components, never the process
     * link), so the author names it — and a name that points at another process must fail rather
     * than quietly compose with that process's composer.
     */
    static LetterComposerConfiguration require(
            List<LetterComposerConfiguration> configurations,
            String componentKey,
            String catalogId,
            String templateId,
            String where
    ) {
        boolean named = componentKey != null && !componentKey.isBlank();
        List<LetterComposerConfiguration> candidates = named
                ? configurations.stream()
                        .filter(configuration -> componentKey.equals(configuration.componentKey()))
                        .toList()
                : configurations;
        String location = named ? "'" + componentKey + "' on " + where : where;

        if (candidates.isEmpty()) {
            throw new ComposerException(ComposerException.Reason.NO_COMPOSER,
                    "No letter composer on " + location);
        }
        // Checked here rather than while reading the form: a component written by a newer plugin
        // must fail the request that uses it, not quietly remove every composer on that form.
        candidates.forEach(LetterComposerConfiguration::requireReadable);

        // Two composers answering to one name is not a choice this can make. They would write the
        // same process variable, so only one of them could survive a submit anyway, while their
        // mappings and write-back rules differ — taking the first would apply one composer's rules
        // to the other's letter, by document order. Reachable from a hand-written form, and from
        // the builder by naming one composer `brief` and another `pv:brief`, which are different
        // keys to Formio's own uniqueness check and the same key once stored.
        if (named && candidates.size() > 1) {
            throw new ComposerException(ComposerException.Reason.AMBIGUOUS_COMPOSER,
                    "More than one letter composer on " + location
                            + ". Give each letter picker on a form its own name.");
        }

        LetterComposerConfiguration offering = candidates.stream()
                .filter(configuration -> configuration.offers(catalogId, templateId))
                .findFirst()
                .orElseThrow(() -> new ComposerException(ComposerException.Reason.TEMPLATE_NOT_OFFERED,
                        "Template '" + templateId + "'"
                                + (catalogId != null ? " in catalog '" + catalogId + "'" : "")
                                + " is not offered by the letter composer on " + location));

        // A template id is unique only within a catalog, so a composer offering letters from two
        // of them can hold the same id twice. Rendering whichever was configured first would be a
        // coin toss between two different letters, so the caller is made to say which.
        if (offering.matching(catalogId, templateId).size() > 1) {
            throw new ComposerException(ComposerException.Reason.TEMPLATE_NOT_OFFERED,
                    "The letter composer on " + location + " offers template '" + templateId
                            + "' from more than one catalog. Name the catalog on the request.");
        }
        return offering;
    }
}
