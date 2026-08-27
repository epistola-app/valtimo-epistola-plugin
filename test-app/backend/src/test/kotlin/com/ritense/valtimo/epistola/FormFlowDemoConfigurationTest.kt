/*
 * Copyright 2026 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-licence.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package com.ritense.valtimo.epistola

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

/**
 * Guards the standalone Form Flow demo's baseline shape.
 *
 * The customer investigation needs a preview-free path that exercises Valtimo's Form Flow task
 * transition. Keep this fixture independent of Epistola components; a later preview variant can
 * be compared against it without changing the BPMN/Form Flow hand-off.
 */
class FormFlowDemoConfigurationTest {
    private val mapper = ObjectMapper()

    @Test
    fun `form flow completes the generate-letter task after its confirmation step`() {
        val flow = readJson("config/case/form-flow-demo/1.0.0/form-flow/generate-letter.form-flow.json")
        val steps = flow.path("steps").associateBy { it.path("key").asText() }

        assertThat(flow.path("startStep").asText()).isEqualTo("enter-letter-details")
        assertThat(steps).containsKeys("enter-letter-details", "confirm-letter")
        assertThat(
            steps
                .getValue("enter-letter-details")
                .path("nextSteps")[0]
                .path("step")
                .asText(),
        ).isEqualTo("confirm-letter")
        assertThat(steps.getValue("confirm-letter").path("onComplete")[0].asText())
            .isEqualTo("${'$'}{valtimoFormFlow.completeTask(additionalProperties, step.submissionData)}")
    }

    @Test
    fun `generate-letter is a form-flow process link and follow-up remains a normal form task`() {
        val links =
            readJson("config/case/form-flow-demo/1.0.0/process-link/form-flow-demo.process-link.json")
                .associateBy { it.path("activityId").asText() }

        assertThat(links.getValue("generate-letter").path("processLinkType").asText()).isEqualTo("form-flow")
        assertThat(links.getValue("generate-letter").path("formFlowDefinitionKey").asText())
            .isEqualTo("generate-letter")
        assertThat(links.getValue("follow-up").path("processLinkType").asText()).isEqualTo("form")
        assertThat(links.getValue("follow-up").path("formDefinitionName").asText())
            .isEqualTo("form-flow-follow-up")
    }

    @Test
    fun `baseline forms contain no Epistola component or preview request`() {
        listOf(
            "form-flow-demo-start.form.json",
            "generate-letter.form.json",
            "confirm-letter.form.json",
            "form-flow-follow-up.form.json",
        ).forEach { form ->
            val content =
                ClassPathResource("config/case/form-flow-demo/1.0.0/form/$form")
                    .inputStream
                    .bufferedReader()
                    .use { it.readText() }

            assertThat(content.lowercase())
                .describedAs("Form Flow baseline form %s must not contain Epistola behaviour", form)
                .doesNotContain("epistola")
        }
    }

    private fun readJson(path: String) = ClassPathResource(path).inputStream.use { mapper.readTree(it) }
}