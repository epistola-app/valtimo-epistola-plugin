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

import app.epistola.valtimo.domain.SimpleMappingSupport;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateField.FieldType;
import app.epistola.valtimo.mapping.EvaluationContext;
import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.composer.LetterComposerService.ComposerContext;
import app.epistola.valtimo.composer.LetterComposerService.PreparedLetter;
import app.epistola.valtimo.service.form.FormioFormGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.operaton.bpm.engine.RuntimeService;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The composer's promise is that the employee is asked for what the case cannot supply, and for
 * nothing else. That cannot be read from the mapping (it may be opaque or computed), so it is read
 * from what the mapping produced — these tests pin exactly that behaviour, plus the per-template
 * fragment layering and the refusal of a template the form does not offer.
 */
class LetterComposerServiceTest {

    private static final UUID PLUGIN_CONFIGURATION_ID = UUID.randomUUID();
    private static final ComposerContext CONTEXT =
            new ComposerContext("process:1:abc", "choose-letter", "pi-1", "doc-1", "pv:epistolaLetter");

    private ComposerConfigurationResolver configurationResolver;
    private EpistolaService epistolaService;
    private JsonataMappingService jsonataMappingService;
    private LetterComposerService service;

    @BeforeEach
    void setUp() {
        configurationResolver = mock(ComposerConfigurationResolver.class);
        epistolaService = mock(EpistolaService.class);
        jsonataMappingService = mock(JsonataMappingService.class);
        PluginService pluginService = mock(PluginService.class);

        EpistolaPlugin plugin = mock(EpistolaPlugin.class);
        when(plugin.getBaseUrl()).thenReturn("http://epistola");
        when(plugin.getApiKey()).thenReturn("key");
        when(plugin.getTenantId()).thenReturn("gemeente");
        when(plugin.getDefaultEnvironmentId()).thenReturn("prod");
        when(pluginService.createInstance(any(UUID.class))).thenReturn(plugin);

        service = new LetterComposerService(
                configurationResolver,
                pluginService,
                epistolaService,
                jsonataMappingService,
                new FormioFormGenerator(new ObjectMapper()),
                mock(com.ritense.document.service.DocumentService.class),
                mock(RuntimeService.class),
                new ObjectMapper());
    }

    private LetterComposerConfiguration configuration(
            LetterComposerConfiguration.OfferedTemplate... templates
    ) {
        return new LetterComposerConfiguration(
                "pv:epistolaLetter",
                PLUGIN_CONFIGURATION_ID,
                "gemeente",
                "{\"naam\": $doc.naam}",
                List.of(templates),
                false);
    }

    private void offering(LetterComposerConfiguration configuration, String templateId) {
        when(configurationResolver.requireOffering(
                CONTEXT.processDefinitionId(), CONTEXT.activityId(), CONTEXT.componentKey(), templateId))
                .thenReturn(configuration);
    }

    private void templateRequires(String templateId, TemplateField... fields) {
        when(epistolaService.getTemplateDetails(anyString(), anyString(), anyString(), eq("gemeente"), eq(templateId)))
                .thenReturn(new TemplateDetails(templateId, templateId, List.of(fields), null,
                        SimpleMappingSupport.full()));
    }

    private TemplateField required(String name) {
        return new TemplateField(name, name, "string", FieldType.SCALAR, true, null, List.of());
    }

    @Test
    void asksOnlyForTheFieldsTheMappingCouldNotFill() {
        offering(configuration(new LetterComposerConfiguration.OfferedTemplate("besluit", "Besluit", null)), "besluit");
        when(jsonataMappingService.evaluate(any())).thenReturn(Map.of("naam", "Jansen"));
        templateRequires("besluit", required("naam"), required("motivatie"));

        PreparedLetter letter = service.prepare(CONTEXT, "besluit");

        var components = letter.form().get("components");
        assertThat(components).hasSize(1);
        assertThat(components.get(0).get("key").asText()).isEqualTo("motivatie");
        assertThat(letter.data()).containsEntry("naam", "Jansen");
        assertThat(letter.label()).isEqualTo("Besluit");
        assertThat(letter.complete()).isFalse();
    }

