// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training

import app.epistola.valtimo.service.EpistolaService
import com.ritense.case.service.CaseDefinitionService
import com.ritense.case_.repository.CaseDefinitionRepository
import com.ritense.plugin.service.PluginService
import com.ritense.valtimo.Application
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * Boots the real test-app (with the `training` profile active) against Testcontainers
 * Postgres + Keycloak and drives [TraineeDossierProvisioningService] directly — proving the
 * export/import/rename/finalize sequence (see [TraineeDossierProvisioner]) actually produces a
 * working, isolated clone of `form-flow-demo`, not just that it compiles against Valtimo's API
 * surface.
 *
 * [EpistolaService] is mocked (same reason as [FormFlowTransitionE2ETest]) — this test is about
 * the cloning mechanism, not document generation. [EpistolaTenantProvisioner] is mocked because
 * that call is explicitly out of scope (see its KDoc) — a real implementation lives outside this
 * repo.
 */
@SpringBootTest(classes = [Application::class])
@ActiveProfiles("test", "training")
class TraineeDossierProvisioningE2ETest {
    @MockitoBean
    lateinit var epistolaService: EpistolaService

    @MockitoBean
    lateinit var epistolaTenantProvisioner: EpistolaTenantProvisioner

    @Autowired
    lateinit var provisioningService: TraineeDossierProvisioningService

    @Autowired
    lateinit var caseDefinitionRepository: CaseDefinitionRepository

    @Autowired
    lateinit var caseDefinitionService: CaseDefinitionService

    @Autowired
    lateinit var pluginService: PluginService

    @Test
    fun `provisions an isolated, finalized dossier per trainee`() {
        whenever(epistolaTenantProvisioner.ensureTenant("11111111-1111-4111-8111-111111111111"))
            .thenReturn(EpistolaTenantCredentials(tenantId = "trainee-a", apiKey = "epk_test_a"))
        whenever(epistolaTenantProvisioner.ensureTenant("22222222-2222-4222-8222-222222222222"))
            .thenReturn(EpistolaTenantCredentials(tenantId = "trainee-b", apiKey = "epk_test_b"))

        val dossierA = provisioningService.ensureDossier("11111111-1111-4111-8111-111111111111")
        val dossierB = provisioningService.ensureDossier("22222222-2222-4222-8222-222222222222")

        assertThat(dossierA.key).isNotEqualTo(dossierB.key)

        val caseDefinitionA = caseDefinitionService.findCaseDefinition(dossierA)!!
        assertThat(caseDefinitionA.`final`).describedAs("a trainee's dossier must be finalized").isTrue()

        val pluginConfigurationA =
            pluginService.getPluginConfiguration(
                TraineeKeys.pluginConfigurationId("11111111-1111-4111-8111-111111111111"),
            )
        assertThat(pluginConfigurationA.properties?.get("tenantId")?.asText()).isEqualTo("trainee-a")

        val pluginConfigurationB =
            pluginService.getPluginConfiguration(
                TraineeKeys.pluginConfigurationId("22222222-2222-4222-8222-222222222222"),
            )
        assertThat(pluginConfigurationB.properties?.get("tenantId")?.asText()).isEqualTo("trainee-b")
    }

    @Test
    fun `re-provisioning the same trainee is a no-op`() {
        whenever(epistolaTenantProvisioner.ensureTenant("33333333-3333-4333-8333-333333333333"))
            .thenReturn(EpistolaTenantCredentials(tenantId = "trainee-c", apiKey = "epk_test_c"))

        val first = provisioningService.ensureDossier("33333333-3333-4333-8333-333333333333")
        val second = provisioningService.ensureDossier("33333333-3333-4333-8333-333333333333")

        assertThat(second).isEqualTo(first)
        assertThat(caseDefinitionRepository.findAllByIdKeyOrderByIdVersionTagDesc(first.key)).hasSize(1)
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
        }
    }
}