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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class EpistolaPluginGenerateDocumentTest {

    private static final String BASE_URL = "https://api.epistola.app";
    private static final String API_KEY = "api-key";
    private static final String TENANT_ID = "demo";

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
    }

    private EpistolaPlugin plugin() {
        Map<DocumentStorageTarget, DocumentStorageStrategy> strategies = new EnumMap<>(DocumentStorageTarget.class);
        EpistolaPlugin plugin = new EpistolaPlugin(
                epistolaService,
                mock(ObjectMapper.class),
                jsonataMappingService,
                mock(DocumentService.class),
                resultCollectorRunner,
                strategies);
        ReflectionTestUtils.setField(plugin, "baseUrl", BASE_URL);
        ReflectionTestUtils.setField(plugin, "apiKey", API_KEY);
        ReflectionTestUtils.setField(plugin, "tenantId", TENANT_ID);
        return plugin;
    }

    @Test
    void generateDocumentFailsFastWhenResultProcessVariableIsNotAlphanumeric() {
        assertThatThrownBy(() -> plugin().generateDocument(
                execution,
                "catalog",
                "template",
                null,
                null,
                null,
                "{}",
                FileFormat.PDF,
                "\"document.pdf\"",
                null,
                "pv:some-value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resultProcessVariable")
                .hasMessageContaining("letters and numbers");

        verifyNoInteractions(epistolaService, jsonataMappingService, resultCollectorRunner);
        verify(execution, never()).setVariable(anyString(), any());
    }
}
