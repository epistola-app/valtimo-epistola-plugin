// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

/**
 * Guards the shape of the **Form Flow voorbeeld** demo case.
 *
 * The case exists to exercise a Valtimo Form Flow whose confirmation step carries an Epistola
 * document preview — the arrangement where the preview is on screen, and possibly still loading, at
 * the moment the user completes the task. These are static shape checks; that the flow actually
 * advances is covered by [FormFlowTransitionE2ETest] and by the Playwright suite.
 */
class FormFlowDemoConfigurationTest {
    private val mapper = ObjectMapper()

    @Test
    fun `the confirmation step completes the task and is the step that carries the preview`() {
        val flow = readJson("$BASE/form-flow/generate-letter.form-flow.json")
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

        val completing = steps.getValue("confirm-letter")
        assertThat(completing.path("onComplete")[0].asText())
            .isEqualTo("${'$'}{valtimoFormFlow.completeTask(additionalProperties, step.submissionData)}")

        // The preview belongs on the completing step: that is the only placement where a preview
        // request can still be in flight when the task completes.
        assertThat(formTextOf(completing)).contains("epistola-document-preview")
        assertThat(formTextOf(steps.getValue("enter-letter-details")))
            .describedAs("the input step stays preview-free, so the preview's effect is attributable")
            .doesNotContain("epistola-document-preview")
    }

    @Test
    fun `document schema accepts the submission data that completeTask writes to doc-slash-submission`() {
        // The two-argument completeTask overload defaults its save path to `doc:/submission`, so the
        // completing step's submission data lands on the document. A schema that rejects it makes the
        // onComplete expression throw and the task never completes.
        val completeTask =
            readJson("$BASE/form-flow/generate-letter.form-flow.json")
                .path("steps")
                .flatMap { it.path("onComplete") }
                .map { it.asText() }
                .single { it.contains("completeTask") }
        assertThat(completeTask).doesNotContain("{'doc:")

        val schema = readJson("$BASE/document/definition/form-flow-demo.schema.document-definition.json")
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse()
        assertThat(schema.path("properties").has("submission"))
            .describedAs("document schema must permit the doc:/submission key written by completeTask")
            .isTrue()
    }

    @Test
    fun `the preview targets a generate-document link that runs after both user tasks`() {
        val component = previewComponent()
        assertThat(component.path("processDefinitionKey").asText()).isEqualTo("form-flow-demo")

        val links = readJson("$BASE/process-link/form-flow-demo.process-link.json")
        val target = links.single { it.path("activityId").asText() == component.path("sourceActivityId").asText() }
        assertThat(target.path("pluginActionDefinitionKey").asText()).isEqualTo("epistola-generate-document")

        // Generation sits after the follow-up task on purpose: between the user tasks it would add
        // latency and correlation to the very transition this fixture exists to observe.
        val bpmn = readText("$BASE/bpmn/form-flow-demo.bpmn")

        fun flowTarget(source: String) = Regex("""sourceRef="$source" targetRef="([^"]+)"""").find(bpmn)?.groupValues?.get(1)
        assertThat(flowTarget("generate-letter")).isEqualTo("follow-up")
        assertThat(flowTarget("follow-up")).isEqualTo("render-letter")
    }

    @Test
    fun `the preview reads an earlier step's field through a re-declared carrier`() {
        val component = previewComponent()
        val referenced =
            Regex("""\${'$'}form\.(\w+)""")
                .findAll(component.path("overrideMapping").asText())
                .map { it.groupValues[1] }
                .toList()
        assertThat(referenced).describedAs("the preview should reflect what was typed").isNotEmpty()

        // `$form` is the *current* step's form data. Valtimo carries an earlier step's value across
        // only by prefilling a component on this step that re-declares the same key, so every
        // $form.<key> must exist both here and on the step that submits it. Rename either and the
        // preview silently falls back to the case document with nothing failing.
        val confirmKeys = formOf("confirm-letter").path("components").map { it.path("key").asText() }
        val inputKeys = formOf("generate-letter").path("components").map { it.path("key").asText() }
        referenced.forEach { key ->
            assertThat(confirmKeys).describedAs("confirmation step needs a carrier for %s", key).contains(key)
            assertThat(inputKeys)
                .describedAs("carrier %s is only filled if an earlier step submits it", key)
                .contains(key)
        }
    }

    @Test
    fun `every task-bound Epistola component carries the task-id carrier`() {
        // Hand-authored form JSON does not get the carrier from the builder wrapper, so it has to be
        // written out. Without it the component fails closed. See docs/formio-components.md.
        assertThat(
            previewComponent().path("components").any {
                it.path("properties").path("sourceKey").asText() == "epistola:taskId"
            },
        ).isTrue()
    }

    private fun previewComponent() =
        formOf("confirm-letter").path("components").single { it.path("type").asText() == "epistola-document-preview" }

    private fun formOf(name: String) = readJson("$BASE/form/$name.form.json")

    private fun formTextOf(step: com.fasterxml.jackson.databind.JsonNode) =
        readText("$BASE/form/${step.path("type").path("properties").path("definition").asText()}.form.json")

    private fun readJson(path: String) = ClassPathResource(path).inputStream.use { mapper.readTree(it) }

    private fun readText(path: String) = ClassPathResource(path).inputStream.bufferedReader().use { it.readText() }

    private companion object {
        const val BASE = "config/case/form-flow-demo/1.0.0"
    }
}