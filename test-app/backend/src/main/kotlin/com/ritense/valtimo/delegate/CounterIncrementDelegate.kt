package com.ritense.valtimo.delegate

import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.springframework.stereotype.Component

/**
 * Atomically increments a counter process variable.
 *
 * Used in multi-instance subprocesses where parallel iterations need to update
 * shared counters (e.g. successCount, failCount) without race conditions.
 *
 * Usage in BPMN: `camunda:delegateExpression="${counterIncrement}"`
 * with input parameter `counterVariable` set to the variable name to increment.
 */
@Component("counterIncrement")
class CounterIncrementDelegate : JavaDelegate {

    override fun execute(execution: DelegateExecution) {
        val varName = execution.getVariable("counterVariable") as? String
            ?: throw IllegalArgumentException("Input parameter 'counterVariable' is required")

        // Synchronize on the process instance ID to prevent concurrent modification
        // when multiple parallel multi-instance iterations complete simultaneously
        synchronized(execution.processInstanceId.intern()) {
            val current = (execution.getVariable(varName) as? Number)?.toInt() ?: 0
            execution.setVariable(varName, current + 1)
        }
    }
}
