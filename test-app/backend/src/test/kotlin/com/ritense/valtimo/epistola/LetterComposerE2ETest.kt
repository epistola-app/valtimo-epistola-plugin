// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola

import app.epistola.valtimo.domain.GenerationJobResult
import app.epistola.valtimo.domain.SimpleMappingSupport
import app.epistola.valtimo.domain.TemplateDetails
import app.epistola.valtimo.schema.JsonSchemaMappingAnalyzer
import app.epistola.valtimo.service.EpistolaService
import app.epistola.valtimo.composer.LetterComposerService
import app.epistola.valtimo.composer.LetterComposerService.ComposerContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.authorization.AuthorizationContext.Companion.runWithoutAuthorization
import com.ritense.document.domain.impl.request.NewDocumentRequest
import com.ritense.document.service.DocumentService
import com.ritense.valtimo.Application
import com.ritense.valueresolver.ValueResolverService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.TaskService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
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
 * Boots the real test-app and walks the letter composer demo on the **Correspondentie** case:
 * pick a letter, see what the case could not supply, and let one generate task render whichever
 * letter was chosen.
 *
 * What it guards, and what a unit test cannot see:
 * - the bundled demo really hangs together — the form's baseline mapping against the real case
 *   schema, and the real data contracts of the bundled catalog templates;
 * - "ask only for what the mapping did not fill" holds for two templates that share most of their
 *   contract: the acknowledgement needs nothing, the decision needs its three decision fields;
 * - the chosen letter survives the round trip through a process variable and comes back out of the
 *   composed-letter action, so a single service task generates the letter that was picked.
 *
 * [EpistolaService] is mocked, but the template contracts are read from the bundled catalog rather
 * than invented here: a demo whose mapping drifts from the shipped contract should fail this test.
 */
@SpringBootTest(classes = [Application::class])
@ActiveProfiles("test")
class LetterComposerE2ETest {
    @MockitoBean
    lateinit var epistolaService: EpistolaService

    @Autowired
    lateinit var letterComposerService: LetterComposerService

    @Autowired
    lateinit var documentService: DocumentService

    @Autowired
    lateinit var runtimeService: RuntimeService

    @Autowired
    lateinit var engineTaskService: TaskService

    @Autowired
    lateinit var valueResolverService: ValueResolverService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun stubTemplateContracts() {
        TEMPLATES.forEach { templateId ->
            whenever(
                epistolaService.getTemplateDetails(any(), any(), any(), eq(CATALOG), eq(templateId)),
            ).thenReturn(bundledTemplateDetails(templateId))
        }
        whenever(
            epistolaService.submitGenerationJob(
                any(),
                any(),
                any(),
                any(),
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                any(),
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
            ),
        ).thenReturn(
            GenerationJobResult
                .builder()
                .requestId("req-1")
                .status("PENDING")
                .build(),
        )
    }

    @Test
    fun `a letter the case can fill completely asks the employee for nothing`() {
        val context = startCaseAndOpenChooseLetter()

        val prepared = runWithoutAuthorization { letterComposerService.prepare(context, ACKNOWLEDGEMENT) }

        assertThat(prepared.complete())
            .describedAs("the baseline mapping covers this template's whole contract")
            .isTrue()
        assertThat(prepared.form().path("components")).isEmpty()
        assertThat(prepared.data()["objector"] as Map<String, Any>).containsEntry("firstName", "Jan")
    }

    @Test
    fun `a letter the case cannot fill asks for exactly the missing fields`() {
        val context = startCaseAndOpenChooseLetter()

        val prepared = runWithoutAuthorization { letterComposerService.prepare(context, DECISION) }

        val asked = prepared.form().path("components").map { it.path("key").asText() }
        assertThat(asked)
            .describedAs("the case knows the objector and the original decision, but not the ruling")
            .containsExactlyInAnyOrder("decisionType", "decision", "motivation")
        assertThat(prepared.complete()).isFalse()
    }

