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
package app.epistola.valtimo.service;

import app.epistola.client.api.GenerationApi;
import app.epistola.client.model.GenerateDocumentRequest;
import app.epistola.client.model.PreviewDocumentRequest;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateField.FieldType;
import app.epistola.valtimo.domain.TemplateField.RichTextKind;
import app.epistola.valtimo.richtext.CapturedLogs;
import app.epistola.valtimo.richtext.HtmlFormat;
import app.epistola.valtimo.richtext.MarkdownFormat;
import app.epistola.valtimo.richtext.PlainTextFormat;
import app.epistola.valtimo.richtext.RichTextConverter;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rich-text fields are converted where data leaves for Epistola, so generation and preview — and
 * through them the composer and the retry form — send the same document (ADR 0008).
 */
class EpistolaServiceImplRichTextTest {

    private static final String TENANT = "tenant";
    private static final Map<String, Object> DATA = Map.of("toelichting", "**vet**", "naam", "Jansen");
    private static final Map<String, Object> CONVERTED = Map.of(
            "toelichting", Map.of("type", "doc", "content", List.of(Map.of("type", "paragraph", "content",
                    List.of(Map.of("type", "text", "text", "vet", "marks", List.of(Map.of("type", "strong"))))))),
            "naam", "Jansen");

    private final EpistolaApiClientFactory factory = mock(EpistolaApiClientFactory.class);
    private final GenerationApi generationApi = mock(GenerationApi.class);
    private final RichTextConverter converter =
            new RichTextConverter(List.of(new PlainTextFormat(), new MarkdownFormat()), "markdown");
    private TemplateDetails template = new TemplateDetails("besluit", "Besluit", List.of(
            new TemplateField("toelichting", "toelichting", "richtext-block", FieldType.SCALAR, true, null,
                    List.of(), false, null, false,
                    new TemplateField.FieldHints(null, null, null, null, null, null, RichTextKind.BLOCK)),
            new TemplateField("naam", "naam", "string", FieldType.SCALAR, true, null, List.of())));
    private RuntimeException templateFailure;

    /** The same service, reading strings as HTML. */
    private EpistolaServiceImpl htmlService() {
        var html = new RichTextConverter(List.of(new PlainTextFormat(), new HtmlFormat()), "html");
        return new EpistolaServiceImpl(factory, 0, html) {
            @Override
            public TemplateDetails getTemplateDetails(
                    String baseUrl, String apiKey, String tenantId, String catalogId, String templateId) {
                return template;
            }
        };
    }

    private final EpistolaServiceImpl service = new EpistolaServiceImpl(factory, 0, converter) {
        @Override
        public TemplateDetails getTemplateDetails(
                String baseUrl, String apiKey, String tenantId, String catalogId, String templateId) {
            if (templateFailure != null) {
                throw templateFailure;
            }
            return template;
        }
    };

    @BeforeEach
    void stubApis() {
        when(factory.createGenerationApi(anyString(), anyString())).thenReturn(generationApi);
        when(factory.createLongRunningGenerationApi(anyString(), anyString())).thenReturn(generationApi);
    }

    private Object submitted() {
        ArgumentCaptor<GenerateDocumentRequest> request = ArgumentCaptor.forClass(GenerateDocumentRequest.class);
        when(generationApi.generateDocument(eq(TENANT), request.capture())).thenThrow(new IllegalStateException("stop"));
        assertThatThrownBy(() -> service.submitGenerationJob("http://x", "key", TENANT, "catalog", "besluit",
                null, null, null, DATA, FileFormat.PDF, "besluit.pdf", null, null))
                .isInstanceOf(EpistolaApiException.class);
        return request.getValue().getData();
    }

    private Object previewed() {
        ArgumentCaptor<PreviewDocumentRequest> request = ArgumentCaptor.forClass(PreviewDocumentRequest.class);
        when(generationApi.previewDocument(eq(TENANT), request.capture())).thenThrow(new IllegalStateException("stop"));
        assertThatThrownBy(() -> service.previewDocument("http://x", "key", TENANT, "catalog", "besluit",
                null, null, DATA))
                .isInstanceOf(RuntimeException.class);
        return request.getValue().getData();
    }

    @Test
    void convertsRichTextFieldsWhenSubmitting() {
        assertThat(submitted()).isEqualTo(CONVERTED);
    }

    @Test
    void convertsThemTheSameWayWhenPreviewing() {
        assertThat(previewed()).isEqualTo(CONVERTED);
    }

    @Test
    void warnsWhatALetterLost_namingTheFieldAndTemplateButNeverTheText() {
        Map<String, Object> html = Map.of("toelichting", "<h1>Jansen, BSN 123456782</h1><p>tekst</p>", "naam", "x");
        ArgumentCaptor<GenerateDocumentRequest> request = ArgumentCaptor.forClass(GenerateDocumentRequest.class);
        when(generationApi.generateDocument(eq(TENANT), request.capture())).thenThrow(new IllegalStateException("stop"));

        try (var logs = CapturedLogs.of(EpistolaServiceImpl.class)) {
            assertThatThrownBy(() -> htmlService().submitGenerationJob("http://x", "key", TENANT, "catalog", "besluit",
                    null, null, null, html, FileFormat.PDF, "besluit.pdf", null, null));

            assertThat(logs.at(Level.WARN)).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("'toelichting'", "'besluit'", "heading \u00D71")
                    .doesNotContain("Jansen", "123456782", "tekst"));
        }
    }

    @Test
    void onlyDebugsItForAPreview_whichTheComposerRepeatsOnEveryChange() {
        Map<String, Object> html = Map.of("toelichting", "<h1>Kop</h1>", "naam", "x");
        when(generationApi.previewDocument(eq(TENANT), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("stop"));

        try (var logs = CapturedLogs.of(EpistolaServiceImpl.class)) {
            assertThatThrownBy(() -> htmlService().previewDocument("http://x", "key", TENANT, "catalog", "besluit",
                    null, null, html));

            assertThat(logs.events()).noneMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("lost what Epistola cannot render"));
            assertThat(logs.at(Level.DEBUG)).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("heading \u00D71"));
        }
    }

    @Test
    void sendsTheDataAsMappedWhenTheTemplateCannotBeRead() {
        templateFailure = new EpistolaApiException("Template not found: besluit");

        assertThat(submitted()).isEqualTo(DATA);
    }

    @Test
    void sendsDataUnchangedWithoutAConverter() {
        EpistolaServiceImpl plain = new EpistolaServiceImpl(factory, 0);
        ArgumentCaptor<GenerateDocumentRequest> request = ArgumentCaptor.forClass(GenerateDocumentRequest.class);
        when(generationApi.generateDocument(eq(TENANT), request.capture())).thenThrow(new IllegalStateException("stop"));

        assertThatThrownBy(() -> plain.submitGenerationJob("http://x", "key", TENANT, "catalog", "besluit",
                null, null, null, DATA, FileFormat.PDF, "besluit.pdf", null, null));

        assertThat(request.getValue().getData()).isEqualTo(DATA);
    }
}
