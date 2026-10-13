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
package app.epistola.valtimo.richtext;

import app.epistola.client.api.GenerationApi;
import app.epistola.client.api.TemplatesApi;
import app.epistola.client.model.GenerateDocumentRequest;
import app.epistola.client.model.TemplateDto;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateField.FieldType;
import app.epistola.valtimo.domain.TemplateField.RichTextKind;
import app.epistola.valtimo.service.EpistolaApiException;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.EpistolaServiceImpl;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static app.epistola.valtimo.richtext.Docs.STRONG;
import static app.epistola.valtimo.richtext.Docs.doc;
import static app.epistola.valtimo.richtext.Docs.p;
import static app.epistola.valtimo.richtext.Docs.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rich-text fields are converted where data leaves for Epistola, so generation and preview — and
 * through them the composer and the retry form — send the same document (ADR 0008).
 */
class RichTextEpistolaServiceTest {

    private static final String TENANT = "tenant";
    private static final Map<String, Object> DATA = Map.of("toelichting", "<p><b>vet</b></p>", "naam", "Jansen");
    private static final Map<String, Object> CONVERTED =
            Map.of("toelichting", doc(p(text("vet", STRONG))), "naam", "Jansen");

    private static TemplateDetails template() {
        return new TemplateDetails("besluit", "Besluit", List.of(
                new TemplateField("toelichting", "toelichting", "richtext-block", FieldType.SCALAR, true, null,
                        List.of(), false, null, false,
                        new TemplateField.FieldHints(null, null, null, null, null, null, RichTextKind.BLOCK)),
                new TemplateField("naam", "naam", "string", FieldType.SCALAR, true, null, List.of())));
    }

    @Nested
    class AroundAMockedClient {

        private final EpistolaService client = mock(EpistolaService.class);
        private final MutableClock clock = new MutableClock();
        private final RichTextEpistolaService service =
                new RichTextEpistolaService(client, RichTextFixtures.converter("html"), clock);

        @SuppressWarnings("unchecked")
        private Map<String, Object> submitted() {
            ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
            verify(client).submitGenerationJob(anyString(), anyString(), anyString(), anyString(), anyString(),
                    any(), any(), any(), data.capture(), any(), any(), any(), any());
            return data.getValue();
        }

        private void submit(Map<String, Object> data) {
            service.submitGenerationJob("http://x", "key", TENANT, "catalog", "besluit", null, null, null,
                    data, FileFormat.PDF, "besluit.pdf", null, null);
        }

        @Test
        void convertsRichTextFieldsWhenSubmitting_andLeavesTheRestAlone() {
            when(client.getTemplateDetails(any(), any(), any(), any(), any())).thenReturn(template());

            submit(DATA);

            assertThat(submitted()).isEqualTo(CONVERTED);
        }

        @Test
        @SuppressWarnings("unchecked")
        void convertsThemTheSameWayWhenPreviewing() {
            when(client.getTemplateDetails(any(), any(), any(), any(), any())).thenReturn(template());
            ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
            when(client.previewDocument(any(), any(), any(), any(), any(), any(), any(), data.capture()))
                    .thenReturn(new ByteArrayInputStream(new byte[]{1}));

            service.previewDocument("http://x", "key", TENANT, "catalog", "besluit", null, null, DATA);

            assertThat(data.getValue()).isEqualTo(CONVERTED);
        }

        @Test
        void readsTheContractOncePerMinute_notOncePerPreview() {
            when(client.getTemplateDetails(any(), any(), any(), any(), any())).thenReturn(template());

            submit(DATA);
            submit(DATA);
            clock.advance(RichTextEpistolaService.FIELDS_TTL.minusSeconds(1));
            submit(DATA);
            verify(client, times(1)).getTemplateDetails(any(), any(), any(), any(), any());

            clock.advance(java.time.Duration.ofSeconds(2));
            submit(DATA);
            verify(client, times(2)).getTemplateDetails(any(), any(), any(), any(), any());
        }

        @Test
        void sendsTheDataAsMappedWhenTheContractCannotBeRead_andTriesAgainNextTime() {
            when(client.getTemplateDetails(any(), any(), any(), any(), any()))
                    .thenThrow(new EpistolaApiException("Template not found: besluit"))
                    .thenReturn(template());

            submit(DATA);
            submit(DATA);

            ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
            verify(client, times(2)).submitGenerationJob(any(), any(), any(), any(), any(), any(), any(), any(),
                    data.capture(), any(), any(), any(), any());
            assertThat(data.getAllValues()).containsExactly(DATA, CONVERTED);
        }

