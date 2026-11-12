// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2
package com.ritense.valtimo.epistola.evenement

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.processdocument.service.ValueResolverDelegateService
import com.ritense.valtimo.contract.annotation.ProcessBean
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * Adds the participant from the `evenement-aanmelden` start form to the event's `deelnemers` list.
 *
 * Valtimo can set a value on the case document but cannot append to a list in it, and a script task
 * is no way out (see `DemoScriptTasksTest`). So the whole list is read, extended and written back.
 *
 * Reads the process variables `naam`, `organisatie`, `email` and `dagdelen`. Form.io's select boxes
 * submit `dagdelen` as an object (`{"ochtend": true, "middag": false}`); a list of keys is accepted
 * too. `dagdelen` is written back as the list of chosen keys (`["ochtend"]`), so the invitation's
 * data mapping passes it on as it is and the template decides per daypart whether it was chosen.
 *
 * Demo-only, like [com.ritense.valtimo.epistola.documents.StoreGeneratedLetterDelegate].
 */
@ProcessBean
@Component("registerParticipant")
class RegisterParticipantDelegate(
    private val valueResolverDelegateService: ValueResolverDelegateService,
    private val objectMapper: ObjectMapper,
) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val dagdelen = chosenDayparts(execution.getVariable("dagdelen"))
        require(dagdelen.isNotEmpty()) { "A participant must choose at least one daypart" }

        val participant =
            linkedMapOf(
                "naam" to required(execution, "naam"),
                "organisatie" to required(execution, "organisatie"),
                "email" to required(execution, "email"),
                "dagdelen" to dagdelen,
                "aangemeldOp" to OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            )

        val existing = valueResolverDelegateService.resolveValue(execution, DEELNEMERS)
        val participants: List<Any?> = existing?.let { objectMapper.convertValue(it, List::class.java) } ?: emptyList()

        valueResolverDelegateService.handleValue(execution, DEELNEMERS, participants + participant)
        execution.setVariable("dagdelen", dagdelen)
    }

    private fun required(
        execution: DelegateExecution,
        name: String,
    ): String =
        (execution.getVariable(name) as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Process variable '$name' is required")

    companion object {
        const val DEELNEMERS = "doc:/deelnemers"

        /** The daypart keys a participant ticked, in the order the form lists them. */
        fun chosenDayparts(value: Any?): List<String> =
            when (value) {
                null -> emptyList()
                is Map<*, *> -> value.filterValues { it == true || it == "true" }.keys.map { it.toString() }
                is Collection<*> -> value.filterNotNull().map { it.toString() }
                is String -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                else -> throw IllegalArgumentException("Cannot read dayparts from ${value::class.simpleName}")
            }
    }
}