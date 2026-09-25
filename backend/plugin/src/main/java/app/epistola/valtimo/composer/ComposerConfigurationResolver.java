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
     * The composer on a process's start form that offers the given template.
     *
     * @throws ComposerException when the start form carries no composer, or none offering that template
     */
    public LetterComposerConfiguration requireStartOffering(String processDefinitionId, String templateId) {
        return requireOffering(forStartEvent(processDefinitionId), templateId,
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
            String templateId
    ) {
        return requireOffering(forActivity(processDefinitionId, activityId), templateId,
                "the form of activity '" + activityId + "'");
    }

    private LetterComposerConfiguration requireOffering(
            List<LetterComposerConfiguration> configurations,
            String templateId,
            String where
    ) {
        if (configurations.isEmpty()) {
            throw new ComposerException(ComposerException.Reason.NO_COMPOSER,
                    "No letter composer on " + where);
        }
        return configurations.stream()
                .filter(configuration -> configuration.offers(templateId))
                .findFirst()
                .orElseThrow(() -> new ComposerException(ComposerException.Reason.TEMPLATE_NOT_OFFERED,
                        "Template '" + templateId + "' is not offered by the letter composer on " + where));
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
        String catalogId = text(letterSet.path("catalogId"));
        String dataMapping = text(component.path("dataMapping"));

        List<LetterComposerConfiguration.OfferedTemplate> templates = new ArrayList<>();
        for (JsonNode template : letterSet.path("templates")) {
            String templateId = text(template.path("templateId"));
            if (templateId == null) {
                continue;
            }
            String label = text(template.path("label"));
            templates.add(new LetterComposerConfiguration.OfferedTemplate(
                    templateId,
                    label != null ? label : templateId,
                    text(template.path("dataMapping"))));
        }

        if (pluginConfigurationId == null || catalogId == null || templates.isEmpty()) {
            log.warn("Skipping letter composer '{}': it needs a plugin configuration, a catalog and "
                    + "at least one template (has configuration={}, catalog={}, templates={})",
                    text(component.path("key")), pluginConfigurationId, catalogId, templates.size());
            return null;
        }

        return new LetterComposerConfiguration(
                text(component.path("key")),
                pluginConfigurationId,
                catalogId,
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
