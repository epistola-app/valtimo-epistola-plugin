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
import app.epistola.valtimo.domain.GenerationJobDetail;
import app.epistola.valtimo.domain.GenerationJobStatus;
import app.epistola.valtimo.domain.EpistolaProcessVariables;
import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.completion.EpistolaResultCollectorRunner;
import app.epistola.valtimo.service.download.DocumentStorageStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.document.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EpistolaPlugin#checkJobStatus}: the type-tolerant request-id extraction
 * (rich-result {@code Map} vs legacy {@code String}), validation, and conditional output-variable
 * writes for the resolved {@link GenerationJobDetail}.
 */
class EpistolaPluginCheckJobStatusTest {

    private static final String BASE_URL = "https://api.epistola.app";
    private static final String API_KEY = "api-key";
    private static final String TENANT_ID = "demo";
    private static final String REQUEST_ID_VAR = "epistolaResult";
    private static final String STATUS_VAR = "jobStatus";
    private static final String DOCUMENT_ID_VAR = "documentId";
    private static final String ERROR_VAR = "errorMessage";
    private static final String REQUEST_ID = "req-1";

    private EpistolaService epistolaService;
    private DelegateExecution execution;

    @BeforeEach
    void setUp() {
        epistolaService = mock(EpistolaService.class);
        execution = mock(DelegateExecution.class);
    }

    private EpistolaPlugin plugin() {
        Map<DocumentStorageTarget, DocumentStorageStrategy> strategies = new EnumMap<>(DocumentStorageTarget.class);
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

    private void stubStatus(GenerationJobDetail detail) {
        when(epistolaService.getJobStatus(BASE_URL, API_KEY, TENANT_ID, REQUEST_ID)).thenReturn(detail);
    }

    @Test
    void extractsRequestIdFromRichResultMap_andStoresStatusAndDocumentId() {
        when(execution.getVariable(REQUEST_ID_VAR))
                .thenReturn(Map.of(EpistolaProcessVariables.RESULT_KEY_REQUEST_ID, REQUEST_ID));
        stubStatus(GenerationJobDetail.builder()
                .requestId(REQUEST_ID)
                .status(GenerationJobStatus.COMPLETED)
                .documentId("doc-99")
                .build());

        plugin().checkJobStatus(execution, REQUEST_ID_VAR, STATUS_VAR, DOCUMENT_ID_VAR, ERROR_VAR);

        verify(execution).setVariable(STATUS_VAR, "COMPLETED");
        verify(execution).setVariable(DOCUMENT_ID_VAR, "doc-99");
        verify(execution, never()).setVariable(eq(ERROR_VAR), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void extractsRequestIdFromLegacyStringVariable() {
        when(execution.getVariable(REQUEST_ID_VAR)).thenReturn(REQUEST_ID);
        stubStatus(GenerationJobDetail.builder()
                .requestId(REQUEST_ID)
                .status(GenerationJobStatus.PENDING)
                .build());

        plugin().checkJobStatus(execution, REQUEST_ID_VAR, STATUS_VAR, DOCUMENT_ID_VAR, ERROR_VAR);

        verify(execution).setVariable(STATUS_VAR, "PENDING");
        verify(execution, never()).setVariable(eq(DOCUMENT_ID_VAR), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void storesErrorMessage_whenFailed() {
        when(execution.getVariable(REQUEST_ID_VAR)).thenReturn(REQUEST_ID);
        stubStatus(GenerationJobDetail.builder()
                .requestId(REQUEST_ID)
                .status(GenerationJobStatus.FAILED)
                .errorMessage("boom")
                .build());

        plugin().checkJobStatus(execution, REQUEST_ID_VAR, STATUS_VAR, DOCUMENT_ID_VAR, ERROR_VAR);

        verify(execution).setVariable(STATUS_VAR, "FAILED");
        verify(execution).setVariable(ERROR_VAR, "boom");
    }

    @Test
    void throwsWhenVariableIsNull() {
        when(execution.getVariable(REQUEST_ID_VAR)).thenReturn(null);

        assertThatThrownBy(() ->
                plugin().checkJobStatus(execution, REQUEST_ID_VAR, STATUS_VAR, DOCUMENT_ID_VAR, ERROR_VAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null or empty");
        verifyNoInteractions(epistolaService);
    }

    @Test
    void throwsWhenVariableIsUnsupportedType() {
        when(execution.getVariable(REQUEST_ID_VAR)).thenReturn(42);

        assertThatThrownBy(() ->
                plugin().checkJobStatus(execution, REQUEST_ID_VAR, STATUS_VAR, DOCUMENT_ID_VAR, ERROR_VAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a String or a Map");
        verifyNoInteractions(epistolaService);
    }
}
