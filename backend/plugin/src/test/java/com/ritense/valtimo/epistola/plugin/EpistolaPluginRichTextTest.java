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

import app.epistola.valtimo.domain.DocumentStorageTarget;
import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GenerationJobResult;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateField.FieldType;
import app.epistola.valtimo.domain.TemplateField.RichTextKind;
import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.richtext.RichTextConverter;
import app.epistola.valtimo.richtext.RichTextEpistolaService;
import app.epistola.valtimo.richtext.RichTextFixtures;
import app.epistola.valtimo.richtext.RichTextFormat;
import app.epistola.valtimo.richtext.RichTextLosses;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.completion.EpistolaResultCollectorRunner;
import app.epistola.valtimo.service.download.DocumentStorageStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.document.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
 * The generate action with rich text, through the conversion as it is wired: a mapping that yields
 * the HTML a case keeps reaches Epistola as a document, and a conversion that fails lands on the
 * process where its retry path can read it — naming the field, never the text.
 */
class EpistolaPluginRichTextTest {

    private EpistolaService client;
    private JsonataMappingService jsonataMappingService;
    private DelegateExecution execution;

    @BeforeEach
    void setUp() {
        client = mock(EpistolaService.class);
        jsonataMappingService = mock(JsonataMappingService.class);
        execution = mock(DelegateExecution.class);
        when(client.getTemplateDetails(any(), any(), any(), any(), any())).thenReturn(new TemplateDetails(
                "besluit", "Besluit", List.of(new TemplateField("toelichting", "toelichting", "richtext-block",
                        FieldType.SCALAR, true, null, List.of(), false, null, false,
                        new TemplateField.FieldHints(null, null, null, null, null, null, RichTextKind.BLOCK)))));
        when(jsonataMappingService.evaluate(any()))
                .thenReturn(Map.of("toelichting", "<p>Jansen, BSN <b>123456782</b></p>"));
        when(jsonataMappingService.evaluateScalar(any())).thenReturn("besluit.pdf");
    }

    private EpistolaPlugin plugin(RichTextConverter converter) {
        EpistolaPlugin plugin = new EpistolaPlugin(
                new RichTextEpistolaService(client, converter),
                mock(ObjectMapper.class),
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
        plugin.generateDocument(execution, null, "catalog", "besluit", null, null, null,
                "{\"toelichting\": $doc.toelichting}", "PDF", "besluit.pdf", null, "epistolaResult");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsTheCasesHtmlAsADocument() {
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        when(client.submitGenerationJob(anyString(), anyString(), anyString(), anyString(), anyString(), any(),
                any(), any(), data.capture(), eq(FileFormat.PDF), anyString(), any(), any()))
                .thenReturn(GenerationJobResult.builder().requestId("request-1").status("PENDING").build());

        generate(plugin(RichTextFixtures.converter("html")));

        Map<String, Object> plain = Map.of("type", "text", "text", "Jansen, BSN ");
        Map<String, Object> bold = Map.of("type", "text", "text", "123456782", "marks", List.of(Map.of("type", "strong")));
        Map<String, Object> paragraph = Map.of("type", "paragraph", "content", List.of(plain, bold));
        assertThat(data.getValue().get("toelichting")).isEqualTo(Map.of("type", "doc", "content", List.of(paragraph)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsAFailedConversionWhereTheProcessCanReadIt_withoutTheText() {
        RichTextFormat broken = new RichTextFormat() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public Map<String, Object> toProseMirror(Object value, RichTextLosses losses) {
                throw new IllegalStateException("cannot parse " + value);
            }
        };

        assertThatThrownBy(() -> generate(plugin(new RichTextConverter(List.of(broken), "broken"))))
                .isInstanceOf(RuntimeException.class);

        ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
        verify(execution).setVariable(eq("epistolaResult"), result.capture());
        String errorMessage = String.valueOf(((Map<String, Object>) result.getValue()).get("errorMessage"));
        assertThat(errorMessage).contains("'toelichting'", "'broken'").doesNotContain("123456782", "Jansen");
        verify(client, never()).submitGenerationJob(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }
}
