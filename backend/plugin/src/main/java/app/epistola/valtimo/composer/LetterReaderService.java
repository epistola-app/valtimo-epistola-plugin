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

import app.epistola.valtimo.domain.EpistolaProcessVariables;
import app.epistola.valtimo.logging.Failures;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;

/**
 * Which generate tasks read a composer's letter.
 *
 * <p>Answers a question; it does not render a verdict. The configurator shows the answer beside the
 * composer's key, so an author who renames the key watches the line go empty — at the moment the
 * mistake is made rather than hours later, when the only symptom is a letter that never arrived.
 *
 * <p><b>Why this is not a validation.</b> "No generate task reads this variable" is not reliably a
 * fault. Generating in another process is a supported design — see
 * <a href="../../../../../../../docs/adr/0006-letter-composer-configuration.md">ADR 0006</a> on a
 * letter process started with {@code startSupportingProcess} — and a call activity, a form flow
 * handing the letter on, or a consumer that is not this plugin all look identical from here. A check
 * that cries wolf costs more than the silence it would replace: an operator who learns to ignore the
 * admin page has lost the real warnings with it. So this reports what it can see and leaves the
 * conclusion to the person who knows the design.
 */
@Slf4j
@RequiredArgsConstructor
public class LetterReaderService {

    private final ComposerConfigurationResolver configurationResolver;
    private final ProcessLinkService processLinkService;
    private final RepositoryService repositoryService;

    /**
     * The generate tasks on this case type that read {@code letterVariable}, in process order.
     *
     * <p>Takes the composer's key with or without its {@code pv:} prefix, because that is how an
     * author writes it. An empty list means none were found on this case type, which is not the same
     * as none existing — see the class note.
     */
    public List<LetterReader> readersOf(String caseDefinitionKey, String letterVariable) {
        String variable = withoutPrefix(letterVariable);
        if (variable == null || caseDefinitionKey == null || caseDefinitionKey.isBlank()) {
            return List.of();
        }

        List<LetterReader> readers = new ArrayList<>();
        for (String processDefinitionId : configurationResolver.processesOf(caseDefinitionKey)) {
            for (PluginProcessLink link : generatingLinks(processDefinitionId)) {
                if (!variable.equals(letterVariableOf(link))) {
                    continue;
                }
                ProcessDefinition definition = processDefinition(processDefinitionId);
                readers.add(new LetterReader(
                        definition != null ? definition.getKey() : processDefinitionId,
                        definition != null && definition.getName() != null
                                ? definition.getName()
                                : processDefinitionId,
                        link.getActivityId()));
            }
        }
        return readers;
    }

    /** The generate-document plugin links of one process definition. */
    private List<PluginProcessLink> generatingLinks(String processDefinitionId) {
        try {
            return processLinkService.getProcessLinks(processDefinitionId).stream()
                    .filter(PluginProcessLink.class::isInstance)
                    .map(PluginProcessLink.class::cast)
                    .filter(link -> EpistolaProcessVariables.GENERATING_ACTION_KEYS
                            .contains(link.getPluginActionDefinitionKey()))
                    .toList();
        } catch (RuntimeException e) {
            log.debug("No readable process links for {} while listing letter readers ({})",
                    processDefinitionId, Failures.describe(e));
            return List.of();
        }
    }

    /**
     * The variable this generate task was configured to read.
     *
     * <p>Defaults exactly as the action does, because an author who left the field empty is still
     * reading {@code epistolaLetter} and expects to see it here.
     */
    private static String letterVariableOf(PluginProcessLink link) {
        String configured = link.getActionProperties() == null
                ? null
                : link.getActionProperties().path("letterVariable").asText(null);
        return configured == null || configured.isBlank()
                ? EpistolaProcessVariables.COMPOSED_LETTER
                : configured;
    }

    private static String withoutPrefix(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String name = key.startsWith("pv:") ? key.substring("pv:".length()) : key;
        return name.isBlank() ? null : name;
    }

    private ProcessDefinition processDefinition(String processDefinitionId) {
        try {
            return repositoryService.getProcessDefinition(processDefinitionId);
        } catch (RuntimeException e) {
            log.debug("No readable process definition {} while listing letter readers ({})",
                    processDefinitionId, Failures.describe(e));
            return null;
        }
    }

    /** One generate task, named the way an author sees it in the modeller. */
    public record LetterReader(String processDefinitionKey, String processDefinitionName, String activityId) {}
}
