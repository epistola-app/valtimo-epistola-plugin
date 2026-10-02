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
import app.epistola.valtimo.domain.EpistolaProcessVariables;
import app.epistola.valtimo.domain.GenerationJobResult;
import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.completion.EpistolaResultCollectorRunner;
import app.epistola.valtimo.service.download.DocumentStorageStrategy;
import app.epistola.valtimo.domain.FileFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.document.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EpistolaPlugin#generateComposedDocument}: the action that renders whatever
 * a letter composer put on a process variable.
 *
 * <p>What matters here is that it takes the catalog, template and data from that variable and
 * nothing of its own — that is the whole reason it is a separate action — and that a variable
 * holding no usable letter fails loudly rather than generating nothing, which would leave a process
 * that looks like it sent a letter.
 */
class EpistolaPluginGenerateComposedDocumentTest {

    private static final String BASE_URL = "https://api.epistola.app";
    private static final String API_KEY = "api-key";
    private static final String TENANT_ID = "demo";
    private static final String RESULT_VAR = "epistolaResult";

    private EpistolaService epistolaService;
    private JsonataMappingService jsonataMappingService;
    private EpistolaResultCollectorRunner resultCollectorRunner;
    private DelegateExecution execution;
    private app.epistola.valtimo.composer.ComposerWriteBackService writeBackService;

