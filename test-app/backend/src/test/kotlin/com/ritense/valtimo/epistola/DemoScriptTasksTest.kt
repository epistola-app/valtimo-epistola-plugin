// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * No demo process has a script task.
 *
 * The bulk demo had one, and it never ran: it declared its language as `camunda:scriptFormat`,
 * which the engine does not read, so the engine fell back to JUEL and evaluated the script as a
 * string literal. The task completed and did nothing, and the failure surfaced one step later, at a
 * variable it should have set. A script task cannot do much in Valtimo either way: there is no
 * Groovy engine, and the JavaScript whitelist allows a handful of classes. A delegate is the way to
 * run code in a demo process.
 */
class DemoScriptTasksTest {
    @Test
    fun `no demo process has a script task`() {
        val bpmns = PathMatchingResourcePatternResolver().getResources("classpath*:config/case/*/*/bpmn/*.bpmn")
        assertThat(bpmns).isNotEmpty

        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val scriptTasks =
            bpmns.flatMap { bpmn ->
                val elements = bpmn.inputStream.use { factory.newDocumentBuilder().parse(it) }.getElementsByTagNameNS(BPMN, "scriptTask")
                (0 until elements.length).map { "${bpmn.filename}#${(elements.item(it) as Element).getAttribute("id")}" }
            }
        assertThat(scriptTasks).`as`("script tasks in the demo").isEmpty()
    }

    private companion object {
        const val BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL"
    }
}