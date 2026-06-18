package com.ritense.valtimo.epistola

import app.epistola.valtimo.domain.DocumentStorageTarget
import app.epistola.valtimo.service.EpistolaService
import app.epistola.valtimo.service.completion.EpistolaMessageCorrelationService
import app.epistola.valtimo.service.download.DocumentStorageStrategy
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.authorization.AuthorizationContext.Companion.runWithoutAuthorization
import com.ritense.document.domain.impl.request.NewDocumentRequest
import com.ritense.document.service.DocumentService
import com.ritense.plugin.domain.PluginConfiguration
import com.ritense.plugin.service.PluginService
import com.ritense.valtimo.Application
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin
import com.ritense.valtimo.service.OperatonTaskService
import com.ritense.valueresolver.ValueResolverService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.TaskService
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.variable.value.BytesValue
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
 * End-to-end test that boots the real test-app [Application] (full Valtimo) against Testcontainers
 * Postgres + Keycloak, exercising the `download-document` storageTarget feature in the real
 * application — see `docs/adr/0001-download-document-content-storage.md`.
 *
 * <p>Valtimo's KeycloakAutoConfiguration resolves the OIDC issuer eagerly at startup, so a reachable
 * issuer is required to boot; a plain Keycloak with its default `master` realm satisfies that.
 * [EpistolaService] is mocked, so no real Epistola is needed.
 *
 * <p>Covers, against the real app context: the strategy wiring; the real `download-document` action
 * for both targets (resolved via [PluginService] from the deployed plugin configuration); and the
 * BPMN `asyncAfter` catch-event flow with document-variable serialization — the two bugs this work
 * fixed (catch-event stall + the FileValue serialization failure on task open).
 */
@SpringBootTest(classes = [Application::class])
@ActiveProfiles("test")
class DownloadDocumentE2ETest {
    @MockitoBean
    lateinit var epistolaService: EpistolaService

    @Autowired
    lateinit var pluginService: PluginService

    @Autowired
    lateinit var storageStrategies: List<DocumentStorageStrategy>

    @Autowired
    lateinit var runtimeService: RuntimeService

    @Autowired
    lateinit var historyService: HistoryService

    @Autowired
    lateinit var repositoryService: RepositoryService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var correlationService: EpistolaMessageCorrelationService

    @Autowired
    lateinit var valueResolverService: ValueResolverService

    @Autowired
    lateinit var documentService: DocumentService

    @Autowired
    lateinit var operatonTaskService: OperatonTaskService

    @Autowired
    lateinit var engineTaskService: TaskService

    private var deploymentId: String? = null

    @BeforeEach
    fun deploy() {
        deploymentId =
            repositoryService
                .createDeployment()
                .addString("epistola-download-flow-test.bpmn", BPMN)
                .deploy()
                .id
    }

    @AfterEach
    fun undeploy() {
        deploymentId?.let { repositoryService.deleteDeployment(it, true) }
    }

    @Test
    fun `both storage strategies are wired in the real app`() {
        assertThat(storageStrategies.map { it.target() })
            .contains(DocumentStorageTarget.TEMPORARY_RESOURCE, DocumentStorageTarget.PROCESS_VARIABLE)
    }

    @Test
    fun `download action with temporary-resource stores only a resource id`() {
        val plugin = resolvePlugin()
        whenever(epistolaService.downloadDocument(any(), any(), any(), eq("doc-1")))
            .thenReturn(byteArrayOf(0x25, 0x50, 0x44, 0x46))
        val execution = mock<DelegateExecution>()
        whenever(execution.getVariable(DOCUMENT_VARIABLE)).thenReturn("doc-1")

        plugin.downloadDocument(
            execution,
            DOCUMENT_VARIABLE,
            DocumentStorageTarget.TEMPORARY_RESOURCE,
            "documentResourceId",
            null,
        )

        val captor = argumentCaptor<Any>()
        verify(execution).setVariable(eq("documentResourceId"), captor.capture())
        assertThat(captor.firstValue).isInstanceOf(String::class.java)
        objectMapper.writeValueAsString(captor.firstValue) // serializes cleanly
    }