    @Test
    void reportsALetterThatNeedsNoInputAtAll() {
        offering(configuration(new LetterComposerConfiguration.OfferedTemplate("besluit", "Besluit", null)), "besluit");
        when(jsonataMappingService.evaluate(any())).thenReturn(Map.of("naam", "Jansen"));
        templateRequires("besluit", required("naam"));

        PreparedLetter letter = service.prepare(CONTEXT, "besluit");

        assertThat(letter.complete()).isTrue();
        assertThat(letter.form().get("components")).isEmpty();
    }

    @Test
    void layersTheTemplateFragmentOverTheBaselineMapping() {
        offering(configuration(new LetterComposerConfiguration.OfferedTemplate(
                "herinnering", "Herinnering", "{\"termijn\": 14}")), "herinnering");
        when(jsonataMappingService.evaluate(any()))
                .thenReturn(Map.of("naam", "Jansen", "termijn", 30))
                .thenReturn(Map.of("termijn", 14));
        templateRequires("herinnering", required("naam"), required("termijn"));

        PreparedLetter letter = service.prepare(CONTEXT, "herinnering");

        assertThat(letter.data()).containsEntry("naam", "Jansen").containsEntry("termijn", 14);
        assertThat(letter.complete()).isTrue();
    }

    @Test
    void evaluatesTheMappingAgainstTheCaseTheTaskBelongsTo() {
        offering(configuration(new LetterComposerConfiguration.OfferedTemplate("besluit", "Besluit", null)), "besluit");
        when(jsonataMappingService.evaluate(any())).thenReturn(Map.of());
        templateRequires("besluit");

        service.prepare(CONTEXT, "besluit");

        ArgumentCaptor<EvaluationContext> captor = ArgumentCaptor.forClass(EvaluationContext.class);
        org.mockito.Mockito.verify(jsonataMappingService).evaluate(captor.capture());
        assertThat(captor.getValue().getDocumentId()).isEqualTo("doc-1");
        assertThat(captor.getValue().getProcessInstanceId()).isEqualTo("pi-1");
        assertThat(captor.getValue().getExpression()).isEqualTo("{\"naam\": $doc.naam}");
    }

    @Test
    void previewsWithTheCatalogAndTenantOfTheConfiguredPlugin() {
        offering(configuration(new LetterComposerConfiguration.OfferedTemplate("besluit", "Besluit", null)), "besluit");
        when(epistolaService.previewDocument(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any()))
                .thenReturn(new ByteArrayInputStream("%PDF".getBytes()));

        service.preview(CONTEXT, "besluit", Map.of("naam", "Jansen"));

        org.mockito.Mockito.verify(epistolaService).previewDocument(
                eq("http://epistola"), eq("key"), eq("gemeente"), eq("gemeente"), eq("besluit"),
                eq(null), eq("prod"), eq(Map.of("naam", "Jansen")));
    }

    @Test
    void refusesToPreviewATemplateTheFormDoesNotOffer() {
        when(configurationResolver.requireOffering(any(), any(), any(), eq("geheime-brief")))
                .thenThrow(new ComposerException(ComposerException.Reason.TEMPLATE_NOT_OFFERED, "nope"));

        assertThatThrownBy(() -> service.preview(CONTEXT, "geheime-brief", Map.of()))
                .isInstanceOf(ComposerException.class);
    }

    @Test
    void reportsARefusedRenderAsSuch() {
        offering(configuration(new LetterComposerConfiguration.OfferedTemplate("besluit", "Besluit", null)), "besluit");
        when(epistolaService.previewDocument(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("422 from Epistola"));

        assertThatThrownBy(() -> service.preview(CONTEXT, "besluit", Map.of()))
                .isInstanceOf(ComposerException.class)
                .extracting(e -> ((ComposerException) e).getReason())
                .isEqualTo(ComposerException.Reason.RENDER_FAILED);
    }
}
