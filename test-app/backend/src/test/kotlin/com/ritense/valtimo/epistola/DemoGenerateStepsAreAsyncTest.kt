// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every demo step that generates a letter runs in a job of its own (`asyncBefore`).
 *
 * A generation Epistola refuses raises an incident only behind such a boundary (ADR 0009, third
 * addendum): without it, the refusal goes back to whoever started the step, who gets an error while
 * the transaction rolls back, and no incident is raised. The demo is what installers copy, so it
 * models the boundary, and the admin usage overview flags a step without it.
 */
class DemoGenerateStepsAreAsyncTest {
    private val mapper = ObjectMapper()

    @Test
    fun `every demo generate step is marked asyncBefore`() {
        val resolver = PathMatchingResourcePatternResolver()
        val links = resolver.getResources("classpath*:config/case/*/*/process-link/*.process-link.json")
        assertThat(links).isNotEmpty

        val generateSteps =
            links.flatMap { resource ->
                val tree = resource.inputStream.use { mapper.readTree(it) }
                val bpmnName = resource.filename!!.removeSuffix(".process-link.json") + ".bpmn"
                val bpmn = resource.createRelative("../bpmn/$bpmnName")
                (if (tree.isArray) tree.toList() else listOf(tree))
                    .filter { it.path("pluginActionDefinitionKey").asText() in GENERATING_ACTIONS }
                    .map { link -> link.path("activityId").asText() to bpmn }
            }
        assertThat(generateSteps).isNotEmpty

        val withoutBoundary =
            generateSteps
                .filterNot { (activityId, bpmn) -> asyncBefore(bpmn.inputStream.use { it.readBytes() }, activityId) }
                .map { (activityId, bpmn) -> "${bpmn.filename}#$activityId" }
        assertThat(withoutBoundary).`as`("generate steps without asyncBefore").isEmpty()
    }

    private fun asyncBefore(
        bpmn: ByteArray,
        activityId: String,
    ): Boolean {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val elements = factory.newDocumentBuilder().parse(bpmn.inputStream()).getElementsByTagNameNS("*", "*")
        val activity =
            (0 until elements.length)
                .map { elements.item(it) as org.w3c.dom.Element }
                .firstOrNull { it.getAttribute("id") == activityId }
                ?: error("No activity $activityId")
        return ASYNC_NAMESPACES.any { activity.getAttributeNS(it, "asyncBefore") == "true" }
    }

    private companion object {
        val GENERATING_ACTIONS = setOf("epistola-generate-document", "epistola-generate-dynamic-document")
        val ASYNC_NAMESPACES = listOf("http://camunda.org/schema/1.0/bpmn", "http://operaton.org/schema/1.0/bpmn")
    }
}