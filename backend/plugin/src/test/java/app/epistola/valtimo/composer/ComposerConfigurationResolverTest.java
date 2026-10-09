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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.form.domain.FormIoFormDefinition;
import com.ritense.form.domain.FormProcessLink;
import com.ritense.form.repository.FormDefinitionRepository;
import com.ritense.processlink.domain.ActivityTypeWithEventName;
import com.ritense.processlink.domain.ProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import com.ritense.formflow.domain.FormFlowProcessLink;
import com.ritense.formflow.domain.definition.configuration.step.FormStepTypeProperties;
import com.ritense.valtimo.contract.case_.CaseDefinitionId;
import com.ritense.case_.service.ActiveCaseDefinitionService;
import com.ritense.formflow.service.FormFlowService;
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.repository.ProcessDefinitionQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The resolver is what makes the composer's endpoints safe: the configuration comes from the
 * stored form behind the caller's task, never from the request. These tests pin that contract —
 * a template the form does not offer is refused, and an incomplete composer is ignored rather
 * than half-used.
 */
class ComposerConfigurationResolverTest {

    private static final String PROCESS_DEFINITION_ID = "process:1:abc";
    private static final String ACTIVITY_ID = "choose-letter";
    private static final UUID FORM_ID = UUID.randomUUID();
    private static final UUID PLUGIN_CONFIGURATION_ID = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProcessLinkService processLinkService;
    private FormDefinitionRepository formDefinitionRepository;
    private RepositoryService repositoryService;
    private FormFlowService formFlowService;
    private ActiveCaseDefinitionService activeCaseDefinitionService;
    private ProcessDefinitionCaseDefinitionService processDefinitionCaseDefinitionService;
    private ProcessDefinitionQuery processDefinitionQuery;
    private ComposerConfigurationResolver resolver;

    @BeforeEach
    void setUp() {
        processLinkService = mock(ProcessLinkService.class);
        formDefinitionRepository = mock(FormDefinitionRepository.class);
        repositoryService = mock(RepositoryService.class);
        processDefinitionQuery = mock(ProcessDefinitionQuery.class, org.mockito.Mockito.RETURNS_SELF);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(processDefinitionQuery);
        when(processDefinitionQuery.list()).thenReturn(List.of());
        formFlowService = mock(FormFlowService.class);
        processDefinitionCaseDefinitionService = mock(ProcessDefinitionCaseDefinitionService.class);
        activeCaseDefinitionService = mock(ActiveCaseDefinitionService.class);
        resolver = new ComposerConfigurationResolver(
                processLinkService, formDefinitionRepository, formFlowService,
                activeCaseDefinitionService, processDefinitionCaseDefinitionService);
    }

    private static final String CASE_KEY = "correspondentie";

    /**
     * Say which processes a user may start on the case, which is what discovery now asks.
     *
     * <p>The mocks are built before {@code when(...)} is entered on purpose: constructing them
     * inside the argument would be stubbing within unfinished stubbing, which Mockito rejects.
     */
    private void startableOnTheCase(String... processDefinitionIds) {
        var caseDefinitionId = new CaseDefinitionId(CASE_KEY, "1.0.0");
        var caseDefinition = mock(com.ritense.case_.domain.definition.CaseDefinition.class);
        when(caseDefinition.getId()).thenReturn(caseDefinitionId);
        when(activeCaseDefinitionService.getActiveCaseDefinition(CASE_KEY)).thenReturn(caseDefinition);

        List<com.ritense.processdocument.domain.ProcessDefinitionCaseDefinition> links =
                new java.util.ArrayList<>();
        for (String id : processDefinitionIds) {
            var link = mock(com.ritense.processdocument.domain.ProcessDefinitionCaseDefinition.class);
            var linkId = mock(com.ritense.processdocument.domain.ProcessDefinitionCaseDefinitionId.class);
            when(linkId.getProcessDefinitionId())
                    .thenReturn(new com.ritense.processdocument.domain.ProcessDefinitionId(id));
            when(link.getId()).thenReturn(linkId);
            links.add(link);
        }
        when(processDefinitionCaseDefinitionService
                .findProcessDefinitionCaseDefinitions(caseDefinitionId, true, null))
                .thenReturn(links);
    }

