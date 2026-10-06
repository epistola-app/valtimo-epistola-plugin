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
package app.epistola.valtimo.service.generation;

import app.epistola.valtimo.composer.ComposerSchema;
import app.epistola.valtimo.domain.DynamicDocument;

import java.util.Map;
import java.util.UUID;

/**
 * What a generation activity was rendering: the template, and the data to render it with.
 *
 * <p>Three parts of the plugin need this same answer and each used to work it out for itself:
 *
 * <ul>
 *   <li>the generate action, when it submits;</li>
 *   <li>the retry form, when a generation failed and someone wants to correct it;</li>
 *   <li>the letter composer, when it asks an employee for what the mapping could not supply.</li>
 * </ul>
 *
 * <p>They differ only in <em>where the answer comes from</em> — a process link's own configuration,
 * or a document a composer (or any process) left on a variable. Naming that difference once is what
 * lets the retry form cover both without becoming two services; see ADR 0007.
 *
 * <p>It holds a {@link DynamicDocument} rather than repeating its fields: that record already is
 * "what to render", and two types with the same three fields would drift. What it adds is the one
 * thing a document cannot name — which Epistola connection to ask — because that belongs to the
 * process link, not to the document.
 *
 * @param document               what is being rendered
 * @param pluginConfigurationId  the Epistola connection to ask
 */
public record GenerationSubject(
        DynamicDocument document,
        UUID pluginConfigurationId
) {
    public GenerationSubject {
        if (document == null) {
            throw new IllegalArgumentException("A generation subject names a document");
        }
    }

    /** Built from parts, for a source that reads them from a process link rather than a document. */
    public static GenerationSubject of(
            String catalogId, String templateId, Map<String, Object> data, UUID pluginConfigurationId) {
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("A generation subject names a template");
        }
        return new GenerationSubject(
                new DynamicDocument(ComposerSchema.CURRENT, catalogId, templateId, data),
                pluginConfigurationId);
    }

    public String catalogId() {
        return document.catalogId();
    }

    public String templateId() {
        return document.templateId();
    }

    public Map<String, Object> data() {
        return document.data();
    }
}
