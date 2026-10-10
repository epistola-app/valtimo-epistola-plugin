// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2
package com.ritense.valtimo.epistola

import app.epistola.valtimo.composer.ComposerSchema
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Guards the shape of every bundled letter composer.
 *
 * The one that matters is `prefill: false`. A composer's key is a `pv:` one, so the chosen letter
 * becomes a process variable on submit — but Valtimo resolves a `pv:` key against the case's
 * process instances when it prefills, and once a dossier has more than one instance holding that
 * variable it cannot pick and fails the whole form with a 500. There is nothing to prefill either:
 * the letter is what the employee is about to choose. Only opening a form on a case that has run
 * the process twice reveals this, which is exactly what a fixture test can pin down instead.
 */
class BundledComposerFormTest {
    private val mapper = ObjectMapper()
    private val resolver = PathMatchingResourcePatternResolver()

    @Test
    fun `every bundled letter composer opts out of prefill and names where its letter goes`() {
        val forms = resolver.getResources("classpath*:config/case/**/form/*.form.json")
        var composers = 0

        forms.forEach { resource ->
            val form = resource.inputStream.use { mapper.readTree(it) }
            composers +=
                form.path("components").composers().count { composer ->
                    val where = "${composer.path("key").asText()} in ${resource.description}"

                    assertThat(composer.path("prefill").asBoolean(true))
                        .describedAs("prefill of %s", where)
                        .isFalse()
                    assertThat(composer.path("key").asText())
                        .describedAs("key of %s", where)
                        .startsWith("pv:")
                    // The bundled forms are what an author copies, so they carry what a
                    // builder-saved form carries: the settings under the one key this plugin
                    // claims, and a version it can actually read.
                    val settings = composer.path(ComposerSchema.COMPONENT_NAMESPACE)
                    assertThat(settings.isObject)
                        .describedAs("settings namespace of %s", where)
                        .isTrue()
                    assertThat(settings.path(ComposerSchema.COMPONENT_FIELD).asInt(0))
                        .describedAs("schemaVersion of %s", where)
                        .isBetween(1, ComposerSchema.CURRENT)
                    true
                }
        }

        assertThat(composers)
            .describedAs("expected bundled letter composers on the classpath")
            .isPositive
    }

    /**
     * Every write-back rule a bundled form declares is one the plugin will actually apply.
     *
     * A rule is dropped at runtime, with a warning nobody reads, if its destination has no resolver
     * prefix or its expression is missing — so a bundled form carrying one would demonstrate
     * nothing while looking configured. Checked here because the rules are JSON: no compiler sees
     * them, and the failure is a value quietly not arriving in a case.
     *
     * Note what is *not* checked: whether a `doc:` destination exists in the case schema. That
     * needs the document definition, and the demo deliberately writes to `pv:` destinations for
     * exactly that reason — a case with `additionalProperties: false` refuses an undeclared path.
     */
    @Test
    fun `a bundled write-back rule is one the plugin can apply`() {
        var rules = 0

        resolver.getResources("classpath*:config/case/**/form/*.form.json").forEach { resource ->
            val form = resource.inputStream.use { mapper.readTree(it) }
            form.path("components").composers().forEach { composer ->
                val writeBack = composer.path(ComposerSchema.COMPONENT_NAMESPACE).path("writeBack")
                if (!writeBack.isObject) return@forEach
                writeBack.fields().forEach { (destination, expression) ->
                    val where = "$destination on ${composer.path("key").asText()} in ${resource.description}"
                    assertThat(destination)
                        .describedAs("destination of %s", where)
                        .matches("^(doc:/.*|pv:[A-Za-z_][A-Za-z0-9_]*)$")
                    assertThat(expression.asText(""))
                        .describedAs("expression of %s", where)
                        .isNotBlank()
                    rules++
                }
            }
        }

        assertThat(rules)
            .describedAs("expected at least one bundled write-back rule, so the feature is demonstrated")
            .isPositive
    }

    /**
     * A composer inside a form flow needs two things spelled out, and neither is a compile error.
     *
     * A flow does not resolve `pv:` keys the way an ordinary form does: `completeTask`'s
     * two-argument overload writes the whole submission to one path and resolves nothing, so the
     * variable the generate task reads has to be named in the three-argument overload's mapping.
     * And only the *completing* step's submission is mapped, so a composer on an earlier step needs
     * a hidden same-key carrier on the completing step to reach it.
     *
     * Miss either and the demo still looks right — the letter is offered, previewed and submitted —
     * and the last step answers 500 with "No composed letter on process variable". Both were shipped
     * that way, and only walking the flow in a browser found them.
     */
    @Test
    fun `a composer in a form flow can reach the process variable the generate task reads`() {
        var checked = 0

        resolver.getResources("classpath*:config/case/**/form-flow/*.form-flow.json").forEach { flowResource ->
            val flow = flowResource.inputStream.use { mapper.readTree(it) }
            val case = caseOf(flowResource.uri.toString())
            val steps = flow.path("steps").toList()
            val completing =
                steps.lastOrNull { step ->
                    step.path("onComplete").any { it.asText().contains("completeTask") }
                } ?: return@forEach
            val onComplete = completing.path("onComplete").joinToString(" ") { it.asText() }
            val completingKeys = formFor(case, completing)?.path("components")?.inputKeys() ?: emptySet()

            steps.forEach { step ->
                val form = formFor(case, step) ?: return@forEach
                form.path("components").composers().forEach { composer ->
                    val key = composer.path("key").asText()
                    val where = "$key in ${flowResource.description}"
                    checked++

                    assertThat(onComplete)
                        .describedAs(
                            "the completing step of %s must map %s onto the process variable — a form " +
                                "flow resolves no pv: key on its own, so completeTask needs the " +
                                "three-argument mapping",
                            where,
                            key,
                        ).contains("'$key':'/$key'")

                    if (step != completing) {
                        assertThat(completingKeys)
                            .describedAs(
                                "the completing step of %s must re-declare %s as a hidden carrier — only " +
                                    "its own submission data is mapped, so a letter chosen earlier never " +
                                    "reaches the mapping",
                                where,
                                key,
                            ).contains(key)
                    }
                }
            }
        }

        assertThat(checked)
            .describedAs("expected a bundled form flow carrying a letter composer")
            .isPositive
    }

    /** The case a bundled resource belongs to; form names are unique only within one. */
    private fun caseOf(uri: String): String = Regex("config/case/([^/]+)/").find(uri)!!.groupValues[1]

    /** The form a flow step renders, resolved the way Valtimo resolves it: by name, within the case. */
    private fun formFor(
        case: String,
        step: JsonNode,
    ): JsonNode? {
        val name =
            step
                .path("type")
                .path("properties")
                .path("definition")
                .asText()
        if (name.isEmpty()) {
            return null
        }
        return resolver
            .getResources("classpath*:config/case/$case/**/form/$name.form.json")
            .firstOrNull()
            ?.inputStream
            ?.use { mapper.readTree(it) }
    }

    /** Every input key in a component tree, whatever nests it. */
    private fun JsonNode.inputKeys(): Set<String> =
        flatMap { component ->
            component.path("components").inputKeys() +
                if (component.path("input").asBoolean(false)) setOf(component.path("key").asText()) else emptySet()
        }.toSet()

    /** Every letter composer in a component tree, however deeply a layout component nests it. */
    private fun JsonNode.composers(): List<JsonNode> =
        flatMap { component ->
            val nested = component.path("components").composers()
            if (component.path("type").asText() == "epistola-letter-composer") {
                listOf(component) + nested
            } else {
                nested
            }
        }
}