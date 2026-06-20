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
package app.epistola.valtimo.service.preview;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;

/**
 * Resolves the raw {@code dataMapping} JSONata of a generate-document process link from its
 * {@code (processDefinitionKey, activityId)} pair, for the configurator-facing override builder.
 * <p>
 * The builder uses the returned expression to surface — informationally — which {@code $doc} /
 * {@code $pv} paths the template's mapping consumes (so the author sees what is worth overriding
 * during preview). Extraction of the referenced paths happens on the frontend; this service only
 * delivers the mapping string. It never throws: an unresolved definition, link, or mapping yields
 * an empty string, which the frontend treats as "nothing to suggest".
 */
@Slf4j
@RequiredArgsConstructor
public class ProcessLinkMappingService {

    private final RepositoryService repositoryService;
    private final ProcessLinkService processLinkService;

    /**
     * Resolve the {@code dataMapping} JSONata for the latest version of the given process
     * definition and activity.
     *
     * @param processDefinitionKey the process definition key
     * @param activityId           the BPMN activity id of the generate-document task
     * @return the raw {@code dataMapping} JSONata, or {@code ""} when it cannot be resolved
     */
    public String getDataMapping(String processDefinitionKey, String activityId) {
        if (processDefinitionKey == null || processDefinitionKey.isBlank()
                || activityId == null || activityId.isBlank()) {
            return "";
        }
        try {
            ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(processDefinitionKey)
                    .latestVersion()
                    .singleResult();
            if (definition == null) {
                log.debug("No process definition found for key '{}'", processDefinitionKey);
                return "";
            }

            PluginProcessLink link = processLinkService.getProcessLinks(definition.getId(), activityId).stream()
                    .filter(PluginProcessLink.class::isInstance)
                    .map(PluginProcessLink.class::cast)
                    .findFirst()
                    .orElse(null);
            if (link == null) {
                log.debug("No plugin process link found for {}/{}", processDefinitionKey, activityId);
                return "";
            }

            return extractDataMapping(link);
        } catch (Exception e) {
            log.warn("Failed to resolve dataMapping for {}/{}: {}",
                    processDefinitionKey, activityId, e.getMessage());
            return "";
        }
    }

    /**
     * Read the textual {@code dataMapping} from a plugin process link's action properties.
     * Legacy object-format mappings (pre-JSONata) are unsupported and yield {@code ""}.
     * Shared with {@link PreviewService}.
     */
    static String extractDataMapping(PluginProcessLink link) {
        ObjectNode actionProps = link.getActionProperties();
        if (!actionProps.has("dataMapping")) {
            return "";
        }
        var node = actionProps.get("dataMapping");
        if (node.isTextual()) {
            return node.asText("");
        }
        // Legacy: dataMapping stored as JSON object — not supported with JSONata
        log.warn("Process link {} has dataMapping in legacy object format. " +
                "Please redeploy process links to use JSONata string format.", link.getId());
        return "";
    }
}
