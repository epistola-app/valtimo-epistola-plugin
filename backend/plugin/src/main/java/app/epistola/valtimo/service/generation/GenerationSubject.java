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
 * @param catalogId              the catalog the template lives in
 * @param templateId             the template being rendered
 * @param data                   the data it is rendered with, resolved for this process instance
 * @param pluginConfigurationId  the Epistola connection to ask, which belongs to the process link
 *                               rather than to the document — a document names no connection
 */
public record GenerationSubject(
        String catalogId,
        String templateId,
        Map<String, Object> data,
        UUID pluginConfigurationId
) {
    public GenerationSubject {
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("A generation subject names a template");
        }
        // Not Map.copyOf: it rejects a null value, and a field someone cleared is a null here. That
        // exact copy cost a whole letter its readability once already — see DynamicDocument.
        if (data == null) {
            data = Map.of();
        } else {
            Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
            data.forEach(snapshot::put);
            data = java.util.Collections.unmodifiableMap(snapshot);
        }
    }
}
