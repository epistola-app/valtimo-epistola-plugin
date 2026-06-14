package com.ritense.valtimo.epistola

import app.epistola.valtimo.domain.EpistolaProcessVariables
import app.epistola.valtimo.service.completion.EpistolaMessageCorrelationService
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate

/**
 * Test delegate that mimics what `generate-document` writes at submit time, used to verify
 * per-branch correlation. Reads the branch's `testRequestId` / `testResultVar` (BPMN input params)
 * and writes the activity-named jobPath variable plus the jobPath-named locator (exactly as
 * `EpistolaPlugin.generateDocument`), plus a PENDING rich result on the configured result variable.
 * The catch-event parse listener then pins the per-branch `epistolaWaitFor` token from these.
 */
class EpistolaTestSubmitDelegate : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val requestId = execution.getVariable("testRequestId") as String
        val resultVar = execution.getVariable("testResultVar") as String

        val activityId = execution.currentActivityId
        val jobPath = EpistolaMessageCorrelationService.buildJobPath(TENANT, requestId)
        execution.setVariable(EpistolaProcessVariables.activityJobPathVariable(activityId), jobPath)
        execution.setVariable(jobPath, resultVar)
        execution.setVariable(
            resultVar,
            linkedMapOf(
                EpistolaProcessVariables.RESULT_KEY_REQUEST_ID to requestId,
                EpistolaProcessVariables.RESULT_KEY_STATUS to "PENDING",
                EpistolaProcessVariables.RESULT_KEY_DOCUMENT_ID to null,
                EpistolaProcessVariables.RESULT_KEY_ERROR_MESSAGE to null,
            ),
        )
    }

    companion object {
        const val TENANT = "demo"
    }
}