    @Test
    fun `one generate task renders whichever letter was chosen`() {
        val context = startCaseAndOpenChooseLetter()
        val prepared = runWithoutAuthorization { letterComposerService.prepare(context, DECISION) }

        // What the composer component stores when the employee submits: the mapped data with their
        // input laid over it, under the pv: key the form declares.
        val data = prepared.data().toMutableMap()
        data["decisionType"] = "gegrond"
        data["decision"] = "Het bezwaar is gegrond verklaard."
        data["motivation"] = "De dakkapel voldoet aan de welstandscriteria."
        valueResolverService.handleValues(
            context.processInstanceId(),
            null,
            mapOf(
                "pv:epistolaLetter" to
                    mapOf("templateId" to DECISION, "catalogId" to CATALOG, "data" to data),
            ),
        )

        val task =
            engineTaskService
                .createTaskQuery()
                .processInstanceId(context.processInstanceId())
                .taskDefinitionKey(CHOOSE_LETTER_TASK)
                .singleResult()
        runWithoutAuthorization { engineTaskService.complete(task.id) }

        val dataCaptor = argumentCaptor<Map<String, Any>>()
        verify(epistolaService).submitGenerationJob(
            any(),
            any(),
            any(),
            eq(CATALOG),
            eq(DECISION),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            dataCaptor.capture(),
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
        )
        assertThat(dataCaptor.firstValue)
            .describedAs("generation renders exactly what was composed, including the typed input")
            .containsEntry("decisionType", "gegrond")
            .containsEntry("motivation", "De dakkapel voldoet aan de welstandscriteria.")
        assertThat(dataCaptor.firstValue["objector"] as Map<String, Any>).containsEntry("lastName", "Jansen")
    }

    private fun startCaseAndOpenChooseLetter(): ComposerContext {
        val document =
            runWithoutAuthorization {
                documentService
                    .createDocument(
                        NewDocumentRequest(DOCUMENT_DEFINITION, CASE_KEY, CASE_VERSION, caseContent()),
                    ).resultingDocument()
                    .orElseThrow()
            }
        val processInstance = runtimeService.startProcessInstanceByKey(PROCESS_KEY, document.id().toString())
        val task =
            engineTaskService
                .createTaskQuery()
                .processInstanceId(processInstance.id)
                .taskDefinitionKey(CHOOSE_LETTER_TASK)
                .singleResult()
        assertThat(task).describedAs("the process must wait on the choose-letter task").isNotNull()

        return ComposerContext(
            task.processDefinitionId,
            task.taskDefinitionKey,
            processInstance.id,
            document.id().toString(),
        )
    }

    private fun caseContent() =
        objectMapper.readTree(
            """
            {
              "objector": {
                "firstName": "Jan",
                "lastName": "Jansen",
                "address": {
                  "street": "Dorpsstraat",
                  "houseNumber": "12",
                  "postalCode": "6131AA",
                  "city": "Sittard"
                }
              },
              "originalDecision": {
                "reference": "OMG-2026-0042",
                "date": "2026-01-15T00:00:00Z",
                "subject": "Omgevingsvergunning dakkapel"
              },
              "objection": {
                "grounds": "De dakkapel past in het straatbeeld.",
                "receivedDate": "2026-02-01T00:00:00Z"
              }
            }
            """.trimIndent(),
        )

    /** The template's real contract, straight from the bundled catalog resource. */
    private fun bundledTemplateDetails(templateId: String): TemplateDetails {
        val resource =
            ClassPathResource(
                "config/epistola/catalogs/municipality-demo/resources/template/$templateId.json",
            )
        val dataModel =
            resource.inputStream
                .use { objectMapper.readTree(it) }
                .path("resource")
                .path("dataModel")
        val analysis = JsonSchemaMappingAnalyzer().analyze(objectMapper.convertValue(dataModel, Map::class.java) as Map<String, Any>)
        return TemplateDetails(templateId, templateId, analysis.fields(), dataModel, SimpleMappingSupport.full())
    }

    private fun <T> anyOrNull(): T? = org.mockito.kotlin.anyOrNull()

    companion object {
        private const val CASE_KEY = "correspondentie"
        private const val CASE_VERSION = "1.0.0"
        private const val DOCUMENT_DEFINITION = "correspondentie"
        private const val PROCESS_KEY = "correspondentie-letter-composer"
        private const val CHOOSE_LETTER_TASK = "choose-letter"
        private const val CATALOG = "municipality-demo"
        private const val ACKNOWLEDGEMENT = "ontvangstbevestiging-bezwaar"
        private const val DECISION = "besluit-bezwaar"
        private val TEMPLATES = listOf(ACKNOWLEDGEMENT, DECISION)

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