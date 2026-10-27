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
import app.epistola.client.model.GenerationJobResponse;
import app.epistola.client.model.PreviewDocumentRequest;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.richtext.RichTextConversionException;
import app.epistola.valtimo.richtext.RichTextFixtures;
import app.epistola.valtimo.richtext.RichTextValues;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;

import java.time.OffsetDateTime;
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
 * Rich text on its way out (ADR 0008): what an editor tagged with its format is sent as a document,
 * and everything else — what a mapping produced — exactly as it was.
 */
class EpistolaServiceImplRichTextTest {

    private static final String TENANT = "tenant";
    private static final String HTML = "<p>Het bezwaar is <b>gegrond</b></p>";

    private GenerationApi generationApi;
    private GenerationApi longRunningApi;
    private EpistolaServiceImpl service;

    @BeforeEach
    void setUp() {
        EpistolaApiClientFactory factory = mock(EpistolaApiClientFactory.class);
        generationApi = mock(GenerationApi.class);
        longRunningApi = mock(GenerationApi.class);
        when(factory.createGenerationApi(anyString(), anyString())).thenReturn(generationApi);
        when(factory.createLongRunningGenerationApi(anyString(), anyString())).thenReturn(longRunningApi);
        when(generationApi.generateDocument(eq(TENANT), any())).thenReturn(new GenerationJobResponse(
                UUID.randomUUID(), GenerationJobResponse.Status.pending, GenerationJobResponse.JobType.single, 1,
                OffsetDateTime.now()));
        when(longRunningApi.previewDocument(eq(TENANT), any())).thenReturn(new ByteArrayResource(new byte[] {1}));
        service = new EpistolaServiceImpl(factory, 0, new RichTextValues(RichTextFixtures.converter("html")));
    }

    private static Map<String, Object> tagged(String format, String kind, String value) {
        return Map.of(RichTextValues.TAG, Map.of("format", format, "kind", kind, "value", value));
    }

    private static Map<String, Object> document() {
        Map<String, Object> plain = Map.of("type", "text", "text", "Het bezwaar is ");
        Map<String, Object> bold = Map.of("type", "text", "text", "gegrond", "marks", List.of(Map.of("type", "strong")));
        return Map.of("type", "doc", "content", List.of(Map.of("type", "paragraph", "content", List.of(plain, bold))));
    }

    private void submit(Map<String, Object> data) {
        service.submitGenerationJob("http://x", "key", TENANT, "local/valtimo/catalog", "besluit", null, null, null, null, data,
                FileFormat.PDF, "besluit.pdf", null, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sent() {
        ArgumentCaptor<GenerateDocumentRequest> request = ArgumentCaptor.forClass(GenerateDocumentRequest.class);
        verify(generationApi).generateDocument(eq(TENANT), request.capture());
        return (Map<String, Object>) request.getValue().getData();
    }

    @Test
    void sendsWhatAnEditorTypedAsADocument_andMappedDataAsMapped() {
        submit(Map.of(
                "besluit", Map.of("motivation", tagged("html", "BLOCK", HTML)),
                "mapped", HTML));

        assertThat(sent()).isEqualTo(Map.of(
                "besluit", Map.of("motivation", document()),
                "mapped", HTML));
    }

    @Test
    void leavesOutWhatAnEditorLeftEmpty_soARequiredFieldIsReportedMissing() {
        submit(Map.of(
                "motivation", tagged("html", "BLOCK", "<p><br></p>"),
                "alineas", List.of(tagged("html", "BLOCK", " ")),
                "naam", "Jansen"));

        assertThat(sent()).isEqualTo(Map.of("naam", "Jansen"));
    }

    @Test
    void previewsWhatAnEditorTypedAsADocument() {
        service.previewDocument("http://x", "key", TENANT, "local/valtimo/catalog", "besluit", null, null, null,
                Map.of("motivation", tagged("html", "BLOCK", HTML)));

        ArgumentCaptor<PreviewDocumentRequest> request = ArgumentCaptor.forClass(PreviewDocumentRequest.class);
        verify(longRunningApi).previewDocument(eq(TENANT), request.capture());
        assertThat(request.getValue().getData()).isEqualTo(Map.of("motivation", document()));
    }

    @Test
    void refusesAFormatItDoesNotKnow_sendingNothing_andNamingTheFieldNotTheText() {
        assertThatThrownBy(() -> submit(Map.of("motivation", tagged("rtf", "BLOCK", HTML))))
                .isInstanceOf(RichTextConversionException.class)
                .hasMessageContaining("motivation")
                .hasMessageContaining("rtf")
                .hasMessageNotContaining("gegrond");
        verify(generationApi, never()).generateDocument(any(), any());
    }
}
