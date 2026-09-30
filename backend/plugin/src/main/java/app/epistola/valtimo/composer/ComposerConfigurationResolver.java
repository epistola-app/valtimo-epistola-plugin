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

import com.ritense.form.domain.FormIoFormDefinition;
import com.ritense.form.domain.FormProcessLink;
import com.ritense.form.repository.FormDefinitionRepository;
import com.ritense.formflow.domain.FormFlowProcessLink;
import com.ritense.formflow.domain.definition.FormFlowDefinition;
import com.ritense.formflow.domain.definition.configuration.step.FormStepTypeProperties;
import com.ritense.case_.service.ActiveCaseDefinitionService;
import com.ritense.formflow.service.FormFlowService;
import com.ritense.processdocument.domain.ProcessDefinitionId;
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService;
import com.ritense.valtimo.contract.case_.CaseDefinitionId;
import com.ritense.processlink.domain.ActivityTypeWithEventName;
import com.ritense.processlink.service.ProcessLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds the letter-composer configuration behind a user task.
 *
 * <p>The chain is task → its form process link → the form definition → the
 * {@code epistola-letter-composer} components on it. Reading the configuration from the stored
 * form, rather than accepting it from the browser, is what makes the composer's endpoints safe:
 * the caller chooses <i>which</i> of the offered templates to render, never <i>what</i> gets
 * rendered or with which mapping.
 */
@Slf4j
@RequiredArgsConstructor
public class ComposerConfigurationResolver {

    private final ProcessLinkService processLinkService;
    private final FormDefinitionRepository formDefinitionRepository;
    private final FormFlowService formFlowService;
    private final ActiveCaseDefinitionService activeCaseDefinitionService;
    private final ProcessDefinitionCaseDefinitionService processDefinitionCaseDefinitionService;

    /**
     * Every composer configured on the form of this activity, in document order.
     * Empty when the activity has no form link, or a form without a composer.
     */
    public List<LetterComposerConfiguration> forActivity(String processDefinitionId, String activityId) {
        return forLinks(processDefinitionId, processLinkService.getProcessLinks(processDefinitionId, activityId));
    }

    /**
     * Every composer reachable through these links, whether the activity opens a form or a form
     * flow.
     *
     * <p>A form flow contributes the composers on <i>all</i> of its form steps, not only the one on
     * screen. They all belong to the same task, so a caller who may open the task may reach any of
     * them anyway — and the alternative, naming a step on the wire, would be something the browser
     * supplies about where configuration is read from, which is exactly what this resolver exists
     * to avoid.
     */
    private List<LetterComposerConfiguration> forLinks(String processDefinitionId, List<?> links) {
        List<LetterComposerConfiguration> configurations = new ArrayList<>();
        for (Object link : links) {
            if (link instanceof FormProcessLink form) {
                formDefinitionId(form.getFormDefinitionId(), configurations);
            } else if (link instanceof FormFlowProcessLink flow) {
                collectFromFormFlow(processDefinitionId, flow.getFormFlowDefinitionKey(), configurations);
            }
        }
        return configurations;
    }

    /**
     * The composers on a form flow's form steps.
     *
     * <p>A step stores its form by <i>name</i>, and a form name is only unique within a case
     * definition, so both the flow and its forms are looked up against the case definition the
     * process belongs to. Every API used here has been in Valtimo since 13.21, the plugin's floor.
     */
    private void collectFromFormFlow(
            String processDefinitionId,
            String formFlowDefinitionKey,
            List<LetterComposerConfiguration> into
    ) {
        CaseDefinitionId caseDefinitionId = caseDefinitionOf(processDefinitionId);
        if (caseDefinitionId == null) {
            log.warn("Cannot read the composers on form flow '{}': process definition {} belongs to no "
                    + "case definition", formFlowDefinitionKey, processDefinitionId);
            return;
        }

        FormFlowDefinition definition =
                formFlowService.findDefinitionOrNull(formFlowDefinitionKey, caseDefinitionId);
        if (definition == null) {
            log.warn("Form flow '{}' is linked to process definition {} but no longer exists",
                    formFlowDefinitionKey, processDefinitionId);
            return;
        }

        for (var step : definition.getSteps()) {
            if (!(step.getType().getProperties() instanceof FormStepTypeProperties form)) {
                continue;
            }
            formDefinitionRepository.findByNameAndCaseDefinitionId(form.getDefinition(), caseDefinitionId)
                    .or(() -> formDefinitionRepository.findByNameAndCaseDefinitionIdIsNull(form.getDefinition()))
                    .ifPresentOrElse(
                            definitionOnStep -> ComposerParser.collectComposers(
                                    definitionOnStep.getFormDefinition().path("components"), into),
                            () -> log.warn("Form flow '{}' step '{}' names form '{}', which does not exist",
                                    formFlowDefinitionKey, step.getId().getKey(), form.getDefinition()));
        }
    }

