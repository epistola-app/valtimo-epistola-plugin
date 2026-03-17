package com.ritense.valtimo.probe

import app.epistola.client.model.GenerateBatchRequest

/** Kotlin compilation probe for batch contract types. */
class ProbeClient {
    fun probe() {
        val request = GenerateBatchRequest(items = emptyList())
    }
}