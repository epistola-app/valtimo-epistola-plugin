// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2
package com.ritense.valtimo.epistola

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
                    true
                }
        }

        assertThat(composers)
            .describedAs("expected bundled letter composers on the classpath")
            .isPositive
    }

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