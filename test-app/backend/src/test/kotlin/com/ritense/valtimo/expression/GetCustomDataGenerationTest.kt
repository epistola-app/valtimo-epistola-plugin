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
package com.ritense.valtimo.expression

import app.epistola.valtimo.domain.DocumentStorageTarget
import app.epistola.valtimo.domain.FileFormat
import app.epistola.valtimo.domain.GenerationJobResult
import app.epistola.valtimo.expression.ExpressionContext
import app.epistola.valtimo.expression.ExpressionFunctionRegistry
import app.epistola.valtimo.mapping.JsonataMappingService
import app.epistola.valtimo.service.EpistolaService
import app.epistola.valtimo.service.completion.EpistolaResultCollectorRunner
import app.epistola.valtimo.service.download.DocumentStorageStrategy
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.document.service.DocumentService
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.springframework.test.util.ReflectionTestUtils
import java.util.EnumMap

class GetCustomDataGenerationTest {
    @Test
    fun `generation evaluates custom function and submits its structured result`() {
        val customDataFunction = spy(GetCustomDataFunction())
        val mappingService = JsonataMappingService(ExpressionFunctionRegistry(listOf(customDataFunction)))
        val epistolaService = mock<EpistolaService>()
        val resultCollectorRunner = mock<EpistolaResultCollectorRunner>()
        val execution = mock<DelegateExecution>()
        val submittedData = argumentCaptor<Map<String, Any>>()

        whenever(
            epistolaService.submitGenerationJob(
                any(),
                any(),
                any(),
                any(),
                any(),
                isNull(),
                isNull(),
                any(),
                any(),
                eq(FileFormat.PDF),
                any(),
                isNull(),
                isNull(),
            ),
        ).thenReturn(
            GenerationJobResult
                .builder()
                .requestId("request-1")
                .status("PENDING")
                .build(),
        )

        val plugin =
            EpistolaPlugin(
                epistolaService,
                ObjectMapper(),
                mappingService,
                mock<DocumentService>(),
                resultCollectorRunner,
                EnumMap<DocumentStorageTarget, DocumentStorageStrategy>(DocumentStorageTarget::class.java),
            )
        ReflectionTestUtils.setField(plugin, "baseUrl", "https://api.epistola.app")
        ReflectionTestUtils.setField(plugin, "apiKey", "api-key")
        ReflectionTestUtils.setField(plugin, "tenantId", "demo")
        ReflectionTestUtils.setField(plugin, "defaultEnvironmentId", "default")

        plugin.generateDocument(
            execution,
            null,
            "catalog",
            "template",
            null,
            null,
            null,
            """
            {
                "customerName": ${'$'}getCustomData().customerName,
                "status": ${'$'}getCustomData().status,
                "tags": ${'$'}getCustomData().tags
            }
            """.trimIndent(),
            "PDF",
            "document.pdf",
            null,
            "epistolaResult",
        )

        verify(customDataFunction).execute(any<ExpressionContext>())
        verify(epistolaService).submitGenerationJob(
            eq("https://api.epistola.app"),
            eq("api-key"),
            eq("demo"),
            eq("catalog"),
            eq("template"),
            isNull(),
            isNull(),
            eq("default"),
            submittedData.capture(),
            eq(FileFormat.PDF),
            eq("document.pdf"),
            isNull(),
            isNull(),
        )
        assertThat(submittedData.firstValue)
            .containsEntry("customerName", "Example customer")
            .containsEntry("status", "active")
        assertThat(submittedData.firstValue["tags"]).isEqualTo(listOf("priority", "digital"))
    }
}