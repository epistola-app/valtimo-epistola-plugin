package com.ritense.valtimo.epistola

import app.epistola.valtimo.service.download.ProcessVariableStorageStrategy
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate

/**
 * Test service-task delegate standing in for `download-document`. Invoked via `camunda:class` (no
 * Spring bean resolution — Valtimo whitelists expression beans) and stores an 8 KB document (whose
 * Base64 would exceed Operaton's varchar(4000)) via the real [ProcessVariableStorageStrategy].
 */
class EpistolaTestStoreDelegate : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val content = ByteArray(8192) { (it % 256).toByte() }
        ProcessVariableStorageStrategy().store(execution, "test-doc", content, OUTPUT_VARIABLE)
    }

    companion object {
        const val OUTPUT_VARIABLE = "documentContent"
    }
}