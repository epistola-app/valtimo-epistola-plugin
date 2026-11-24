// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola

import app.epistola.valtimo.composer.ComposerConfigurationResolver
import app.epistola.valtimo.composer.ComposerSubmissionListener
import app.epistola.valtimo.composer.ComposerWriteBackService
import app.epistola.valtimo.mapping.JsonataMappingService
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.authorization.AuthorizationContext.Companion.runWithoutAuthorization
import com.ritense.document.domain.impl.JsonSchemaDocument
import com.ritense.document.domain.impl.JsonSchemaDocumentDefinitionId
import com.ritense.document.service.DocumentService
import com.ritense.valtimo.Application
import com.ritense.valtimo.contract.buildingblock.BuildingBlockDefinitionId
import com.ritense.valtimo.contract.case_.CaseDefinitionId
import com.ritense.valtimo.contract.event.TaskCompletedEvent
import com.ritense.valueresolver.ValueResolverService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.operaton.bpm.engine.RepositoryService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

/**
 * Completing a task must not fail because of the letter composer when no composer is on that
 * task's form (#246).
 *
 * The composer's write-back listens to [TaskCompletedEvent], which Valtimo publishes **inside the
 * task submission's transaction**. When no composer is scoped to the task's process, write-back
 * reads every composer on the case type. It used to find that case type by looking the document's
 * definition name up with Valtimo's `getActiveCaseDefinition`, which is `@Transactional` and throws
 * for a building block's key (not a case type) and for a case type the user may not view. The
 * plugin caught the throw, but Spring had already marked the submission's transaction
 * rollback-only, so the commit failed (`UnexpectedRollbackException`, a 500, and the task stayed
 * open).
 *
 * Why this needs the real application, and not a unit test: the failure is the interaction of
 * Valtimo's transactional proxy, its PBAC check and the real transaction manager, and a mocked
 * lookup reproduces none of them. Everything here is the real bean except the document read, which
 * returns a document shaped by Valtimo's own factories — the test-app ships no building block.
 * Each test fails with `UnexpectedRollbackException` against the code before the fix.
 */
@SpringBootTest(classes = [Application::class])
@ActiveProfiles("test")
class ComposerSubmissionTransactionE2ETest {
    @Autowired
    lateinit var resolver: ComposerConfigurationResolver

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var jsonataMappingService: JsonataMappingService

    @Autowired
    lateinit var valueResolverService: ValueResolverService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var repositoryService: RepositoryService

    @AfterEach
    fun clearUser() = SecurityContextHolder.clearContext()

    @Test
    fun `a building block's task with no composer on its form completes`() {
        val listener = listenerReading(definitionId = buildingBlockDocument())

        for (roles in listOf(CASE_WORKER, ADMIN)) {
            actAs(roles)
            assertThatCode { completeTaskInTransaction(listener) }
                .describedAs("submitting as %s", roles.toList())
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `a case worker who may not view the case type still completes their task`() {
        // ROLE_USER holds no CaseDefinition:VIEW in the test-app, so the old lookup was refused —
        // and the refusal, caught or not, failed the submission.
        val listener = listenerReading(definitionId = caseDocument())

        actAs(CASE_WORKER)
        assertThatCode { completeTaskInTransaction(listener) }.doesNotThrowAnyException()
    }

    @Test
    fun `the case-wide read finds the composers on the case's own version`() {
        // The id comes from the document, not from "the active version": the forms read are the
        // ones the case's processes were started from.
        actAs(CASE_WORKER)
        val composers = TransactionTemplate(transactionManager).execute { resolver.forCaseDefinition(CORRESPONDENTIE) }

        assertThat(composers).extracting<String> { it.componentKey() }.contains("pv:epistolaLetter")
    }

    private fun completeTaskInTransaction(listener: ComposerSubmissionListener) {
        val event =
            TaskCompletedEvent(
                UUID.randomUUID(),
                "test",
                LocalDateTime.now(),
                "someone",
                "someone",
                LocalDateTime.now(),
                UUID.randomUUID().toString(),
                "Beoordelen",
                processWithoutComposers(),
                UUID.randomUUID().toString(),
                // Every Valtimo process carries VTM_callDepth, so a task's variables are never empty.
                mapOf<String, Any>("VTM_callDepth" to 1),
                DOCUMENT_ID.toString(),
            )
        // The commit is what failed: the listener itself returned normally.
        TransactionTemplate(transactionManager).executeWithoutResult { listener.onTaskCompleted(event) }
    }

    /** The real listener, write-back service and resolver; only the document read is stubbed. */
    private fun listenerReading(definitionId: JsonSchemaDocumentDefinitionId): ComposerSubmissionListener {
        val document = mock<JsonSchemaDocument> { on { definitionId() } doReturn definitionId }
        val documentService = mock<DocumentService>()
        org.mockito.Mockito
            .doReturn(Optional.of(document))
            .whenever(documentService)
            .findBy(any())
        val writeBack =
            ComposerWriteBackService(resolver, documentService, jsonataMappingService, valueResolverService, objectMapper)
        return ComposerSubmissionListener(writeBack, resolver)
    }

    /** A deployed process none of whose forms carries a composer, as a building block's would be. */
    private fun processWithoutComposers(): String =
        runWithoutAuthorization {
            repositoryService
                .createProcessDefinitionQuery()
                .latestVersion()
                .list()
                .map { it.id }
                .first { resolver.forProcessDefinition(it).isEmpty() }
        }

    private fun buildingBlockDocument(): JsonSchemaDocumentDefinitionId =
        JsonSchemaDocumentDefinitionId
            .forBuildingBlock(BUILDING_BLOCK, BuildingBlockDefinitionId(BUILDING_BLOCK, "1.0.0"))
            .also { assertThat(it.caseDefinitionId()).describedAs("a building block's document has no case type").isNull() }

    private fun caseDocument(): JsonSchemaDocumentDefinitionId = JsonSchemaDocumentDefinitionId.forCase("correspondentie", CORRESPONDENTIE)

    private fun actAs(roles: Array<String>) {
        SecurityContextHolder.getContext().authentication =
            TestingAuthenticationToken("someone", "n/a", *roles).apply { isAuthenticated = true }
    }

    companion object {
        private val DOCUMENT_ID: UUID = UUID.randomUUID()
        private const val BUILDING_BLOCK = "aanvraag-beoordelen"
        private val CORRESPONDENTIE = CaseDefinitionId("correspondentie", "1.0.0")
        private val CASE_WORKER = arrayOf("ROLE_USER")
        private val ADMIN = arrayOf("ROLE_ADMIN", "ROLE_USER")

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