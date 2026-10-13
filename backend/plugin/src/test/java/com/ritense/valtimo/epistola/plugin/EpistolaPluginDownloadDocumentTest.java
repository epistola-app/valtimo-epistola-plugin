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
import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.completion.EpistolaResultCollectorRunner;
import app.epistola.valtimo.service.download.DocumentStorageStrategy;
import app.epistola.valtimo.service.download.ProcessVariableStorageStrategy;
import app.epistola.valtimo.service.download.TemporaryResourceStorageStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.document.service.DocumentService;
import com.ritense.resource.service.TemporaryResourceStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.variable.value.BytesValue;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EpistolaPlugin#downloadDocument} across {@link DocumentStorageTarget}s.
 *
 * <p>See {@code docs/adr/0001-download-document-content-storage.md}. The default
 * {@code TEMPORARY_RESOURCE} strategy stores the PDF in temporary resource storage and writes only a
 * small resource id; the {@code PROCESS_VARIABLE} strategy stores raw bytes inline. The
 * action validates the per-target output variable and strategy availability before downloading.
 */
class EpistolaPluginDownloadDocumentTest {

    private static final String BASE_URL = "https://api.epistola.app";
    private static final String API_KEY = "api-key";
    private static final String TENANT_ID = "demo";
    private static final String DOCUMENT_VARIABLE = "epistolaResult";
    private static final String RESOURCE_ID_VARIABLE = "documentResourceId";
    private static final String CONTENT_VARIABLE = "documentContent";
    private static final String DOCUMENT_ID = "doc-123";

    private EpistolaService epistolaService;
    private TemporaryResourceStorageService temporaryResourceStorageService;
    private DelegateExecution execution;

    @BeforeEach
    void setUp() {
        epistolaService = mock(EpistolaService.class);
        temporaryResourceStorageService = mock(TemporaryResourceStorageService.class);
        execution = mock(DelegateExecution.class);
        when(execution.getVariable(DOCUMENT_VARIABLE)).thenReturn(DOCUMENT_ID);
    }

    private EpistolaPlugin pluginWith(Map<DocumentStorageTarget, DocumentStorageStrategy> strategies) {
        EpistolaPlugin plugin = new EpistolaPlugin(
                epistolaService,
                mock(ObjectMapper.class),
                mock(JsonataMappingService.class),
                mock(DocumentService.class),
                mock(EpistolaResultCollectorRunner.class),
                strategies);
        ReflectionTestUtils.setField(plugin, "baseUrl", BASE_URL);
        ReflectionTestUtils.setField(plugin, "apiKey", API_KEY);
        ReflectionTestUtils.setField(plugin, "tenantId", TENANT_ID);
        return plugin;
    }

    private EpistolaPlugin pluginWithAllStrategies() {
        Map<DocumentStorageTarget, DocumentStorageStrategy> strategies = new EnumMap<>(DocumentStorageTarget.class);
        strategies.put(DocumentStorageTarget.TEMPORARY_RESOURCE,
                new TemporaryResourceStorageStrategy(temporaryResourceStorageService));
        strategies.put(DocumentStorageTarget.PROCESS_VARIABLE, new ProcessVariableStorageStrategy());
        return pluginWith(strategies);
    }

    @Test
    void temporaryResource_isTheDefaultAndStoresOnlyTheResourceId() throws Exception {
        byte[] pdf = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2d}; // %PDF-
        when(epistolaService.downloadDocument(BASE_URL, API_KEY, TENANT_ID, DOCUMENT_ID)).thenReturn(pdf);
        when(temporaryResourceStorageService.store(any(InputStream.class), anyMap())).thenReturn("res-1");

        // storageTarget = null exercises the default (TEMPORARY_RESOURCE).
        pluginWithAllStrategies().downloadDocument(execution, DOCUMENT_VARIABLE, null, RESOURCE_ID_VARIABLE, null);

        ArgumentCaptor<InputStream> streamCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(temporaryResourceStorageService).store(streamCaptor.capture(), anyMap());
        assertThat(streamCaptor.getValue().readAllBytes()).isEqualTo(pdf);
        verify(execution).setVariable(RESOURCE_ID_VARIABLE, "res-1");
    }

    @Test
    void temporaryResource_handlesDocumentLargerThanVarcharLimit() {
        byte[] largePdf = new byte[8192]; // Base64 ~10.9 KB — would overflow varchar(4000) as a String var
        when(epistolaService.downloadDocument(BASE_URL, API_KEY, TENANT_ID, DOCUMENT_ID)).thenReturn(largePdf);
        when(temporaryResourceStorageService.store(any(InputStream.class), anyMap())).thenReturn("res-2");

        assertThatCode(() -> pluginWithAllStrategies().downloadDocument(
                execution, DOCUMENT_VARIABLE, DocumentStorageTarget.TEMPORARY_RESOURCE, RESOURCE_ID_VARIABLE, null))
                .doesNotThrowAnyException();

        verify(execution).setVariable(RESOURCE_ID_VARIABLE, "res-2");
    }

    @Test
    void processVariable_storesInlineBytesAndDoesNotTouchResourceStorage() {
        byte[] pdf = new byte[]{0x25, 0x50, 0x44, 0x46};
        when(epistolaService.downloadDocument(BASE_URL, API_KEY, TENANT_ID, DOCUMENT_ID)).thenReturn(pdf);

        pluginWithAllStrategies().downloadDocument(
                execution, DOCUMENT_VARIABLE, DocumentStorageTarget.PROCESS_VARIABLE, null, CONTENT_VARIABLE);

        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(execution).setVariable(eq(CONTENT_VARIABLE), valueCaptor.capture());
        assertThat(valueCaptor.getValue())
                .as("PROCESS_VARIABLE must store a byte variable, not a String")
                .isInstanceOf(BytesValue.class);
        assertThat(((BytesValue) valueCaptor.getValue()).getValue()).isEqualTo(pdf);
        verifyNoInteractions(temporaryResourceStorageService);
    }

    @Test
    void failsFast_whenOutputVariableForTargetIsNotConfigured() {
        // TEMPORARY_RESOURCE selected but resourceIdVariable blank — should not even download.
        assertThatThrownBy(() -> pluginWithAllStrategies().downloadDocument(
                execution, DOCUMENT_VARIABLE, DocumentStorageTarget.TEMPORARY_RESOURCE, "  ", CONTENT_VARIABLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output variable");
        verifyNoInteractions(epistolaService);
    }

    @Test
    void failsFast_whenSelectedTargetStrategyIsUnavailable() {
        // Environment without temporary resource storage: only the process-variable strategy is registered.
        Map<DocumentStorageTarget, DocumentStorageStrategy> onlyInline = new EnumMap<>(DocumentStorageTarget.class);
        onlyInline.put(DocumentStorageTarget.PROCESS_VARIABLE, new ProcessVariableStorageStrategy());

        assertThatThrownBy(() -> pluginWith(onlyInline).downloadDocument(
                execution, DOCUMENT_VARIABLE, DocumentStorageTarget.TEMPORARY_RESOURCE, RESOURCE_ID_VARIABLE, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not available");
        verifyNoInteractions(epistolaService);
    }
}
