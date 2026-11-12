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
package com.ritense.valtimo.epistola.plugin;

import app.epistola.client.api.GenerationApi;
import app.epistola.client.model.GenerateDocumentRequest;
import app.epistola.client.model.GenerationJobResponse;
import app.epistola.client.model.GenerationJobStatus;
import app.epistola.client.model.GenerationJobType;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.DocumentStorageTarget;
import app.epistola.valtimo.domain.EpistolaProcessVariables;
import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.richtext.RichTextConverter;
import app.epistola.valtimo.richtext.RichTextFixtures;
import app.epistola.valtimo.richtext.RichTextFormat;
import app.epistola.valtimo.richtext.RichTextLosses;
import app.epistola.valtimo.richtext.RichTextValues;
import app.epistola.valtimo.service.EpistolaServiceImpl;
import app.epistola.valtimo.service.completion.EpistolaResultCollectorRunner;
import app.epistola.valtimo.service.download.DocumentStorageStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.document.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The generate actions with rich text (ADR 0008), through the client as it is wired, down to the
 * request Epistola receives. What a person typed into the plugin's editors — a composed letter —
 * arrives tagged with its format and is sent as a document; what a mapping produced is sent as
 * mapped, because its author converts with {@code $richText}. A conversion that fails raises an
 * incident naming the field, never the text.
 */
class EpistolaPluginRichTextTest {

    private static final String HTML = "<p>Jansen, BSN <b>123456782</b></p>";

    private EpistolaApiClientFactory factory;
    private GenerationApi generationApi;
    private JsonataMappingService jsonataMappingService;
    private DelegateExecution execution;

    @BeforeEach
    void setUp() {
        factory = mock(EpistolaApiClientFactory.class);
        generationApi = mock(GenerationApi.class);
        jsonataMappingService = mock(JsonataMappingService.class);
        execution = mock(DelegateExecution.class);
        when(factory.createGenerationApi(anyString(), anyString())).thenReturn(generationApi);
        when(generationApi.generateDocument(any(), any())).thenReturn(new GenerationJobResponse(
                UUID.randomUUID(), GenerationJobStatus.pending, GenerationJobType.single, 1,
                OffsetDateTime.now()));
        when(jsonataMappingService.evaluate(any())).thenReturn(Map.of("toelichting", HTML));
        when(jsonataMappingService.evaluateScalar(any())).thenReturn("besluit.pdf");
    }

    private EpistolaPlugin plugin(RichTextConverter converter) {
        EpistolaPlugin plugin = new EpistolaPlugin(
                new EpistolaServiceImpl(factory, 0, new RichTextValues(converter)),
                new ObjectMapper(),
                jsonataMappingService,
                mock(DocumentService.class),
                mock(EpistolaResultCollectorRunner.class),
                new EnumMap<DocumentStorageTarget, DocumentStorageStrategy>(DocumentStorageTarget.class));
        ReflectionTestUtils.setField(plugin, "baseUrl", "https://api.epistola.app");
        ReflectionTestUtils.setField(plugin, "apiKey", "api-key");
        ReflectionTestUtils.setField(plugin, "tenantId", "demo");
        ReflectionTestUtils.setField(plugin, "defaultEnvironmentId", "default");
        return plugin;
    }

    private void generate(EpistolaPlugin plugin) {
        plugin.generateDocument(execution, null, "local/valtimo/catalog", "besluit", null, null, null,
                null,
                "{\"toelichting\": $doc.toelichting}", "PDF", "besluit.pdf", null, "epistolaResult");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sent() {
        ArgumentCaptor<GenerateDocumentRequest> request = ArgumentCaptor.forClass(GenerateDocumentRequest.class);
        verify(generationApi).generateDocument(eq("demo"), request.capture());
        return (Map<String, Object>) request.getValue().getData();
    }

    private static Map<String, Object> document() {
        Map<String, Object> plain = Map.of("type", "text", "text", "Jansen, BSN ");
        Map<String, Object> bold = Map.of("type", "text", "text", "123456782", "marks", List.of(Map.of("type", "strong")));
        return Map.of("type", "doc", "content", List.of(Map.of("type", "paragraph", "content", List.of(plain, bold))));
    }

    @Test
    void sendsMappedDataAsMapped_readingNoContract() {
        generate(plugin(RichTextFixtures.converter("html")));

        assertThat(sent()).containsEntry("toelichting", HTML);
        verify(factory, never()).createTemplatesApi(any(), any());
    }

    @Test
    void sendsAComposedLetterAsADocument() {
        givenAComposedLetterWithTypedText();

        plugin(RichTextFixtures.converter("html")).generateComposedDocument(execution, null, null, null,
                "epistolaResult");

        assertThat(sent()).containsEntry("toelichting", document());
    }

    private void givenAComposedLetterWithTypedText() {
        when(execution.getVariable(EpistolaProcessVariables.COMPOSED_LETTER)).thenReturn(Map.of(
                "catalogId", "local/valtimo/catalog", "templateId", "besluit", "data", Map.of("toelichting",
                        Map.of(RichTextValues.TAG, Map.of("format", "html", "kind", "BLOCK", "value", HTML)))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedConversionRaisesAnIncidentNamingTheField_withoutTheText() {
        RichTextFormat broken = new RichTextFormat() {
            @Override
            public String id() {
                return "html";
            }

            @Override
            public Map<String, Object> toProseMirror(Object value, RichTextLosses losses) {
                throw new IllegalStateException("cannot parse " + value);
            }
        };
        givenAComposedLetterWithTypedText();

        // The text cannot become what the template takes: the step cannot start, an incident whose
        // text names the field and the format — never the text itself (ADR 0009, third addendum).
        assertThatThrownBy(() -> plugin(new RichTextConverter(List.of(broken), "html"))
                .generateComposedDocument(execution, null, null, null, "epistolaResult"))
                .isInstanceOf(app.epistola.valtimo.service.DocumentGenerationRefusedException.class)
                .hasMessageContaining("'toelichting'")
                .hasMessageContaining("'html'")
                .hasMessageNotContaining("123456782")
                .hasMessageNotContaining("Jansen")
                .hasNoCause();
        verify(execution, never()).setVariable(eq("epistolaResult"), any());
        verify(generationApi, never()).generateDocument(any(), any());
    }
}
