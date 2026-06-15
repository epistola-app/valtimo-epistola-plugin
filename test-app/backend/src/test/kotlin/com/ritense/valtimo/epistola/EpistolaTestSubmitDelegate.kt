package com.ritense.valtimo.epistola

import app.epistola.valtimo.domain.EpistolaProcessVariables
import app.epistola.valtimo.service.completion.EpistolaMessageCorrelationService
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate

/**
 * Test delegate that mimics what `generate-document` writes at submit time, used to verify
 * per-branch correlation. Reads the branch's `testRequestId` / `testResultVar` (BPMN input params)
 * and writes the rich result (including `jobPath`) plus the jobPath-named locator, exactly as
 * `EpistolaPlugin.generateDocument`. The catch event pins its `epistolaWaitFor` token from
 * `${<resultVar>.jobPath}` (this delegate has no plugin process link, so the test BPMN sets the
 * input mapping explicitly).
 */
class EpistolaTestSubmitDelegate : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val requestId = execution.getVariable("testRequestId") as String
        val resultVar = execution.getVariable("testResultVar") as String

        val jobPath = EpistolaMessageCorrelationService.buildJobPath(TENANT, requestId)
        execution.setVariable(jobPath, resultVar) // locator: jobPath -> result variable name
        execution.setVariable(
            resultVar,
            linkedMapOf(
                EpistolaProcessVariables.RESULT_KEY_REQUEST_ID to requestId,
                EpistolaProcessVariables.RESULT_KEY_STATUS to "PENDING",
                EpistolaProcessVariables.RESULT_KEY_DOCUMENT_ID to null,
                EpistolaProcessVariables.RESULT_KEY_ERROR_MESSAGE to null,
                EpistolaProcessVariables.RESULT_KEY_JOB_PATH to jobPath,
            ),
        )
    }

    companion object {
        const val TENANT = "demo"
    }
}