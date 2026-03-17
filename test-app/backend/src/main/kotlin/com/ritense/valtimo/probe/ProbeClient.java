package com.ritense.valtimo.probe;

// Probing which batch-related classes exist in the Epistola client
import app.epistola.client.model.GenerateBatchRequest;

class ProbeJavaClient {
    void probe() {
        // Just checking compilation - batch types available
        GenerateBatchRequest request = new GenerateBatchRequest();
    }
}