    @Test
    fun `download action with process-variable stores inline bytes`() {
        val plugin = resolvePlugin()
        val pdf = ByteArray(8192) { (it % 256).toByte() }
        whenever(epistolaService.downloadDocument(any(), any(), any(), eq("doc-1"))).thenReturn(pdf)
        val execution = mock<DelegateExecution>()
        whenever(execution.getVariable(DOCUMENT_VARIABLE)).thenReturn("doc-1")

        plugin.downloadDocument(
            execution,
            DOCUMENT_VARIABLE,
            DocumentStorageTarget.PROCESS_VARIABLE,
            null,
            "documentContent",
        )

        val captor = argumentCaptor<Any>()
        verify(execution).setVariable(eq("documentContent"), captor.capture())
        assertThat(captor.firstValue).isInstanceOf(BytesValue::class.java)
        assertThat((captor.firstValue as BytesValue).value).isEqualTo(pdf)
    }

    @Test
    fun `download action fails fast when the output variable for the target is missing`() {
        val plugin = resolvePlugin()
        val execution = mock<DelegateExecution>()
        whenever(execution.getVariable(DOCUMENT_VARIABLE)).thenReturn("doc-1")

        assertThatThrownBy {
            plugin.downloadDocument(
                execution,
                DOCUMENT_VARIABLE,
                DocumentStorageTarget.TEMPORARY_RESOURCE,
                "  ",
                "documentContent",
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("output variable")
    }

    @Test
    fun `catch event completes via async continuation and stored variable serializes`() {
        val pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY, mapOf(DOCUMENT_VARIABLE to "test-doc"))

        assertThat(messageSubscriptionExists(pi.id)).isTrue()

        runtimeService.createMessageCorrelation(MESSAGE).processInstanceId(pi.id).correlate()
        assertThat(messageSubscriptionExists(pi.id)).isFalse()
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.id).singleResult()).isNull()
        }

        val value = storedValue(pi.id)
        assertThat(value).isInstanceOf(ByteArray::class.java)
        objectMapper.writeValueAsString(value) // serializes cleanly (regression)
    }

    @Test
    fun `parallel branches each correlate to their own result, independently`() {
        val deployment =
            repositoryService.createDeployment().addString("epistola-parallel-test.bpmn", PARALLEL_BPMN).deploy()
        try {
            val pi = runtimeService.startProcessInstanceByKey(PARALLEL_PROCESS_KEY)

            // Both parallel branches reach their own catch event.
            await().atMost(Duration.ofSeconds(20)).untilAsserted {
                assertThat(messageSubscriptionCount(pi.id)).isEqualTo(2)
            }

            // Correlate ONLY branch A's result — must wake exactly that branch.
            assertThat(correlationService.correlateCompletion("demo", "req-a", "COMPLETED", "doc-a", null))
                .isEqualTo(1)
            await().atMost(Duration.ofSeconds(20)).untilAsserted {
                assertThat(messageSubscriptionCount(pi.id)).isEqualTo(1) // only branch B still waits
            }
            assertThat(documentIdOf(runtimeService.getVariable(pi.id, "resultA"))).isEqualTo("doc-a")
            assertThat(documentIdOf(runtimeService.getVariable(pi.id, "resultB")))
                .`as`("branch B must NOT be affected by branch A's result")
                .isNull()

            // Correlate branch B — process completes; B got its own document.
            assertThat(correlationService.correlateCompletion("demo", "req-b", "COMPLETED", "doc-b", null))
                .isEqualTo(1)
            await().atMost(Duration.ofSeconds(20)).untilAsserted {
                assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.id).singleResult())
                    .isNull()
            }
            assertThat(documentIdOf(historicValue(pi.id, "resultB"))).isEqualTo("doc-b")
            assertThat(documentIdOf(historicValue(pi.id, "resultA"))).isEqualTo("doc-a")
        } finally {
            repositoryService.deleteDeployment(deployment.id, true)
        }
    }

    @Test
    fun `epistola value resolver yields the active task's identity from the task scope`() {
        // This is how the Formio components obtain the task id in every task-open flow: a hidden
        // carrier field with sourceKey 'epistola:taskId' is prefilled server-side, where Valtimo
        // passes the OperatonTask as the resolver's VariableScope. Verify the resolver against the
        // real application context + a real active user task.
        val deployment =
            repositoryService.createDeployment().addString("epistola-usertask-test.bpmn", USER_TASK_BPMN).deploy()
        try {
            // Bind the process to a real case document: Valtimo dossier processes always carry the
            // case-document UUID as the business key, and the engine's task-create listeners
            // (CaseAssigneeTaskCreatedListener / CaseTaskTeamAutoAssignListener) resolve that document
            // on user-task creation — a process without one fails before our resolver runs.
            val document =
                runWithoutAuthorization {
                    documentService
                        .createDocument(
                            NewDocumentRequest(
                                "example",
                                "example",
                                "1.0.0",
                                objectMapper.readTree("""{"firstName":"Test"}"""),
                            ),
                        ).resultingDocument()
                        .orElseThrow()
                }
            val pi = runtimeService.startProcessInstanceByKey(USER_TASK_PROCESS_KEY, document.id().toString())
            val task = engineTaskService.createTaskQuery().processInstanceId(pi.id).singleResult()
            assertThat(task).isNotNull

            val resolved =
                runWithoutAuthorization {
                    val operatonTask = operatonTaskService.findTaskById(task.id)
                    valueResolverService.resolveValues(
                        pi.id,
                        operatonTask,
                        listOf("epistola:taskId", "epistola:taskDefinitionKey", "epistola:executionId"),
                    )
                }

            assertThat(resolved["epistola:taskId"]).isEqualTo(task.id)
            assertThat(resolved["epistola:taskDefinitionKey"]).isEqualTo("reviewTask")
            assertThat(resolved["epistola:executionId"]).isEqualTo(task.executionId)
        } finally {
            repositoryService.deleteDeployment(deployment.id, true)
        }
    }

    private fun resolvePlugin(): EpistolaPlugin {
        val configurations = pluginService.findPluginConfigurations(EpistolaPlugin::class.java) { true }
        assertThat(configurations).isNotEmpty()
        return pluginService.createInstance(configurations.first() as PluginConfiguration) as EpistolaPlugin
    }

    private fun messageSubscriptionExists(processInstanceId: String): Boolean =
        runtimeService
            .createEventSubscriptionQuery()
            .processInstanceId(processInstanceId)
            .eventName(MESSAGE)
            .count() > 0

    private fun storedValue(processInstanceId: String): Any? =
        historyService
            .createHistoricVariableInstanceQuery()
            .processInstanceId(processInstanceId)
            .variableName(EpistolaTestStoreDelegate.OUTPUT_VARIABLE)
            .singleResult()
            ?.value

    private fun messageSubscriptionCount(processInstanceId: String): Long =
        runtimeService
            .createEventSubscriptionQuery()
            .processInstanceId(processInstanceId)
            .eventName(MESSAGE)
            .count()

    private fun historicValue(
        processInstanceId: String,
        variableName: String,
    ): Any? =
        historyService
            .createHistoricVariableInstanceQuery()
            .processInstanceId(processInstanceId)
            .variableName(variableName)
            .singleResult()
            ?.value

    private fun documentIdOf(richResult: Any?): Any? = (richResult as? Map<*, *>)?.get("documentId")

    companion object {
        private const val PROCESS_KEY = "epistola-download-flow-test"
        private const val PARALLEL_PROCESS_KEY = "epistola-parallel-test"
        private const val USER_TASK_PROCESS_KEY = "epistola-usertask-test"
        private const val MESSAGE = "EpistolaDocumentGenerated"
        private const val DOCUMENT_VARIABLE = "epistolaResult"

        // Minimal process that parks on a user task, so the epistola: value resolver can be
        // exercised against a real OperatonTask scope.
        private val USER_TASK_BPMN =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs_ut_test">
              <bpmn:process id="epistola-usertask-test" isExecutable="true" camunda:historyTimeToLive="P1D">
                <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="reviewTask" />
                <bpmn:userTask id="reviewTask" name="Review">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:sequenceFlow id="f2" sourceRef="reviewTask" targetRef="end" />
                <bpmn:endEvent id="end"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """.trimIndent()

        private val BPMN =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs_dl_test">
              <bpmn:message id="msg_docgen" name="EpistolaDocumentGenerated" />
              <bpmn:process id="epistola-download-flow-test" isExecutable="true" camunda:historyTimeToLive="P1D">
                <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait" />
                <bpmn:intermediateCatchEvent id="wait" name="Wait" camunda:asyncAfter="true">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                  <bpmn:messageEventDefinition id="med" messageRef="msg_docgen" />
                </bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="download" />
                <bpmn:serviceTask id="download" name="Download" camunda:class="com.ritense.valtimo.epistola.EpistolaTestStoreDelegate">
                  <bpmn:incoming>f2</bpmn:incoming>
                  <bpmn:outgoing>f3</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="f3" sourceRef="download" targetRef="end" />
                <bpmn:endEvent id="end"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """.trimIndent()

        // Two parallel branches, each: submit (sets the activity-named jobPath + result var) →
        // its own EpistolaDocumentGenerated catch event (asyncAfter, auto-pinned epistolaWaitFor)
        // → join → end.
        private val PARALLEL_BPMN =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs_par_test">
              <bpmn:message id="msg_docgen" name="EpistolaDocumentGenerated" />
              <bpmn:process id="epistola-parallel-test" isExecutable="true" camunda:historyTimeToLive="P1D">
                <bpmn:startEvent id="start"><bpmn:outgoing>f_s</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f_s" sourceRef="start" targetRef="fork" />
                <bpmn:parallelGateway id="fork">
                  <bpmn:incoming>f_s</bpmn:incoming>
                  <bpmn:outgoing>f_a1</bpmn:outgoing>
                  <bpmn:outgoing>f_b1</bpmn:outgoing>
                </bpmn:parallelGateway>
                <bpmn:sequenceFlow id="f_a1" sourceRef="fork" targetRef="submitA" />
                <bpmn:serviceTask id="submitA" name="Submit A" camunda:class="com.ritense.valtimo.epistola.EpistolaTestSubmitDelegate">
                  <bpmn:extensionElements>
                    <camunda:inputOutput>
                      <camunda:inputParameter name="testRequestId">req-a</camunda:inputParameter>
                      <camunda:inputParameter name="testResultVar">resultA</camunda:inputParameter>
                    </camunda:inputOutput>
                  </bpmn:extensionElements>
                  <bpmn:incoming>f_a1</bpmn:incoming>
                  <bpmn:outgoing>f_a2</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="f_a2" sourceRef="submitA" targetRef="waitA" />
                <bpmn:intermediateCatchEvent id="waitA" name="Wait A" camunda:asyncAfter="true">
                  <bpmn:extensionElements><camunda:inputOutput>
                    <camunda:inputParameter name="epistolaWaitFor">${'$'}{resultA.jobPath}</camunda:inputParameter>
                  </camunda:inputOutput></bpmn:extensionElements>
                  <bpmn:incoming>f_a2</bpmn:incoming>
                  <bpmn:outgoing>f_a3</bpmn:outgoing>
                  <bpmn:messageEventDefinition id="med_a" messageRef="msg_docgen" />
                </bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="f_a3" sourceRef="waitA" targetRef="join" />
                <bpmn:sequenceFlow id="f_b1" sourceRef="fork" targetRef="submitB" />
                <bpmn:serviceTask id="submitB" name="Submit B" camunda:class="com.ritense.valtimo.epistola.EpistolaTestSubmitDelegate">
                  <bpmn:extensionElements>
                    <camunda:inputOutput>
                      <camunda:inputParameter name="testRequestId">req-b</camunda:inputParameter>
                      <camunda:inputParameter name="testResultVar">resultB</camunda:inputParameter>
                    </camunda:inputOutput>
                  </bpmn:extensionElements>
                  <bpmn:incoming>f_b1</bpmn:incoming>
                  <bpmn:outgoing>f_b2</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="f_b2" sourceRef="submitB" targetRef="waitB" />
                <bpmn:intermediateCatchEvent id="waitB" name="Wait B" camunda:asyncAfter="true">
                  <bpmn:extensionElements><camunda:inputOutput>
                    <camunda:inputParameter name="epistolaWaitFor">${'$'}{resultB.jobPath}</camunda:inputParameter>
                  </camunda:inputOutput></bpmn:extensionElements>
                  <bpmn:incoming>f_b2</bpmn:incoming>
                  <bpmn:outgoing>f_b3</bpmn:outgoing>
                  <bpmn:messageEventDefinition id="med_b" messageRef="msg_docgen" />
                </bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="f_b3" sourceRef="waitB" targetRef="join" />
                <bpmn:parallelGateway id="join">
                  <bpmn:incoming>f_a3</bpmn:incoming>
                  <bpmn:incoming>f_b3</bpmn:incoming>
                  <bpmn:outgoing>f_e</bpmn:outgoing>
                </bpmn:parallelGateway>
                <bpmn:sequenceFlow id="f_e" sourceRef="join" targetRef="end" />
                <bpmn:endEvent id="end"><bpmn:incoming>f_e</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """.trimIndent()

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