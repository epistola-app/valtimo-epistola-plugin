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

    @Test
    fun `document schema accepts the submission data that completeTask writes to doc-slash-submission`() {
        val flow = readJson("config/case/form-flow-demo/1.0.0/form-flow/generate-letter.form-flow.json")
        val completeTask =
            flow
                .path("steps")
                .flatMap { it.path("onComplete") }
                .map { it.asText() }
                .single { it.contains("completeTask") }

        // The two-argument overload defaults its save path to `doc:/submission`, so the completing
        // step's submission data lands on the document. A schema that rejects it fails the task.
        assertThat(completeTask).doesNotContain("{'doc:")

        val schema =
            readJson("config/case/form-flow-demo/1.0.0/document/definition/form-flow-demo.schema.document-definition.json")
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse()
        assertThat(schema.path("properties").has("submission"))
            .describedAs("document schema must permit the doc:/submission key written by completeTask")
            .isTrue()
    }

    @Test
    fun `the preview variant mirrors the baseline flow, differing only by the preview component`() {
        val baseline = readJson("$BASE/form-flow/generate-letter.form-flow.json")
        val preview = readJson("$BASE/form-flow/generate-letter-preview.form-flow.json")

        // Same shape: two steps, the first leading into the second, the second completing the task.
        assertThat(preview.path("steps").size()).isEqualTo(baseline.path("steps").size())
        assertThat(preview.path("steps")[1].path("onComplete")[0].asText())
            .describedAs("both flows must complete the task the same way, or the comparison is not like-for-like")
            .isEqualTo(baseline.path("steps")[1].path("onComplete")[0].asText())

        val baselineForm = readText("$BASE/form/generate-letter.form.json")
        val previewForm = readText("$BASE/form/generate-letter-preview.form.json")
        assertThat(baselineForm).doesNotContain("epistola")
        assertThat(previewForm.lowercase()).contains("epistola-document-preview")
    }

    @Test
    fun `the preview variant generates only after both user tasks, leaving the measured transition clean`() {
        val bpmn = readText("$BASE/bpmn/form-flow-demo-preview.bpmn")

        fun flowTarget(source: String) = Regex("""sourceRef="$source" targetRef="([^"]+)"""").find(bpmn)?.groupValues?.get(1)

        // The point of the variant is to isolate the preview. If document generation sat between the
        // two user tasks it would confound the very transition the investigation measures.
        assertThat(flowTarget("generate-letter-preview"))
            .describedAs("the form flow task must hand straight over to the follow-up task")
            .isEqualTo("follow-up-preview")
        assertThat(flowTarget("follow-up-preview"))
            .describedAs("generation belongs after the follow-up task, not before it")
            .isEqualTo("render-letter")
    }

    @Test
    fun `the step-2 variant puts the preview on the step whose onComplete finishes the task`() {
        val flow = readJson("$BASE/form-flow/generate-letter-preview-step2.form-flow.json")
        val steps = flow.path("steps")

        val completingStep = steps.single { it.path("onComplete").size() > 0 }
        assertThat(completingStep.path("key").asText()).isEqualTo("confirm-letter-with-preview")

        fun formOf(step: com.fasterxml.jackson.databind.JsonNode) =
            readText("$BASE/form/${step.path("type").path("properties").path("definition").asText()}.form.json")

        // The whole point of this variant: the preview is on screen — and possibly still loading —
        // when the task is completed. On the step-1 variant it is already gone by then.
        assertThat(formOf(completingStep))
            .describedAs("the completing step must carry the preview")
            .contains("epistola-document-preview")
        assertThat(formOf(steps[0]))
            .describedAs("the first step must stay preview-free, or the variants are not distinguishable")
            .doesNotContain("epistola-document-preview")
    }

    @Test
    fun `each preview variant targets a generate-document link inside its own process`() {
        listOf(
            "generate-letter-preview.form.json" to "form-flow-demo-preview",
            "confirm-letter-with-preview.form.json" to "form-flow-demo-preview-step2",
        ).forEach { (formFile, expectedProcess) ->
            val component =
                readJson("$BASE/form/$formFile")
                    .path("components")
                    .single { it.path("type").asText() == "epistola-document-preview" }

            // The preview derives the process instance from the task it is opened on, so a link in
            // another process definition would not resolve.
            assertThat(component.path("processDefinitionKey").asText())
                .describedAs("preview in %s must target its own process", formFile)
                .isEqualTo(expectedProcess)

            val links = readJson("$BASE/process-link/$expectedProcess.process-link.json")
            val target = links.single { it.path("activityId").asText() == component.path("sourceActivityId").asText() }
            assertThat(target.path("pluginActionDefinitionKey").asText()).isEqualTo("epistola-generate-document")

            assertThat(
                component.path("components").any {
                    it.path("properties").path("sourceKey").asText() == "epistola:taskId"
                },
            ).describedAs("the task-id carrier must be present in %s", formFile).isTrue()
        }
    }

    private fun readJson(path: String) = ClassPathResource(path).inputStream.use { mapper.readTree(it) }

    private fun readText(path: String) = ClassPathResource(path).inputStream.bufferedReader().use { it.readText() }

    private companion object {
        const val BASE = "config/case/form-flow-demo/1.0.0"
    }
}