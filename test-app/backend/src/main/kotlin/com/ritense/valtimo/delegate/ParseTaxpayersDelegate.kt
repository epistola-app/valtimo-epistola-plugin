// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.delegate

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.valtimo.contract.annotation.ProcessBean
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.springframework.stereotype.Component

/**
 * Reads the bulk demo's `taxpayersJson` into the list its multi-instance iterates (`taxpayers`),
 * and starts the counters at zero.
 *
 * This was a script task, and it never ran. It declared its language as `camunda:scriptFormat`,
 * which the engine does not read (`scriptFormat` is a plain BPMN attribute), so it fell back to
 * JUEL, where text without `${…}` is a string literal: the task completed and set nothing, and the
 * multi-instance then failed on `${taxpayers}`. Spelt correctly it still could not work: Valtimo
 * has no Groovy engine, and its JavaScript whitelist (`AllowedClassesScriptEngineResolver`) offers
 * no way to build the Java maps the engine stores and the mapping reads.
 *
 * Usage in BPMN: `camunda:delegateExpression="${parseTaxpayers}"`. [ProcessBean] is what makes the
 * expression resolve (see [CounterIncrementDelegate]).
 */
@ProcessBean
@Component("parseTaxpayers")
class ParseTaxpayersDelegate(
    private val objectMapper: ObjectMapper,
) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val json =
            execution.getVariable(SOURCE) as? String
                ?: throw IllegalArgumentException("Process variable '$SOURCE' is required and must be a JSON array")
        val taxpayers =
            try {
                objectMapper.readValue(json, LIST_OF_MAPS)
            } catch (e: JsonProcessingException) {
                // Not the parser's message: it quotes the input, which names people.
                throw IllegalArgumentException("Process variable '$SOURCE' is not a JSON array of objects (${e.javaClass.simpleName})")
            }

        execution.setVariable("taxpayers", ArrayList(taxpayers))
        execution.setVariable("totalCount", taxpayers.size)
        execution.setVariable("successCount", 0)
        execution.setVariable("failCount", 0)
    }

    private companion object {
        const val SOURCE = "taxpayersJson"
        val LIST_OF_MAPS = object : TypeReference<List<LinkedHashMap<String, Any?>>>() {}
    }
}