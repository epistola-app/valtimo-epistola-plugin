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

import com.fasterxml.jackson.databind.JsonNode;
import com.ritense.form.domain.FormIoFormDefinition;
import com.ritense.form.domain.FormProcessLink;
import com.ritense.form.repository.FormDefinitionRepository;
import com.ritense.processlink.domain.ActivityTypeWithEventName;
import com.ritense.processlink.service.ProcessLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.RepositoryService;

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

    /** The Formio component type a composer configuration lives on. */
    public static final String COMPOSER_COMPONENT_TYPE = "epistola-letter-composer";

    private final ProcessLinkService processLinkService;
    private final FormDefinitionRepository formDefinitionRepository;
    private final RepositoryService repositoryService;

    /**
     * Every composer configured on the form of this activity, in document order.
     * Empty when the activity has no form link, or a form without a composer.
     */
    public List<LetterComposerConfiguration> forActivity(String processDefinitionId, String activityId) {
        List<LetterComposerConfiguration> configurations = new ArrayList<>();
        for (UUID formDefinitionId : formDefinitionIds(processDefinitionId, activityId)) {
            formDefinitionId(formDefinitionId, configurations);
        }
        return configurations;
    }

    /**
     * Every composer configured on the <b>start form</b> of a process definition.
     *
     * <p>The activity id is discovered here rather than sent by the browser: a start form is the
     * one place a caller has no task to name an activity from, and accepting one would let a
     * request point at another activity's link.
     */
    public List<LetterComposerConfiguration> forStartEvent(String processDefinitionId) {
        List<LetterComposerConfiguration> configurations = new ArrayList<>();
        for (UUID formDefinitionId : startFormDefinitionIds(processDefinitionId)) {
            formDefinitionId(formDefinitionId, configurations);
        }
        return configurations;
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
            String componentKey, String catalogId, String templateId) {
        List<String> matches = new ArrayList<>();
        for (var definition : repositoryService.createProcessDefinitionQuery().latestVersion().list()) {
            boolean offered = forStartEvent(definition.getId()).stream()
                    .filter(configuration -> componentKey == null || componentKey.isBlank()
                            || componentKey.equals(configuration.componentKey()))
                    .anyMatch(configuration -> configuration.offers(catalogId, templateId));
            if (offered) {
                matches.add(definition.getId());
            }
        }
        return matches;
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
        return requireOffering(forStartEvent(processDefinitionId), componentKey, catalogId, templateId,
                "the start form of process definition '" + processDefinitionId + "'");
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
        return requireOffering(forActivity(processDefinitionId, activityId), componentKey, catalogId,
                templateId, "the form of activity '" + activityId + "'");
    }

    /**
     * Pick the composer that both is the one asking and offers the template.
     *
     * <p>Matching on the component's own key matters on a start form: a form cannot know which
     * process it starts (Valtimo hands a Form.io component only the components, never the process
     * link), so the author names it — and a name that points at another process must fail rather
     * than quietly compose with that process's composer.
     */
    private LetterComposerConfiguration requireOffering(
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

    private List<UUID> formDefinitionIds(String processDefinitionId, String activityId) {
        return processLinkService.getProcessLinks(processDefinitionId, activityId).stream()
                .filter(FormProcessLink.class::isInstance)
                .map(FormProcessLink.class::cast)
                .map(FormProcessLink::getFormDefinitionId)
                .toList();
    }

    private List<UUID> startFormDefinitionIds(String processDefinitionId) {
        return processLinkService.getProcessLinks(processDefinitionId).stream()
                .filter(FormProcessLink.class::isInstance)
                .map(FormProcessLink.class::cast)
                .filter(link -> link.getActivityType() == ActivityTypeWithEventName.START_EVENT_START)
                .map(FormProcessLink::getFormDefinitionId)
                .toList();
    }

    /** Collect the composers on one form definition into {@code into}. */
    private void formDefinitionId(UUID formDefinitionId, List<LetterComposerConfiguration> into) {
        Optional<FormIoFormDefinition> form = formDefinitionRepository.findById(formDefinitionId);
        if (form.isEmpty()) {
            log.warn("Form definition {} is linked to a process but no longer exists", formDefinitionId);
            return;
        }
        collectComposers(form.get().getFormDefinition().path("components"), into);
    }

    /**
     * Walk the component tree. Composers are found wherever an author put them — inside a panel,
     * a columns layout or a fieldset — mirroring how Valtimo itself walks a form's components.
     */
    private void collectComposers(JsonNode components, List<LetterComposerConfiguration> into) {
        if (components == null || !components.isArray()) {
            return;
        }
        for (JsonNode component : components) {
            if (COMPOSER_COMPONENT_TYPE.equals(component.path("type").asText())) {
                LetterComposerConfiguration configuration = parse(component);
                if (configuration != null) {
                    into.add(configuration);
                }
                // A composer's own children are plumbing (the prefilled task-id carrier), never
                // another composer, so there is nothing to descend into here.
                continue;
            }
            collectComposers(component.path("components"), into);
            for (JsonNode column : component.path("columns")) {
                collectComposers(column.path("components"), into);
            }
            for (JsonNode row : component.path("rows")) {
                for (JsonNode cell : row) {
                    collectComposers(cell.path("components"), into);
                }
            }
        }
    }

    private LetterComposerConfiguration parse(JsonNode component) {
        // The settings widget stores the "which letters, from where" half as one object; a form
        // written before it existed carries the same three keys at the component's own level.
        JsonNode letterSet = component.has("letterSet") ? component.path("letterSet") : component;

        UUID pluginConfigurationId = uuidOrNull(text(letterSet.path("pluginConfigurationId")));
        // The set's catalog is a default, not the answer: a letter may name its own, which is what
        // lets one picker offer letters from more than one catalog.
        String defaultCatalogId = text(letterSet.path("catalogId"));
        String dataMapping = text(component.path("dataMapping"));
        String componentKey = text(component.path("key"));

        List<LetterComposerConfiguration.OfferedTemplate> templates = new ArrayList<>();
        int withoutCatalog = 0;
        for (JsonNode template : letterSet.path("templates")) {
            String templateId = text(template.path("templateId"));
            if (templateId == null) {
                continue;
            }
            String catalogId = text(template.path("catalogId"));
            if (catalogId == null) {
                catalogId = defaultCatalogId;
            }
            if (catalogId == null) {
                // Dropped rather than guessed: which catalog a letter comes from decides what is
                // rendered, and there is nothing to fall back to.
                withoutCatalog++;
                continue;
            }
            String label = text(template.path("label"));
            templates.add(new LetterComposerConfiguration.OfferedTemplate(
                    catalogId,
                    templateId,
                    label != null ? label : templateId,
                    text(template.path("dataMapping"))));
        }

        if (withoutCatalog > 0) {
            log.warn("Letter composer '{}' offers {} letter(s) with no catalog: give the component a "
                    + "catalog, or name one on each letter", componentKey, withoutCatalog);
        }

        if (pluginConfigurationId == null || templates.isEmpty()) {
            log.warn("Skipping letter composer '{}': it needs a plugin configuration and at least one "
                    + "letter with a catalog (has configuration={}, usable letters={})",
                    componentKey, pluginConfigurationId, templates.size());
            return null;
        }

        return new LetterComposerConfiguration(
                componentKey,
                // Read structurally; refused at the point of use, so one composer written by a
                // newer plugin does not take the rest of the form down with it.
                component.has(ComposerSchema.FIELD)
                        ? component.path(ComposerSchema.FIELD).asInt(ComposerSchema.CURRENT)
                        : null,
                pluginConfigurationId,
                defaultCatalogId,
                dataMapping,
                List.copyOf(templates),
                component.path("askOptionalFields").asBoolean(false));
    }

    private String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    private UUID uuidOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            log.warn("Letter composer has an unusable plugin configuration id: {}", value);
            return null;
        }
    }
}