    /** Give a definition a start form carrying {@code formJson}. */
    private void startFormOn(String processDefinitionId, UUID formId, String formJson) {
        FormProcessLink startLink = mock(FormProcessLink.class);
        when(startLink.getFormDefinitionId()).thenReturn(formId);
        when(startLink.getActivityType()).thenReturn(ActivityTypeWithEventName.START_EVENT_START);
        when(processLinkService.getProcessLinks(processDefinitionId))
                .thenReturn(List.<ProcessLink>of(startLink));

        FormIoFormDefinition form = mock(FormIoFormDefinition.class);
        try {
            when(form.getFormDefinition()).thenReturn(objectMapper.readTree(formJson));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(formDefinitionRepository.findById(formId)).thenReturn(Optional.of(form));
    }

    private void formOnTask(String formJson) {
        FormProcessLink link = mock(FormProcessLink.class);
        when(link.getFormDefinitionId()).thenReturn(FORM_ID);
        when(processLinkService.getProcessLinks(PROCESS_DEFINITION_ID, ACTIVITY_ID))
                .thenReturn(List.<ProcessLink>of(link));

        FormIoFormDefinition form = mock(FormIoFormDefinition.class);
        try {
            when(form.getFormDefinition()).thenReturn(objectMapper.readTree(formJson));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(formDefinitionRepository.findById(FORM_ID)).thenReturn(Optional.of(form));
    }

    /** A composer as the builder saves one: its settings under the one key this plugin owns. */
    private String composerJson(String extraProperties) {
        return """
                {"components":[
                  {"type":"panel","components":[
                    {"type":"epistola-letter-composer","key":"pv:epistolaLetter",
                     "epistola":{
                       "pluginConfigurationId":"%s","catalogId":"gemeente",
                       "dataMapping":"{\\"naam\\": $doc.naam}",
                       "templates":[
                         {"templateId":"besluit","label":"Besluit"},
                         {"templateId":"herinnering","dataMapping":"{\\"termijn\\": 14}"}
                       ]%s}}
                  ]}
                ]}
                """.formatted(PLUGIN_CONFIGURATION_ID, extraProperties);
    }

    @Test
    void ignoresAFormWithoutAComposer() {
        formOnTask("""
                {"components":[{"type":"textfield","key":"pv:motivatie"}]}
                """);

        assertThat(resolver.forActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID)).isEmpty();
    }

    @Test
    void ignoresAnActivityWithoutAFormLink() {
        when(processLinkService.getProcessLinks(PROCESS_DEFINITION_ID, ACTIVITY_ID))
                .thenReturn(List.of(mock(ProcessLink.class)));

        assertThat(resolver.forActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID)).isEmpty();
    }

