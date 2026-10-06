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

import app.epistola.valtimo.action.generate.GenerateDocumentActionConfiguration;
import app.epistola.valtimo.action.generate.GenerateDocumentActionConfigurationRegistry;
import app.epistola.valtimo.mapping.EvaluationContext;
import app.epistola.valtimo.mapping.JsonataMappingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.document.domain.impl.JsonSchemaDocumentId;
import com.ritense.document.service.DocumentService;
import com.ritense.plugin.domain.PluginProcessLink;
import lombok.RequiredArgsConstructor;
import org.operaton.bpm.engine.RuntimeService;

import java.util.Map;
import java.util.UUID;

/**
 * The subject of an activity that carries its own configuration: {@code epistola-generate-document}.
 *
 * <p>The template, catalog and mapping are the author's, stored on the process link, and the data is
 * that mapping evaluated against this process instance now. Evaluating it again rather than reading
 * what was sent is deliberate for a retry: the case may have moved on since the failure, and the
 * point of retrying is to pick that up.
 */
@RequiredArgsConstructor
public class ProcessLinkGenerationSource implements GenerationSubjectSource {

    private final JsonataMappingService jsonataMappingService;
    private final RuntimeService runtimeService;
    private final DocumentService documentService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String pluginActionDefinitionKey) {
        return "epistola-generate-document".equals(pluginActionDefinitionKey);
    }

    @Override
    public GenerationSubject resolve(PluginProcessLink link, String processInstanceId, String documentId) {
        ObjectNode actionProperties = link.getActionProperties();

        // The parser refuses a configuration with no template, and it refuses it first — so this
        // catch, not an if afterwards. A link that was never finished is an ordinary thing to meet
        // here and should read as "that activity names no template", not as a parser error with a
        // version number in it.
        GenerateDocumentActionConfiguration actionConfig;
        try {
            actionConfig = GenerateDocumentActionConfigurationRegistry.parse(actionProperties);
        } catch (RuntimeException e) {
            throw new GenerationSubjectException(GenerationSubjectException.Reason.NO_TEMPLATE,
                    "No templateId found in process link action properties for activity '"
                            + link.getActivityId() + "'", e);
        }

        Map<String, Object> data;
        try {
            data = jsonataMappingService.evaluate(EvaluationContext.builder()
                    .expression(actionConfig.dataMapping())
                    .documentResolver(this::loadDocumentContent)
                    .processVariableResolver(key -> runtimeService.getVariable(processInstanceId, key))
                    .documentId(documentId)
                    .build());
        } catch (RuntimeException e) {
            throw new GenerationSubjectException(GenerationSubjectException.Reason.MAPPING_FAILED,
                    "The data mapping of activity '" + link.getActivityId()
                            + "' could not be evaluated: " + e.getMessage(), e);
        }

        return GenerationSubject.of(
                actionConfig.catalogId(), actionConfig.templateId(), data,
                link.getPluginConfigurationId().getId());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadDocumentContent(String id) {
        if (id == null || id.isBlank()) {
            return Map.of();
        }
        try {
            return documentService.findBy(JsonSchemaDocumentId.existingId(UUID.fromString(id)))
                    .map(found -> (Map<String, Object>) objectMapper.convertValue(
                            found.content().asJson(), Map.class))
                    .orElse(Map.of());
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
