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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package com.ritense.valtimo.epistola

import app.epistola.valtimo.action.generate.GenerateDocumentActionConfigurationRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Prevents bundled demo process links from silently falling back to deprecated v0 semantics.
 *
 * Every first-party generate-document action should demonstrate the latest persisted action
 * configuration and remain parseable by the matching backend version parser.
 */
class BundledGenerateDocumentActionConfigVersionTest {
    private val mapper = ObjectMapper()
    private val resolver = PathMatchingResourcePatternResolver()

    @Test
    fun `every bundled generate-document action uses the latest configuration version`() {
        val processLinks =
            resolver.getResources("classpath*:config/case/**/process-link/*.process-link.json")
        var generateActionCount = 0

        processLinks.forEach { resource ->
            val links = resource.inputStream.use { mapper.readTree(it) }
            links
                .filter {
                    it.path("pluginActionDefinitionKey").asText() ==
                        GENERATE_DOCUMENT_ACTION_KEY
                }.forEach { link ->
                    generateActionCount++
                    val description =
                        "${link.path("activityId").asText()} in ${resource.description}"
                    val properties = link.path("actionProperties")

                    assertThat(properties.path("actionConfigVersion").asInt(-1))
                        .describedAs("actionConfigVersion of %s", description)
                        .isEqualTo(GenerateDocumentActionConfigurationRegistry.LATEST_VERSION)
                    assertThat(properties.path("outputFormat").asText())
                        .describedAs("outputFormat of %s", description)
                        .isEqualTo("\"PDF\"")

                    val parsed =
                        GenerateDocumentActionConfigurationRegistry.parse(
                            properties as ObjectNode,
                        )
                    assertThat(parsed.version())
                        .describedAs("parsed action version of %s", description)
                        .isEqualTo(GenerateDocumentActionConfigurationRegistry.LATEST_VERSION)
                }
        }

        assertThat(generateActionCount)
            .describedAs("expected bundled generate-document actions on the classpath")
            .isPositive
    }

    companion object {
        private const val GENERATE_DOCUMENT_ACTION_KEY = "epistola-generate-document"
    }
}