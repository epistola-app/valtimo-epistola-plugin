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
package app.epistola.valtimo.service.suggestion;

import app.epistola.valtimo.service.EpistolaService;

import com.fasterxml.jackson.databind.JsonNode;
import com.ritense.document.domain.DocumentDefinition;
import com.ritense.document.service.DocumentDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Discovers available variables for JSONata autocompletion.
 * Returns paths grouped by source ($doc, $pv, $case).
 */
@Slf4j
@RequiredArgsConstructor
public class VariableSuggestionService {

    private final DocumentDefinitionService documentDefinitionService;
    private final ProcessVariableDiscoveryService processVariableDiscoveryService;

    /**
     * Get all available variable suggestions for a given case definition.
     *
     * @param caseDefinitionKey  the case definition key (used to find the document schema)
     * @param processDefinitionKey the process definition key (used to discover process variables)
     * @return grouped variable paths
     */
    public VariableSuggestions getSuggestions(String caseDefinitionKey, String processDefinitionKey) {
        List<String> docPaths = discoverDocumentFields(caseDefinitionKey);
        List<String> pvNames = discoverProcessVariables(processDefinitionKey);
        return new VariableSuggestions(docPaths, pvNames);
    }

    private List<String> discoverDocumentFields(String caseDefinitionKey) {
        if (caseDefinitionKey == null || caseDefinitionKey.isBlank()) {
            return List.of();
        }
        try {
            var definition = documentDefinitionService.findActiveByName(caseDefinitionKey);
            if (definition.isEmpty()) {
                log.debug("No document definition found for case '{}'", caseDefinitionKey);
                return List.of();
            }
            JsonNode schema = definition.get().schema();
            List<String> paths = new ArrayList<>();
            extractPaths(schema, "", paths);
            return paths;
        } catch (Exception e) {
            log.warn("Failed to discover document fields for '{}': {}", caseDefinitionKey, e.getMessage());
            return List.of();
        }
    }

    private List<String> discoverProcessVariables(String processDefinitionKey) {
        if (processDefinitionKey == null || processDefinitionKey.isBlank()) {
            return List.of();
        }
        try {
            return processVariableDiscoveryService.discoverVariables(processDefinitionKey);
        } catch (Exception e) {
            log.warn("Failed to discover process variables for '{}': {}", processDefinitionKey, e.getMessage());
            return List.of();
        }
    }

    /**
     * Recursively extract dot-notation paths from a JSON Schema.
     */
    private void extractPaths(JsonNode schema, String prefix, List<String> paths) {
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String path = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            JsonNode fieldSchema = field.getValue();
            String type = fieldSchema.has("type") ? fieldSchema.get("type").asText() : "";

            paths.add(path);

            // Recurse into nested objects
            if ("object".equals(type) && fieldSchema.has("properties")) {
                extractPaths(fieldSchema, path, paths);
            }

            // For arrays with object items, extract item paths
            if ("array".equals(type) && fieldSchema.has("items")) {
                JsonNode items = fieldSchema.get("items");
                if (items.has("properties")) {
                    extractPaths(items, path + "[]", paths);
                }
            }
        }
    }

    public record VariableSuggestions(
            List<String> doc,
            List<String> pv
    ) {}
}
