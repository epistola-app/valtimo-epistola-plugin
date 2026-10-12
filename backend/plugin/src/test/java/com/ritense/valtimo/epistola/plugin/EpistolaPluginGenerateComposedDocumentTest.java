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

    @BeforeEach
    void setUp() {
        epistolaService = mock(EpistolaService.class);
        jsonataMappingService = mock(JsonataMappingService.class);
        resultCollectorRunner = mock(EpistolaResultCollectorRunner.class);
        execution = mock(DelegateExecution.class);
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
                strategies);
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

    /**
     * What happens when the letter no longer exists, or no longer accepts this data.
     *
     * <p>Both are the same shape at generate time and both are ordinary: a template removed from
     * the catalog, or one whose data contract moved after this letter was composed. The letter sits
     * on a process variable and can wait there for days, so the thing it was composed against is
     * not guaranteed to still be there — and Epistola is the only party that can say so.
     *
     * <p>The process must be able to see it. A submit-time refusal writes the same FAILED result
     * object a later failure would, so a BPMN branch or the retry form reads
     * {@code ${result.errorMessage}} either way, and the activity then fails rather than reporting
     * a letter it did not send.
     */
    /**
     * The action renders a letter object; the composer is one way to produce one, not the only way.
     *
     * <p>Worth pinning, because it decides whether this action is usable at all outside the
     * composer UI — a process started with the variable set by hand, by an API caller, by a
     * previous service task. Nothing here consults a composer: the catalog, the template and the
     * data all come from the variable, and the only requirement is that it names a catalog and a
     * template.
     *
     * <p>What such a letter does not get is write-back, and that is correct rather than a
     * limitation: the rules live on a composer, so a letter no composer produced has nobody to say
     * where its values belong.
     */
    @Test
    void rendersALetterNoComposerProduced() {
        // The least a process can set: no schemaVersion, no inputs, no label — a catalog, a
        // template, and the data to render with.
        when(execution.getVariable(EpistolaProcessVariables.COMPOSED_LETTER)).thenReturn(Map.of(
                "catalogId", "gemeente",
                "templateId", "besluit-bezwaar",
                "data", Map.of("naam", "Jansen")));
        when(execution.getBusinessKey()).thenReturn(java.util.UUID.randomUUID().toString());

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
    void recordsTheRefusalWhereTheProcessCanReadIt() {
        composerWrote(letter());
        when(epistolaService.submitGenerationJob(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Template 'besluit-bezwaar' not found in catalog 'gemeente'"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                plugin().generateComposedDocument(execution, null, null, null, RESULT_VAR));

        ArgumentCaptor<Map<String, Object>> result = ArgumentCaptor.forClass(Map.class);
        verify(execution).setVariable(eq(RESULT_VAR), result.capture());
        assertThat(result.getValue())
                .containsEntry(EpistolaProcessVariables.RESULT_KEY_STATUS, "FAILED");
        assertThat(String.valueOf(result.getValue().get(EpistolaProcessVariables.RESULT_KEY_ERROR_MESSAGE)))
                .describedAs("the reason Epistola gave, not a generic failure")
                .contains("not found in catalog");
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
