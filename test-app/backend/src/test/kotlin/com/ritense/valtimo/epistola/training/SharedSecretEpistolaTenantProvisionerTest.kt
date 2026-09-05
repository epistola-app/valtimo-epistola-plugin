// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClientResponseException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * A plain (no Spring context) test against a tiny embedded HTTP server — fast and deterministic,
 * unlike a real epistola-suite instance, while still exercising the real wire behaviour of
 * [SharedSecretEpistolaTenantProvisioner]'s HTTP calls rather than mocking its own class.
 */
class SharedSecretEpistolaTenantProvisionerTest {
    private lateinit var server: HttpServer
    private var lastRequestBody: String? = null
    private var lastAuthorizationHeader: String? = null
    private var responseStatus = 201

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/api/tenants") { exchange ->
            lastRequestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            lastAuthorizationHeader = exchange.requestHeaders.getFirst("Authorization")
            val body =
                if (responseStatus in
                    200..299
                ) {
                    "{\"id\":\"trainee-x\",\"name\":\"n\",\"createdAt\":\"2026-01-01T00:00:00Z\"}"
                } else {
                    "{}"
                }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun provisioner(): SharedSecretEpistolaTenantProvisioner =
        SharedSecretEpistolaTenantProvisioner(
            baseUrl = "http://localhost:${server.address.port}/api",
            sharedSecret = "epk_test_shared_secret_at_least_32_chars_long",
        )

    @Test
    fun `creates a tenant and returns the shared secret as the api key`() {
        // Deliberately an email, not a UUID — the tenant id must survive an identity shape that
        // is not a valid Epistola tenant-id slug on its own (this exact input crashed provisioning
        // against a real Epistola instance before TraineeKeys.caseDefinitionKey started hashing).
        val credentials = provisioner().ensureTenant("trainee1@demo")

        val expectedTenantId = "trainee-" + TraineeKeys.caseDefinitionKey("trainee1@demo")
        assertThat(credentials.tenantId).isEqualTo(expectedTenantId)
        assertThat(credentials.tenantId).matches("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
        assertThat(credentials.apiKey).isEqualTo("epk_test_shared_secret_at_least_32_chars_long")
        assertThat(lastAuthorizationHeader).isEqualTo("ApiKey epk_test_shared_secret_at_least_32_chars_long")
        assertThat(lastRequestBody).contains("\"id\":\"$expectedTenantId\"")
    }

    @Test
    fun `treats an already-existing tenant as success, not a failure`() {
        responseStatus = 409

        val credentials = provisioner().ensureTenant("trainee2@demo")

        assertThat(credentials.tenantId).isEqualTo("trainee-" + TraineeKeys.caseDefinitionKey("trainee2@demo"))
    }

    @Test
    fun `propagates a real failure instead of swallowing it`() {
        responseStatus = 500

        assertThrows(RestClientResponseException::class.java) {
            provisioner().ensureTenant("33333333-3333-4333-8333-333333333333")
        }
    }
}