    /** The case definition a process belongs to, or null when it belongs to none. */
    private CaseDefinitionId caseDefinitionOf(String processDefinitionId) {
        try {
            var link = processDefinitionCaseDefinitionService
                    .findByProcessDefinitionId(new ProcessDefinitionId(processDefinitionId));
            return link == null ? null : link.getId().getCaseDefinitionId();
        } catch (RuntimeException e) {
            log.debug("No case definition for process definition {}: {}", processDefinitionId, e.getMessage());
            return null;
        }
    }

    /**
     * Every composer configured on the <b>start form</b> of a process definition.
     *
     * <p>The activity id is discovered here rather than sent by the browser: a start form is the
     * one place a caller has no task to name an activity from, and accepting one would let a
     * request point at another activity's link.
     */
    public List<LetterComposerConfiguration> forStartEvent(String processDefinitionId) {
        return forLinks(processDefinitionId, processLinkService.getProcessLinks(processDefinitionId).stream()
                .filter(link -> link.getActivityType() == ActivityTypeWithEventName.START_EVENT_START)
                .toList());
    }

    /**
     * Which deployed processes have a <b>start form</b> carrying this composer, offering this
     * template.
     *
     * <p>This is how a start-form composer finds its own process without the author naming one. A
     * start form is part of exactly one process, so the process is derivable — but not by the
     * browser: Valtimo hands a Form.io component its components and nothing about the link that
     * rendered it. So the question is answered here, from the stored links, and the caller is
     * authorized against whatever comes back.
     *
     * <p>Returning a list rather than one id is deliberate. Two processes may well offer the same
     * letter from a composer keyed the same way, and quietly composing with the first would mean
     * running someone else's configuration. The caller narrows the list to the processes this user
     * may actually start, and only a single survivor is used.
     *
     * @return the latest deployed definition ids, in no particular order
     */
    public List<String> startEventDefinitionsOffering(
            String caseDefinitionKey, String componentKey, String catalogId, String templateId) {
        List<String> matches = new ArrayList<>();
        for (String processDefinitionId : startableProcessesFor(caseDefinitionKey)) {
            boolean offered = forStartEvent(processDefinitionId).stream()
                    .filter(configuration -> componentKey == null || componentKey.isBlank()
                            || componentKey.equals(configuration.componentKey()))
                    .anyMatch(configuration -> configuration.offers(catalogId, templateId));
            if (offered) {
                matches.add(processDefinitionId);
            }
        }
        return matches;
    }

    /**
     * The processes a user can start on this case.
     *
     * <p>An ad-hoc letter starts a process <i>on the open dossier</i>, so those are the only
     * candidates there have ever been — an earlier version read every deployed definition's
     * process links to find out, which grew with the installation rather than with the case.
     *
     * <p>Returns nothing for a case that has none, and for a caller with no dossier at all: a
     * composer on the start form of a new case has no case to narrow by, and naming the process is
     * the answer there.
     */
    private List<String> startableProcessesFor(String caseDefinitionKey) {
        if (caseDefinitionKey == null || caseDefinitionKey.isBlank()) {
            return List.of();
        }
        try {
            var caseDefinition = activeCaseDefinitionService.getActiveCaseDefinition(caseDefinitionKey);
            return processDefinitionCaseDefinitionService
                    .findProcessDefinitionCaseDefinitions(caseDefinition.getId(), true, null).stream()
                    .map(link -> link.getId().getProcessDefinitionId().getId())
                    .toList();
        } catch (RuntimeException e) {
            log.debug("No startable processes for case definition '{}': {}",
                    caseDefinitionKey, e.getMessage());
            return List.of();
        }
    }

    /**
     * The composer on a process's start form that offers the given template.
     *
     * @throws ComposerException when the start form carries no composer, or none offering that template
     */
    public LetterComposerConfiguration requireStartOffering(
            String processDefinitionId,
            String componentKey,
            String catalogId,
            String templateId
    ) {
        return ComposerSelection.require(forStartEvent(processDefinitionId), componentKey, catalogId,
                templateId, "the start form of process definition '" + processDefinitionId + "'");
    }

    /**
     * The composer on this activity's form that offers the given template.
     *
     * @throws ComposerException when the form carries no composer, or none offering that template
     */
    public LetterComposerConfiguration requireOffering(
            String processDefinitionId,
            String activityId,
            String componentKey,
            String catalogId,
            String templateId
    ) {
        return ComposerSelection.require(forActivity(processDefinitionId, activityId), componentKey,
                catalogId, templateId, "the form of activity '" + activityId + "'");
    }


    /** Collect the composers on one form definition into {@code into}. */
    private void formDefinitionId(UUID formDefinitionId, List<LetterComposerConfiguration> into) {
        Optional<FormIoFormDefinition> form = formDefinitionRepository.findById(formDefinitionId);
        if (form.isEmpty()) {
            log.warn("Form definition {} is linked to a process but no longer exists", formDefinitionId);
            return;
        }
        ComposerParser.collectComposers(form.get().getFormDefinition().path("components"), into);
    }

}