    @BeforeEach
    void setUp() {
        epistolaService = mock(EpistolaService.class);
        jsonataMappingService = mock(JsonataMappingService.class);
        resultCollectorRunner = mock(EpistolaResultCollectorRunner.class);
        execution = mock(DelegateExecution.class);
        writeBackService = mock(app.epistola.valtimo.composer.ComposerWriteBackService.class);
        when(epistolaService.submitGenerationJob(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(GenerationJobResult.builder().requestId("req-1").status("PENDING").build());
    }

    private EpistolaPlugin plugin() {
        Map<DocumentStorageTarget, DocumentStorageStrategy> strategies = new EnumMap<>(DocumentStorageTarget.class);
        EpistolaPlugin plugin = new EpistolaPlugin(
                epistolaService,
                new ObjectMapper(),
                jsonataMappingService,
                mock(DocumentService.class),
                resultCollectorRunner,
                strategies,
                writeBackService);
        ReflectionTestUtils.setField(plugin, "baseUrl", BASE_URL);
        ReflectionTestUtils.setField(plugin, "apiKey", API_KEY);
        ReflectionTestUtils.setField(plugin, "tenantId", TENANT_ID);
        ReflectionTestUtils.setField(plugin, "defaultEnvironmentId", "prod");
        return plugin;
    }

    private void composerWrote(Object letter) {
        when(execution.getVariable(EpistolaProcessVariables.COMPOSED_LETTER)).thenReturn(letter);
    }

    private Map<String, Object> letter() {
        return Map.of(
                "catalogId", "gemeente",
                "templateId", "besluit-bezwaar",
                "data", Map.of("naam", "Jansen"));
    }

    @org.junit.jupiter.api.Nested
    class WriteBack {

        private static final java.util.UUID CASE = java.util.UUID.randomUUID();

        @Test
        void putsTheLettersValuesIntoTheCaseItWasSentFor() {
            // The case is found from the process instance's business key, not from anything the
            // letter claims — the letter is assembled in the browser.
            composerWrote(letter());
            when(execution.getBusinessKey()).thenReturn(CASE.toString());

            plugin().generateComposedDocument(execution, null, null, null, RESULT_VAR);

            verify(writeBackService).apply(eq(CASE), any(), org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        void onlyOnceTheLetterIsSent() {
            // Epistola refusing the request means nothing was sent, so nothing belongs in the case.
            // Writing first would update a case for a letter that never went out.
            composerWrote(letter());
            when(execution.getBusinessKey()).thenReturn(CASE.toString());
            when(epistolaService.submitGenerationJob(anyString(), anyString(), anyString(), anyString(),
                    anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Epistola said no"));

            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                    plugin().generateComposedDocument(execution, null, null, null, RESULT_VAR));

            verify(writeBackService, org.mockito.Mockito.never()).apply(any(), any(), org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        void nothingToWriteBackToWithoutACase() {
            // A process not started for a dossier has nowhere to write. Not an error: it still
            // rendered a letter, which is all such a process can want.
            composerWrote(letter());
            when(execution.getBusinessKey()).thenReturn(null);

            plugin().generateComposedDocument(execution, null, null, null, RESULT_VAR);

            verify(writeBackService, org.mockito.Mockito.never()).apply(any(), any(), org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        void aFailedWriteDoesNotFailTheActivity() {
            // The letter is irreversible by now. Throwing would claim it had not been sent, and
            // retrying the activity would generate a duplicate.
            composerWrote(letter());
            when(execution.getBusinessKey()).thenReturn(CASE.toString());
            org.mockito.Mockito.doThrow(new RuntimeException("case is locked"))
                    .when(writeBackService).apply(any(), any(), org.mockito.ArgumentMatchers.anyString());

            plugin().generateComposedDocument(execution, null, null, null, RESULT_VAR);

            verify(writeBackService).apply(eq(CASE), any(), org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        void aBusinessKeyThatIsNotADocumentIdIsIgnored() {
            // A process started with some other business key is not a dossier process.
            composerWrote(letter());
            when(execution.getBusinessKey()).thenReturn("not-a-uuid");

            plugin().generateComposedDocument(execution, null, null, null, RESULT_VAR);

            verify(writeBackService, org.mockito.Mockito.never()).apply(any(), any(), org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Test
    void generatesTheLetterTheComposerChose() {
        composerWrote(letter());

        plugin().generateComposedDocument(execution, null, null, null, RESULT_VAR);

        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(epistolaService).submitGenerationJob(
                eq(BASE_URL), eq(API_KEY), eq(TENANT_ID),
                eq("gemeente"), eq("besluit-bezwaar"),
                isNull(), isNull(), eq("prod"),
                data.capture(), eq(FileFormat.PDF), eq("besluit-bezwaar.pdf"), isNull(), any());
        assertThat(data.getValue()).containsEntry("naam", "Jansen");
    }

    @Test
    void readsTheLetterFromTheVariableTheActionNames() {
        when(execution.getVariable("mijnBrief")).thenReturn(letter());

        plugin().generateComposedDocument(execution, "mijnBrief", null, null, RESULT_VAR);

        verify(epistolaService).submitGenerationJob(anyString(), anyString(), anyString(),
                eq("gemeente"), eq("besluit-bezwaar"), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void recordsTheResultForTheCatchEventToCorrelateOn() {
        composerWrote(letter());

        plugin().generateComposedDocument(execution, null, null, null, RESULT_VAR);

        ArgumentCaptor<Map<String, Object>> result = ArgumentCaptor.forClass(Map.class);
        verify(execution).setVariable(eq(RESULT_VAR), result.capture());
        assertThat(result.getValue())
                .containsEntry(EpistolaProcessVariables.RESULT_KEY_REQUEST_ID, "req-1")
                .containsEntry(EpistolaProcessVariables.RESULT_KEY_STATUS, "PENDING")
                .containsEntry(EpistolaProcessVariables.RESULT_KEY_JOB_PATH, "epistola:job:demo/req-1");
        verify(execution).setVariable(EpistolaProcessVariables.TENANT_ID, TENANT_ID);
        // The jobPath-keyed locator is what lets the collector find this branch again.
        verify(execution).setVariable("epistola:job:demo/req-1", RESULT_VAR);
    }

    @Test
    void resolvesAConfiguredFilenameAndCorrelationId() {
        composerWrote(letter());
        when(jsonataMappingService.evaluateScalar(any())).thenReturn("brief.pdf", "zaak-42");

        plugin().generateComposedDocument(execution, null, "\"brief.pdf\"", "$pv.zaakId", RESULT_VAR);

        verify(epistolaService).submitGenerationJob(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any(), any(),
                eq("brief.pdf"), eq("zaak-42"), any());
    }

    @Test
    void failsWhenNoLetterWasComposed() {
        composerWrote(null);

        assertThatThrownBy(() -> plugin().generateComposedDocument(execution, null, null, null, RESULT_VAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(EpistolaProcessVariables.COMPOSED_LETTER);
        verifyNoInteractions(epistolaService);
    }

    @Test
    void failsOnAnUnusableResultVariableName() {
        composerWrote(letter());

        assertThatThrownBy(() -> plugin().generateComposedDocument(execution, null, null, null, " "))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(epistolaService);
    }
}