        @Test
        void readsNoContractForALetterWithoutData() {
            submit(Map.of());

            verify(client, never()).getTemplateDetails(any(), any(), any(), any(), any());
        }

        @Test
        void leavesAConversionFailureToTheCaller_beforeAnythingIsSent() {
            var failing = new RichTextEpistolaService(client, new RichTextConverter(List.of(new RichTextFormat() {
                @Override
                public String id() {
                    return "broken";
                }

                @Override
                public Map<String, Object> toProseMirror(Object value, RichTextLosses losses) {
                    throw new IllegalStateException("cannot parse " + value);
                }
            }), "broken"), clock);
            when(client.getTemplateDetails(any(), any(), any(), any(), any())).thenReturn(template());

            assertThatThrownBy(() -> failing.submitGenerationJob("http://x", "key", TENANT, "catalog", "besluit",
                    null, null, null, DATA, FileFormat.PDF, "besluit.pdf", null, null))
                    .isInstanceOf(RichTextConversionException.class)
                    .hasMessageContaining("'toelichting'");
            verify(client, never()).submitGenerationJob(any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any());
        }

        @Test
        void warnsWhatALetterLost_namingTheFieldAndTemplateButNeverTheText() {
            when(client.getTemplateDetails(any(), any(), any(), any(), any())).thenReturn(template());

            try (var logs = CapturedLogs.of(RichTextEpistolaService.class)) {
                submit(Map.of("toelichting", "<h1>Jansen, BSN 123456782</h1><p>tekst</p>"));

                assertThat(logs.at(Level.WARN)).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("'toelichting'", "'besluit'", "heading ×1")
                        .doesNotContain("Jansen", "123456782", "tekst"));
            }
        }

        @Test
        void onlyDebugsItForAPreview_whichTheComposerRepeatsOnEveryChange() {
            when(client.getTemplateDetails(any(), any(), any(), any(), any())).thenReturn(template());

            try (var logs = CapturedLogs.of(RichTextEpistolaService.class)) {
                service.previewDocument("http://x", "key", TENANT, "catalog", "besluit", null, null,
                        Map.of("toelichting", "<h1>Kop</h1>"));

                assertThat(logs.at(Level.WARN)).isEmpty();
                assertThat(logs.at(Level.DEBUG)).singleElement()
                        .satisfies(event -> assertThat(event.getFormattedMessage()).contains("heading ×1"));
            }
        }

        @Test
        void passesEverythingElseStraightThrough() {
            service.getJobStatus("http://x", "key", TENANT, "req");
            service.downloadDocument("http://x", "key", TENANT, "doc");

            verify(client).getJobStatus("http://x", "key", TENANT, "req");
            verify(client).downloadDocument("http://x", "key", TENANT, "doc");
        }
    }

    /**
     * The whole path at once, as wired: a contract carrying Epistola's rich-text {@code $ref},
     * read by the real client and its schema analysis, converted, and handed to the generation API.
     */
    @Test
    void convertsFieldsTheRealContractMarksAsRichText() {
        EpistolaApiClientFactory factory = mock(EpistolaApiClientFactory.class);
        TemplatesApi templatesApi = mock(TemplatesApi.class);
        GenerationApi generationApi = mock(GenerationApi.class);
        TemplateDto contract = mock(TemplateDto.class);
        when(contract.getId()).thenReturn("besluit");
        when(contract.getName()).thenReturn("Besluit");
        when(contract.getDataModel()).thenReturn(Map.of("type", "object", "properties", Map.of(
                "toelichting", Map.of("$ref", "https://epistola.app/schemas/richtext-block-v1.json"),
                "naam", Map.of("type", "string"))));
        when(factory.createTemplatesApi(anyString(), anyString())).thenReturn(templatesApi);
        when(factory.createGenerationApi(anyString(), anyString())).thenReturn(generationApi);
        when(templatesApi.getTemplate(TENANT, "catalog", "besluit")).thenReturn(contract);
        ArgumentCaptor<GenerateDocumentRequest> request = ArgumentCaptor.forClass(GenerateDocumentRequest.class);
        when(generationApi.generateDocument(eq(TENANT), request.capture())).thenThrow(new IllegalStateException("stop"));
        var service = new RichTextEpistolaService(new EpistolaServiceImpl(factory, 0), RichTextFixtures.converter("html"));

        assertThatThrownBy(() -> service.submitGenerationJob("http://x", "key", TENANT, "catalog", "besluit",
                null, null, null, DATA, FileFormat.PDF, "besluit.pdf", null, null));

        assertThat(request.getValue().getData()).isEqualTo(CONVERTED);
    }

    /** A clock a test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-12T12:00:00Z");

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
