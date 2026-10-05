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

import app.epistola.valtimo.domain.DynamicDocument;
import app.epistola.valtimo.domain.EpistolaProcessVariables;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.plugin.domain.PluginProcessLink;
import lombok.RequiredArgsConstructor;
import org.operaton.bpm.engine.RuntimeService;

/**
 * The subject of an activity that is told what to render:
 * {@code epistola-generate-dynamic-document}.
 *
 * <p>The template and the data were decided before this activity ran and left on a process
 * variable — by a letter composer, or by any process that set it (see
 * {@link DynamicDocument#of}). So unlike a process link, there is no mapping to
 * evaluate: the document is read back exactly as it was going to be sent.
 *
 * <p>That difference matters for a retry. A configured activity re-evaluates its mapping, picking
 * up anything the case has learned since it failed. A dynamic document cannot: re-deriving it would
 * mean composing it again, which is what the employee is being spared. What failed is what comes
 * back, for them to correct.
 */
@RequiredArgsConstructor
public class DynamicDocumentGenerationSource implements GenerationSubjectSource {

    private final RuntimeService runtimeService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String pluginActionDefinitionKey) {
        return "epistola-generate-dynamic-document".equals(pluginActionDefinitionKey);
    }

    @Override
    public GenerationSubject resolve(PluginProcessLink link, String processInstanceId, String documentId) {
        String variableName = variableNameOf(link);
        Object raw = runtimeService.getVariable(processInstanceId, variableName);
        if (raw == null) {
            throw new GenerationSubjectException(GenerationSubjectException.Reason.NO_TEMPLATE,
                    "Process variable '" + variableName + "' holds no document for activity '"
                            + link.getActivityId() + "', so there is nothing to rebuild");
        }

        DynamicDocument document;
        try {
            document = DynamicDocument.from(raw, variableName, objectMapper);
        } catch (RuntimeException e) {
            throw new GenerationSubjectException(GenerationSubjectException.Reason.UNREADABLE_DOCUMENT,
                    "The document on '" + variableName + "' could not be read: " + e.getMessage(), e);
        }

        // The document as it was going to be sent, handed on whole rather than taken apart.
        return new GenerationSubject(document, link.getPluginConfigurationId().getId());
    }

    /** The variable the action was configured to read, defaulting as the action itself does. */
    private String variableNameOf(PluginProcessLink link) {
        String configured = link.getActionProperties().path("letterVariable").asText(null);
        return configured == null || configured.isBlank()
                ? EpistolaProcessVariables.COMPOSED_LETTER
                : configured;
    }
}
