// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola

import app.epistola.valtimo.service.EpistolaService
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.authorization.AuthorizationContext.Companion.runWithoutAuthorization
import com.ritense.document.domain.impl.request.NewDocumentRequest
import com.ritense.document.service.DocumentService
import com.ritense.formflow.FormFlowTaskOpenResultProperties
import com.ritense.formflow.web.rest.FormFlowResource
import com.ritense.processlink.service.ProcessLinkActivityService
import com.ritense.valtimo.Application
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.TaskService
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
import java.util.UUID

/**
 * Boots the real test-app against Testcontainers Postgres + Keycloak and walks the
 * **Form Flow voorbeeld** demo case: open the `generate-letter` user task, complete both form flow
 * steps, and assert the BPMN advanced to the regular `follow-up` task.
 *
 * <p>This is the CI-runnable counterpart to `e2e/tests/form-flow-transition.spec.ts`. The Playwright
 * suite additionally proves the transition is visible **without a page refresh**, which needs a
 * browser; the regression that made this fixture necessary was a backend one and is fully reachable
 * here.
 *
 * <p>What it guards: the final step's
 * `${valtimoFormFlow.completeTask(additionalProperties, step.submissionData)}` writes the completing
 * step's submission data to `doc:/submission` (the two-argument overload's default save path). When
 * the case's document schema does not accept that key the write is rejected, the expression throws,
 * and the task never completes — a 500 in the UI and no transition. A schema-shape unit test cannot
 * see that; only actually running the flow can.
 *
 * <p>[EpistolaService] is mocked: this path is deliberately Epistola-free, which is the point of the
 * fixture as a baseline.
 */
@SpringBootTest(classes = [Application::class])
@ActiveProfiles("test")
class FormFlowTransitionE2ETest {
    @MockitoBean
    lateinit var epistolaService: EpistolaService

    @Autowired
    lateinit var documentService: DocumentService

    @Autowired
    lateinit var runtimeService: RuntimeService

    @Autowired
    lateinit var engineTaskService: TaskService

    @Autowired
    lateinit var processLinkActivityService: ProcessLinkActivityService

    @Autowired
    lateinit var formFlowResource: FormFlowResource

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `completing the form flow advances the process to the follow-up task`() {
        val document =
            runWithoutAuthorization {
                documentService
                    .createDocument(
                        NewDocumentRequest(
                            DOCUMENT_DEFINITION,
                            CASE_KEY,
                            CASE_VERSION,
                            objectMapper.readTree("""{"title":"Form flow transition E2E"}"""),
                        ),
                    ).resultingDocument()
                    .orElseThrow()
            }
        val processInstance = runtimeService.startProcessInstanceByKey(PROCESS_KEY, document.id().toString())

        assertThat(activeTaskKeys(processInstance.id)).containsExactly(GENERATE_LETTER_TASK)

        val formFlowInstanceId = openFormFlow(processInstance.id)

        // Step 1: the letter details. This step has no onComplete, so it only advances the flow.
        val afterFirstStep =
            completeCurrentStep(formFlowInstanceId, """{"subject":"Bevestiging lokale Form Flow-test"}""")
        assertThat(afterFirstStep)
            .describedAs("the flow must present its confirmation step before completing the task")
            .isNotNull()

        // Step 2: the confirmation. Its onComplete runs valtimoFormFlow.completeTask, which both
        // writes to doc:/submission and completes the BPMN task.
        val afterFinalStep = completeCurrentStep(formFlowInstanceId, """{"submit":true}""")
        assertThat(afterFinalStep).describedAs("the form flow must be finished, with no next step").isNull()

        assertThat(activeTaskKeys(processInstance.id))
            .describedAs("the process must have advanced from the form flow task to the follow-up task")
            .containsExactly(FOLLOW_UP_TASK)
    }

    @Test
    fun `the completing step's submission data is stored on the case document`() {
        val document =
            runWithoutAuthorization {
                documentService
                    .createDocument(
                        NewDocumentRequest(
                            DOCUMENT_DEFINITION,
                            CASE_KEY,
                            CASE_VERSION,
                            objectMapper.readTree("""{"title":"Form flow submission E2E"}"""),
                        ),
                    ).resultingDocument()
                    .orElseThrow()
            }
        val processInstance = runtimeService.startProcessInstanceByKey(PROCESS_KEY, document.id().toString())
        val formFlowInstanceId = openFormFlow(processInstance.id)

        completeCurrentStep(formFlowInstanceId, """{"subject":"Onderwerp"}""")
        completeCurrentStep(formFlowInstanceId, """{"submit":true}""")

        val stored = runWithoutAuthorization { documentService.get(document.id().toString()) }
        assertThat(stored.content().asJson().has("submission"))
            .describedAs(
                "completeTask writes to doc:/submission, so the document schema must accept it — " +
                    "without the key the write is rejected and the task never completes",
            ).isTrue()
    }

    private fun activeTaskKeys(processInstanceId: String): List<String> =
        engineTaskService
            .createTaskQuery()
            .processInstanceId(processInstanceId)
            .list()
            .map { it.taskDefinitionKey }

    private fun openFormFlow(processInstanceId: String): String {
        val task =
            engineTaskService
                .createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(GENERATE_LETTER_TASK)
                .singleResult()
        val result = runWithoutAuthorization { processLinkActivityService.openTask(UUID.fromString(task.id)) }
        val properties = result.properties as FormFlowTaskOpenResultProperties
        return properties.formFlowInstanceId.toString()
    }

    /** Completes whichever step the flow currently sits on; returns the next step's id, or null when finished. */
    private fun completeCurrentStep(
        formFlowInstanceId: String,
        submissionJson: String,
    ): UUID? {
        val state = formFlowResource.getFormFlowState(formFlowInstanceId)?.body!!
        val currentStepId =
            requireNotNull(state.step?.id) { "form flow $formFlowInstanceId has no current step to complete" }
        val completed =
            runWithoutAuthorization {
                formFlowResource.completeStep(
                    formFlowInstanceId,
                    currentStepId.toString(),
                    objectMapper.readTree(submissionJson),
                )
            }
        return completed.body?.step?.id
    }

    companion object {
        private const val CASE_KEY = "form-flow-demo"
        private const val CASE_VERSION = "1.0.0"
        private const val DOCUMENT_DEFINITION = "form-flow-demo"
        private const val PROCESS_KEY = "form-flow-demo"
        private const val GENERATE_LETTER_TASK = "generate-letter"
        private const val FOLLOW_UP_TASK = "follow-up"

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
                    // Log-based readiness: robust under podman where host→container port/HTTP probing
                    // is flaky. Keycloak logs "… started in Ns. Listening on: http://0.0.0.0:8080".
                    waitingFor(Wait.forLogMessage(".*Listening on:.*", 1).withStartupTimeout(Duration.ofMinutes(3)))
                    start()
                }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            // Force IPv4: "localhost" can resolve to ::1, but the container port is published on
            // 127.0.0.1 — the JVM's discovery RestTemplate otherwise gets a ConnectException.
            val issuer = "http://127.0.0.1:${keycloak.getMappedPort(8080)}/realms/master"
            registry.add("spring.security.oauth2.client.provider.keycloakjwt.issuer-uri") { issuer }
            registry.add("spring.security.oauth2.client.provider.keycloakapi.issuer-uri") { issuer }
        }
    }
}