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
package app.epistola.valtimo.service.composer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.form.domain.FormIoFormDefinition;
import com.ritense.form.domain.FormProcessLink;
import com.ritense.form.repository.FormDefinitionRepository;
import com.ritense.processlink.domain.ActivityTypeWithEventName;
import com.ritense.processlink.domain.ProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import org.junit.jupiter.api.BeforeEach;
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
    private ComposerConfigurationResolver resolver;

    @BeforeEach
    void setUp() {
        processLinkService = mock(ProcessLinkService.class);
        formDefinitionRepository = mock(FormDefinitionRepository.class);
        resolver = new ComposerConfigurationResolver(processLinkService, formDefinitionRepository);
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

    private String composerJson(String extraProperties) {
        return """
                {"components":[
                  {"type":"panel","components":[
                    {"type":"epistola-letter-composer","key":"pv:epistolaLetter",
                     "pluginConfigurationId":"%s","catalogId":"gemeente",
                     "dataMapping":"{\\"naam\\": $doc.naam}",
                     "templates":[
                       {"templateId":"besluit","label":"Besluit"},
                       {"templateId":"herinnering","dataMapping":"{\\"termijn\\": 14}"}
                     ]%s}
                  ]}
                ]}
                """.formatted(PLUGIN_CONFIGURATION_ID, extraProperties);
    }

    @Test
    void findsAComposerNestedInsideALayoutComponent() {
        formOnTask(composerJson(""));

        List<LetterComposerConfiguration> configurations =
                resolver.forActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID);

        assertThat(configurations).hasSize(1);
        LetterComposerConfiguration configuration = configurations.get(0);
        assertThat(configuration.componentKey()).isEqualTo("pv:epistolaLetter");
        assertThat(configuration.pluginConfigurationId()).isEqualTo(PLUGIN_CONFIGURATION_ID);
        assertThat(configuration.catalogId()).isEqualTo("gemeente");
        assertThat(configuration.dataMapping()).isEqualTo("{\"naam\": $doc.naam}");
        assertThat(configuration.templates()).hasSize(2);
        assertThat(configuration.askOptionalFields()).isFalse();
    }

    @Test
    void labelsATemplateWithItsIdWhenNoneWasConfigured() {
        formOnTask(composerJson(""));

        var template = resolver.forActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID).get(0)
                .findTemplate("herinnering");

        assertThat(template.label()).isEqualTo("herinnering");
        assertThat(template.dataMapping()).isEqualTo("{\"termijn\": 14}");
    }

    @Test
    void readsTheOptionalFieldsSetting() {
        formOnTask(composerJson(",\"askOptionalFields\":true"));

        assertThat(resolver.forActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID).get(0).askOptionalFields())
                .isTrue();
    }

    @Test
    void ignoresAComposerWithoutAPluginConfigurationCatalogOrTemplates() {
        formOnTask("""
                {"components":[
                  {"type":"epistola-letter-composer","key":"half-configured","catalogId":"gemeente"}
                ]}
                """);

        assertThat(resolver.forActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID)).isEmpty();
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

        assertThat(resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, "besluit").catalogId())
                .isEqualTo("gemeente");
    }

    @Test
    void requireOffering_refusesATemplateTheFormDoesNotOffer() {
        formOnTask(composerJson(""));

        assertThatThrownBy(() ->
                resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, "geheime-brief"))
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

        assertThatThrownBy(() -> resolver.requireOffering(PROCESS_DEFINITION_ID, ACTIVITY_ID, "besluit"))
                .isInstanceOf(ComposerException.class)
                .extracting(e -> ((ComposerException) e).getReason())
                .isEqualTo(ComposerException.Reason.NO_COMPOSER);
    }

    @Test
    void readsTheSettingsWidgetsNestedLetterSet() {
        formOnTask("""
                {"components":[
                  {"type":"epistola-letter-composer","key":"pv:epistolaLetter",
                   "dataMapping":"{\\"naam\\": $doc.naam}",
                   "letterSet":{
                     "pluginConfigurationId":"%s","catalogId":"gemeente",
                     "templates":[{"templateId":"besluit","label":"Besluit"}]
                   }}
                ]}
                """.formatted(PLUGIN_CONFIGURATION_ID));

        List<LetterComposerConfiguration> configurations =
                resolver.forActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID);

        assertThat(configurations).singleElement().satisfies(configuration -> {
            assertThat(configuration.pluginConfigurationId()).isEqualTo(PLUGIN_CONFIGURATION_ID);
            assertThat(configuration.catalogId()).isEqualTo("gemeente");
            assertThat(configuration.dataMapping()).isEqualTo("{\"naam\": $doc.naam}");
            assertThat(configuration.offers("besluit")).isTrue();
        });
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
        assertThat(resolver.requireStartOffering(PROCESS_DEFINITION_ID, "besluit").catalogId())
                .isEqualTo("gemeente");
    }

    @Test
    void refusesATemplateTheStartFormDoesNotOffer() {
        when(processLinkService.getProcessLinks(PROCESS_DEFINITION_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.requireStartOffering(PROCESS_DEFINITION_ID, "besluit"))
                .isInstanceOf(ComposerException.class)
                .extracting(e -> ((ComposerException) e).getReason())
                .isEqualTo(ComposerException.Reason.NO_COMPOSER);
    }
}