    @Test
    void survivesAFormLinkPointingAtADeletedForm() {
        FormProcessLink link = mock(FormProcessLink.class);
        when(link.getFormDefinitionId()).thenReturn(FORM_ID);
        when(processLinkService.getProcessLinks(PROCESS_DEFINITION_ID, ACTIVITY_ID))
                .thenReturn(List.<ProcessLink>of(link));
        when(formDefinitionRepository.findById(any())).thenReturn(Optional.empty());

        assertThat(resolver.forActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID)).isEmpty();
    }

    @Test
    void requireOffering_returnsTheComposerOfferingThatTemplate() {
        formOnTask(composerJson(""));

        assertThat(resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, null, null, "besluit").catalogId())
                .isEqualTo("gemeente");
    }

    @Test
    void requireOffering_refusesATemplateTheFormDoesNotOffer() {
        formOnTask(composerJson(""));

        assertThatThrownBy(() ->
                resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, null, null, "geheime-brief"))
                .isInstanceOf(ComposerException.class)
                .hasMessageContaining("geheime-brief")
                .extracting(e -> ((ComposerException) e).getReason())
                .isEqualTo(ComposerException.Reason.TEMPLATE_NOT_OFFERED);
    }

    @Test
    void requireOffering_reportsAFormWithNoComposerSeparately() {
        formOnTask("""
                {"components":[{"type":"textfield","key":"pv:motivatie"}]}
                """);

        assertThatThrownBy(() -> resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, null, null, "besluit"))
                .isInstanceOf(ComposerException.class)
                .extracting(e -> ((ComposerException) e).getReason())
                .isEqualTo(ComposerException.Reason.NO_COMPOSER);
    }

    /**
     * Two composers answering to one name is refused rather than resolved by document order.
     *
     * <p>They would write the same process variable, so only one could survive a submit anyway,
     * while their mappings and write-back rules differ — taking the first would apply one
     * composer's rules to the other's letter. Reachable from a hand-written form, and from the
     * builder by naming one picker {@code brief} and another {@code pv:brief}: different keys to
     * Formio's own uniqueness check, the same key once the prefix is applied on save.
     */
    @Test
    void requireOffering_refusesTwoComposersAnsweringToOneName() {
        formOnTask("""
                {"components":[
                  {"type":"epistola-letter-composer","key":"pv:brief",
                   "epistola":{"pluginConfigurationId":"%s","catalogId":"gemeente",
                     "templates":[{"templateId":"besluit","label":"Besluit"}]}},
                  {"type":"epistola-letter-composer","key":"pv:brief",
                   "epistola":{"pluginConfigurationId":"%s","catalogId":"landelijk",
                     "templates":[{"templateId":"besluit","label":"Ander besluit"}]}}
                ]}
                """.formatted(PLUGIN_CONFIGURATION_ID, PLUGIN_CONFIGURATION_ID));

        assertThatThrownBy(() ->
                resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, "pv:brief", null, "besluit"))
                .isInstanceOf(ComposerException.class)
                .hasMessageContaining("its own name")
                .extracting(e -> ((ComposerException) e).getReason())
                .isEqualTo(ComposerException.Reason.AMBIGUOUS_COMPOSER);
    }

    /** One composer with that name is still found when another on the form has a different one. */
    @Test
    void requireOffering_picksTheNamedComposerWhenAnotherIsPresent() {
        formOnTask("""
                {"components":[
                  {"type":"epistola-letter-composer","key":"pv:brief",
                   "epistola":{"pluginConfigurationId":"%s","catalogId":"gemeente",
                     "templates":[{"templateId":"besluit","label":"Besluit"}]}},
                  {"type":"epistola-letter-composer","key":"pv:tweedeBrief",
                   "epistola":{"pluginConfigurationId":"%s","catalogId":"landelijk",
                     "templates":[{"templateId":"besluit","label":"Ander besluit"}]}}
                ]}
                """.formatted(PLUGIN_CONFIGURATION_ID, PLUGIN_CONFIGURATION_ID));

        assertThat(resolver
                .requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, "pv:tweedeBrief", null, "besluit")
                .catalogId())
                .isEqualTo("landelijk");
    }

    @Test
    void findsTheComposerOnAProcessStartForm() {
        // An ad-hoc letter is composed before any task exists, so the activity is discovered from
        // the definition rather than named by the caller.
        FormProcessLink startLink = mock(FormProcessLink.class);
        when(startLink.getFormDefinitionId()).thenReturn(FORM_ID);
        when(startLink.getActivityType()).thenReturn(ActivityTypeWithEventName.START_EVENT_START);
        FormProcessLink taskLink = mock(FormProcessLink.class);
        when(taskLink.getActivityType()).thenReturn(ActivityTypeWithEventName.USER_TASK_CREATE);
        when(processLinkService.getProcessLinks(PROCESS_DEFINITION_ID))
                .thenReturn(List.<ProcessLink>of(taskLink, startLink));

        FormIoFormDefinition form = mock(FormIoFormDefinition.class);
        try {
            when(form.getFormDefinition()).thenReturn(objectMapper.readTree(composerJson("")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(formDefinitionRepository.findById(FORM_ID)).thenReturn(Optional.of(form));

        assertThat(resolver.forStartEvent(PROCESS_DEFINITION_ID)).singleElement()
                .satisfies(configuration -> assertThat(configuration.offers("besluit")).isTrue());
        assertThat(resolver.requireStartOffering(PROCESS_DEFINITION_ID, null, null, "besluit").catalogId())
                .isEqualTo("gemeente");
    }

    @Test
    void refusesATemplateTheStartFormDoesNotOffer() {
        when(processLinkService.getProcessLinks(PROCESS_DEFINITION_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.requireStartOffering(PROCESS_DEFINITION_ID, null, null, "besluit"))
                .isInstanceOf(ComposerException.class)
                .extracting(e -> ((ComposerException) e).getReason())
                .isEqualTo(ComposerException.Reason.NO_COMPOSER);
    }

    @Test
    void refusesWhenTheNamedComposerIsNotOnThatForm() {
        // A start form cannot know which process it starts, so the author names it — and a name
        // pointing at another process must fail rather than compose with that form's composer.
        formOnTask(composerJson(""));

        assertThatThrownBy(() ->
                resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, "pv:andereBrief", null, "besluit"))
                .isInstanceOf(ComposerException.class)
                .hasMessageContaining("pv:andereBrief")
                .extracting(e -> ((ComposerException) e).getReason())
                .isEqualTo(ComposerException.Reason.NO_COMPOSER);
    }

    @Test
    void picksTheNamedComposerWhenAFormHasMoreThanOne() {
        formOnTask("""
                {"components":[
                  {"type":"epistola-letter-composer","key":"pv:eerste",
                   "pluginConfigurationId":"%s","catalogId":"gemeente",
                   "templates":[{"templateId":"besluit"}]},
                  {"type":"epistola-letter-composer","key":"pv:tweede",
                   "pluginConfigurationId":"%s","catalogId":"andere-catalogus",
                   "templates":[{"templateId":"besluit"}]}
                ]}
                """.formatted(PLUGIN_CONFIGURATION_ID, PLUGIN_CONFIGURATION_ID));

        assertThat(resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, "pv:tweede", null, "besluit")
                .catalogId()).isEqualTo("andere-catalogus");
    }

    @Test
    void discoversWhichProcessesHaveAStartFormOfferingATemplate() {
        // A start form belongs to exactly one process, so the author should not have to name it.
        // Valtimo tells a Form.io component nothing about the link that rendered it, so the
        // question is answered here instead.
        startableOnTheCase("ad-hoc:1:a", "other:1:b");
        startFormOn("ad-hoc:1:a", FORM_ID, composerJson(""));
        when(processLinkService.getProcessLinks("other:1:b")).thenReturn(List.of());

        assertThat(resolver.startEventDefinitionsOffering(CASE_KEY, "pv:epistolaLetter", null, "besluit"))
                .containsExactly("ad-hoc:1:a");
    }

    @Test
    void discoversNothingForATemplateNoStartFormOffers() {
        startableOnTheCase("ad-hoc:1:a");
        startFormOn("ad-hoc:1:a", FORM_ID, composerJson(""));

        assertThat(resolver.startEventDefinitionsOffering(CASE_KEY, "pv:epistolaLetter", null, "aanmaning")).isEmpty();
    }

    @Test
    void discoversNothingForAComposerKeyedDifferently() {
        // The component names itself, so a start form carrying someone else's composer is not a
        // match — otherwise an ad-hoc letter could quietly run another form's configuration.
        startableOnTheCase("ad-hoc:1:a");
        startFormOn("ad-hoc:1:a", FORM_ID, composerJson(""));

        assertThat(resolver.startEventDefinitionsOffering(CASE_KEY, "pv:andereBrief", null, "besluit")).isEmpty();
    }

    @Test
    void reportsEveryProcessWhoseStartFormOffersTheTemplate() {
        // Two matches is the case an author has to break: the endpoint refuses rather than
        // composing with whichever came first.
        UUID secondForm = UUID.randomUUID();
        startableOnTheCase("ad-hoc:1:a", "ad-hoc-2:1:b");
        startFormOn("ad-hoc:1:a", FORM_ID, composerJson(""));
        startFormOn("ad-hoc-2:1:b", secondForm, composerJson(""));

        assertThat(resolver.startEventDefinitionsOffering(CASE_KEY, "pv:epistolaLetter", null, "besluit"))
                .containsExactlyInAnyOrder("ad-hoc:1:a", "ad-hoc-2:1:b");
    }

    @Test
    void doesNotDiscoverAComposerThatOnlySitsOnATaskForm() {
        // Only START_EVENT_START links count. A composer on a user-task form has a task to
        // authorize against, and must not be reachable through the start-form endpoint.
        startableOnTheCase(PROCESS_DEFINITION_ID);
        FormProcessLink taskLink = mock(FormProcessLink.class);
        when(taskLink.getActivityType()).thenReturn(ActivityTypeWithEventName.USER_TASK_CREATE);
        when(processLinkService.getProcessLinks(PROCESS_DEFINITION_ID))
                .thenReturn(List.<ProcessLink>of(taskLink));

        assertThat(resolver.startEventDefinitionsOffering(CASE_KEY, "pv:epistolaLetter", null, "besluit")).isEmpty();
    }

    @Test
    void acceptsAComposerWhoseCatalogsAllLiveOnItsLetters() {
        // The forward-compatible case: no set-level catalog at all. Before, the whole composer was
        // skipped here, which read as "no letter composer on this form".
        formOnTask("""
                {"components":[
                  {"type":"epistola-letter-composer","key":"pv:epistolaLetter",
                   "pluginConfigurationId":"%s",
                   "templates":[
                     {"templateId":"besluit","catalogId":"gemeente"},
                     {"templateId":"aanmaning","catalogId":"landelijk"}
                   ]}
                ]}
                """.formatted(PLUGIN_CONFIGURATION_ID));

        var configuration = resolver.forActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID).get(0);

        assertThat(configuration.templates()).hasSize(2);
        assertThat(configuration.requireOne(null, "besluit").catalogId()).isEqualTo("gemeente");
    }

    @Test
    void skipsAComposerWhoseLettersHaveNoCatalogAtAll() {
        formOnTask("""
                {"components":[
                  {"type":"epistola-letter-composer","key":"pv:epistolaLetter",
                   "pluginConfigurationId":"%s","templates":[{"templateId":"besluit"}]}
                ]}
                """.formatted(PLUGIN_CONFIGURATION_ID));

        assertThat(resolver.forActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID)).isEmpty();
    }

    @Test
    void readsAComposerThatPredatesTheSchemaVersion() {
        // Every form deployed before the field existed is version 1; an upgrade must not
        // invalidate them.
        formOnTask(composerJson(""));

        assertThat(resolver.forActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID).get(0).schemaVersion())
                .isNull();
        assertThat(resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, null, null, "besluit"))
                .isNotNull();
    }

    @Test
    void refusesAComposerWrittenForALaterSchema() {
        formOnTask(composerJson(",\"schemaVersion\":99"));

        assertThatThrownBy(() ->
                resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, null, null, "besluit"))
                .isInstanceOf(ComposerException.class)
                .hasMessageContaining("99")
                .hasMessageContaining("Upgrade the Epistola plugin")
                .extracting(e -> ((ComposerException) e).getReason())
                .isEqualTo(ComposerException.Reason.UNSUPPORTED_SCHEMA);
    }

    @Test
    void stillReadsTheFormWhenAnotherComposerOnItIsTooNew() {
        // The version is checked where a composer is used, not where the form is read, so one
        // component from a newer plugin does not remove every composer on that form.
        formOnTask("""
                {"components":[
                  {"type":"epistola-letter-composer","key":"pv:nieuw","schemaVersion":99,
                   "pluginConfigurationId":"%s","catalogId":"gemeente",
                   "templates":[{"templateId":"besluit"}]},
                  {"type":"epistola-letter-composer","key":"pv:oud",
                   "pluginConfigurationId":"%s","catalogId":"gemeente",
                   "templates":[{"templateId":"besluit"}]}
                ]}
                """.formatted(PLUGIN_CONFIGURATION_ID, PLUGIN_CONFIGURATION_ID));

        assertThat(resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, "pv:oud", null, "besluit"))
                .isNotNull();
        assertThatThrownBy(() ->
                resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, "pv:nieuw", null, "besluit"))
                .isInstanceOf(ComposerException.class);
    }

    /** A template id is unique only within a catalog, so one composer can offer the same id twice. */
    private String twoCatalogsJson() {
        return """
                {"components":[
                  {"type":"epistola-letter-composer","key":"pv:epistolaLetter",
                   "pluginConfigurationId":"%s",
                   "templates":[
                     {"templateId":"besluit","catalogId":"gemeente","label":"Gemeentelijk besluit"},
                     {"templateId":"besluit","catalogId":"landelijk","label":"Landelijk besluit"}
                   ]}
                ]}
                """.formatted(PLUGIN_CONFIGURATION_ID);
    }

    @Test
    void refusesALetterOfferedByTwoCatalogsWhenTheRequestNamesNeither() {
        // Rendering whichever was configured first would be a coin toss between two different
        // letters, both of which the form legitimately offers.
        formOnTask(twoCatalogsJson());

        assertThatThrownBy(() ->
                resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, null, null, "besluit"))
                .isInstanceOf(ComposerException.class)
                .hasMessageContaining("more than one catalog")
                .hasMessageContaining("Name the catalog");
    }

    @Test
    void composesTheOneTheRequestNames() {
        formOnTask(twoCatalogsJson());

        var configuration =
                resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, null, "landelijk", "besluit");

        assertThat(configuration.matching("landelijk", "besluit")).singleElement()
                .satisfies(template -> assertThat(template.label()).isEqualTo("Landelijk besluit"));
    }

    @Test
    void refusesACatalogTheComposerDoesNotOfferThatLetterFrom() {
        formOnTask(twoCatalogsJson());

        assertThatThrownBy(() ->
                resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, null, "provinciaal", "besluit"))
                .isInstanceOf(ComposerException.class)
                .hasMessageContaining("in catalog 'provinciaal'")
                .extracting(e -> ((ComposerException) e).getReason())
                .isEqualTo(ComposerException.Reason.TEMPLATE_NOT_OFFERED);
    }

    @Test
    void namingACatalogIsOptionalWhenTheLetterIsUnambiguous() {
        formOnTask(composerJson(""));

        assertThat(resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, null, null, "besluit"))
                .isNotNull();
    }

    @Test
    void discoveryNarrowsByCatalogToo() {
        startableOnTheCase("ad-hoc:1:a");
        startFormOn("ad-hoc:1:a", FORM_ID, composerJson(""));

        assertThat(resolver.startEventDefinitionsOffering(CASE_KEY, "pv:epistolaLetter", "gemeente", "besluit"))
                .containsExactly("ad-hoc:1:a");
        assertThat(resolver.startEventDefinitionsOffering(CASE_KEY, "pv:epistolaLetter", "landelijk", "besluit"))
                .isEmpty();
    }

    /**
     * A form flow's steps store their form by name, so the composer on a step is reached through
     * the case definition the process belongs to rather than through a form link's id. The task is
     * unchanged by any of this — it still exists and still gates the request — so form flows need
     * no authorization of their own.
     */
    @Nested
    class OnAFormFlowStep {

        private final CaseDefinitionId caseDefinitionId = new CaseDefinitionId("correspondentie", "1.0.0");

        private void flowOnTask(String flowKey, java.util.Map<String, String> stepForms) {
            FormFlowProcessLink link = mock(FormFlowProcessLink.class);
            when(link.getFormFlowDefinitionKey()).thenReturn(flowKey);
            when(processLinkService.getProcessLinks(PROCESS_DEFINITION_ID, ACTIVITY_ID))
                    .thenReturn(List.<ProcessLink>of(link));

            var caseLink = mock(com.ritense.processdocument.domain.ProcessDefinitionCaseDefinition.class);
            var caseLinkId = mock(com.ritense.processdocument.domain.ProcessDefinitionCaseDefinitionId.class);
            when(caseLinkId.getCaseDefinitionId()).thenReturn(caseDefinitionId);
            when(caseLink.getId()).thenReturn(caseLinkId);
            when(processDefinitionCaseDefinitionService.findByProcessDefinitionId(any()))
                    .thenReturn(caseLink);

            var steps = new java.util.LinkedHashSet<com.ritense.formflow.domain.definition.FormFlowStep>();
            stepForms.forEach((stepKey, formName) -> {
                var step = mock(com.ritense.formflow.domain.definition.FormFlowStep.class);
                var stepId = mock(com.ritense.formflow.domain.definition.FormFlowStepId.class);
                when(stepId.getKey()).thenReturn(stepKey);
                when(step.getId()).thenReturn(stepId);
                when(step.getType()).thenReturn(
                        new com.ritense.formflow.domain.definition.configuration.FormFlowStepType(
                                "form", new FormStepTypeProperties(formName)));
                steps.add(step);
            });
            var definition = mock(com.ritense.formflow.domain.definition.FormFlowDefinition.class);
            when(definition.getSteps()).thenReturn(steps);
            when(formFlowService.findDefinitionOrNull(flowKey, caseDefinitionId)).thenReturn(definition);
        }

        private void formNamed(String name, String json) {
            FormIoFormDefinition form = mock(FormIoFormDefinition.class);
            try {
                when(form.getFormDefinition()).thenReturn(objectMapper.readTree(json));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            when(formDefinitionRepository.findByNameAndCaseDefinitionId(name, caseDefinitionId))
                    .thenReturn(Optional.of(form));
        }

        @Test
        void findsTheComposerOnAStepsForm() {
            flowOnTask("brief-flow", java.util.Map.of("kies", "kies-brief"));
            formNamed("kies-brief", composerJson(""));

            assertThat(resolver.forActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID)).singleElement()
                    .satisfies(configuration -> assertThat(configuration.offers("besluit")).isTrue());
        }

        @Test
        void readsEveryStepRatherThanOnlyTheOneOnScreen() {
            // All the steps belong to the one task, so a caller who may open it may reach any of
            // them anyway — and naming a step on the wire would be the browser saying where
            // configuration is read from, which is what this resolver exists to prevent.
            flowOnTask("brief-flow", new java.util.LinkedHashMap<>(java.util.Map.of(
                    "kies", "kies-brief", "controleer", "controleer-brief")));
            formNamed("kies-brief", composerJson(""));
            formNamed("controleer-brief", composerJson("").replace("pv:epistolaLetter", "pv:tweedeBrief"));

            assertThat(resolver.forActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID))
                    .extracting(LetterComposerConfiguration::componentKey)
                    .containsExactlyInAnyOrder("pv:epistolaLetter", "pv:tweedeBrief");
        }

        @Test
        void survivesAStepWhoseFormIsGone() {
            flowOnTask("brief-flow", java.util.Map.of("kies", "verdwenen"));

            assertThat(resolver.forActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID)).isEmpty();
        }

        @Test
        void survivesAProcessThatBelongsToNoCaseDefinition() {
            flowOnTask("brief-flow", java.util.Map.of("kies", "kies-brief"));
            when(processDefinitionCaseDefinitionService.findByProcessDefinitionId(any()))
                    .thenThrow(new IllegalStateException("no case definition"));

            assertThat(resolver.forActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID)).isEmpty();
        }

        @Test
        void fallsBackToAGloballyScopedForm() {
            // A form deployed outside a case definition is found by name alone.
            flowOnTask("brief-flow", java.util.Map.of("kies", "globaal"));
            FormIoFormDefinition form = mock(FormIoFormDefinition.class);
            try {
                when(form.getFormDefinition()).thenReturn(objectMapper.readTree(composerJson("")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            when(formDefinitionRepository.findByNameAndCaseDefinitionId("globaal", caseDefinitionId))
                    .thenReturn(Optional.empty());
            when(formDefinitionRepository.findByNameAndCaseDefinitionIdIsNull("globaal"))
                    .thenReturn(Optional.of(form));

            assertThat(resolver.forActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID)).hasSize(1);
        }
    }
}
