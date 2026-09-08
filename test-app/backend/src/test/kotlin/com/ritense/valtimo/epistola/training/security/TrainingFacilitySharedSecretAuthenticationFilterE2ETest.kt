// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import app.epistola.valtimo.service.EpistolaService
import com.ritense.valtimo.Application
import com.ritense.valtimo.epistola.training.EpistolaTenantProvisioner
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

private const val SECRET = "e2e-test-only-training-facility-secret"

/**
 * The unit test for [TrainingFacilitySharedSecretAuthenticationFilter] mocks the request/response
 * and calls `doFilter` directly — it proves the filter's own logic, not that a real request
 * actually gets past Valtimo's real `authorizeHttpRequests` gate once this filter sets the
 * `SecurityContext`. Every other E2E test in this package calls controller/service beans directly
 * (`@Autowired`), bypassing the servlet filter chain entirely — none of them exercise Spring
 * Security at all. This one does: `@AutoConfigureMockMvc` wires `MockMvc` against the real,
 * registered filter chain (Spring Security included, unlike a bare unit test), so a request routed
 * through it either does or does not pass the same `ROLE_ADMIN` gate a real HTTP client would hit.
 */
@SpringBootTest(classes = [Application::class])
@AutoConfigureMockMvc
@ActiveProfiles("test", "training")
class TrainingFacilitySharedSecretAuthenticationFilterE2ETest {
    @MockitoBean
    lateinit var epistolaService: EpistolaService

    @MockitoBean
    lateinit var epistolaTenantProvisioner: EpistolaTenantProvisioner

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `the correct secret reaches a real ROLE_ADMIN-gated endpoint`() {
        mockMvc
            .perform(
                get("/api/management/v1/case-definition").header(TrainingFacilitySharedSecretAuthenticationFilter.SECRET_HEADER, SECRET),
            ).andExpect(status().isOk)
    }

    @Test
    fun `no header is rejected by the real gate, not just left unauthenticated`() {
        mockMvc
            .perform(get("/api/management/v1/case-definition"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `the wrong secret is rejected by the real gate`() {
        mockMvc
            .perform(
                get(
                    "/api/management/v1/case-definition",
                ).header(TrainingFacilitySharedSecretAuthenticationFilter.SECRET_HEADER, "not-the-secret"),
            ).andExpect(status().isForbidden)
    }

    companion object {
        @JvmStatic
        private val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @JvmStatic
        private val keycloak =
            GenericContainer<Nothing>(DockerImageName.parse("quay.io/keycloak/keycloak:26.1"))
                .apply {
                    withExposedPorts(8080)
                    withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                    withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                    withCommand("start-dev")
                    waitingFor(Wait.forLogMessage(".*Listening on:.*", 1).withStartupTimeout(Duration.ofMinutes(3)))
                    start()
                }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            val issuer = "http://127.0.0.1:${keycloak.getMappedPort(8080)}/realms/master"
            registry.add("spring.security.oauth2.client.provider.keycloakjwt.issuer-uri") { issuer }
            registry.add("spring.security.oauth2.client.provider.keycloakapi.issuer-uri") { issuer }
            registry.add("epistola.training.facility-shared-secret") { SECRET }
        }
    }
}