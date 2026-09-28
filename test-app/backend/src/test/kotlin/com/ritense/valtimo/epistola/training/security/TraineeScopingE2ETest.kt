// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import app.epistola.valtimo.domain.GenerationJobResult
import app.epistola.valtimo.service.EpistolaService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.authorization.AuthorizationContext.Companion.runWithoutAuthorization
import com.ritense.document.service.DocumentService
import com.ritense.valtimo.Application
import com.ritense.valtimo.epistola.training.EpistolaTenantCredentials
import com.ritense.valtimo.epistola.training.EpistolaTenantProvisioner
import com.ritense.valtimo.epistola.training.TraineeKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.TaskService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val SHARED_CASE = "form-flow-demo"

/**
 * Drives the trainee scoping over real HTTP — MockMvc against the full filter chain, with the
 * `authentik` profile's OIDC user model (principal name and `${currentUserEmail}` both from the
 * `email` claim), exactly as the demo runs it. Trainee tokens carry `ROLE_USER`, `ROLE_ADMIN` and
 * `ROLE_DEMO`, like authentik issues them; [TraineeAdminAuthorityStripFilter] must take
 * `ROLE_ADMIN` away server-side, after which Valtimo's PBAC (`demo.permission.json`) scopes the
 * trainee to the cases they created.
 */
@SpringBootTest(
    classes = [Application::class],
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.actuate.autoconfigure.metrics.web.tomcat.TomcatMetricsAutoConfiguration," +
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration," +
            "com.valtimo.keycloak.autoconfigure.KeycloakAutoConfiguration," +
            "com.valtimo.keycloak.autoconfigure.ExternalRoleAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test", "training", "authentik")
class TraineeScopingE2ETest {
    @MockitoBean
    lateinit var epistolaService: EpistolaService

    @MockitoBean
    lateinit var epistolaTenantProvisioner: EpistolaTenantProvisioner

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var documentService: DocumentService

    @Autowired
    lateinit var taskService: TaskService

    @Autowired
    lateinit var repositoryService: RepositoryService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    private val alice = trainee("alice")
    private val bob = trainee("bob")
    private val admin = user("admin-${UUID.randomUUID()}@example.com", "ROLE_USER", "ROLE_ADMIN")

    @BeforeEach
    fun tenants() {
        whenever(epistolaTenantProvisioner.ensureTenant(any())).thenAnswer {
            EpistolaTenantCredentials(tenantId = "trainee-${it.arguments[0]}", apiKey = "epk_test")
        }
        // Some shared case types generate a letter right after the start form (objection does).
        // Generation itself is out of scope here; accept the job so the start completes.
        whenever(
            epistolaService.submitGenerationJob(
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
            ),
        ).thenReturn(
            GenerationJobResult
                .builder()
                .requestId(UUID.randomUUID().toString())
                .status("PENDING")
                .build(),
        )
    }

    @Test
    fun `each trainee works only on their own cases in a shared case type, an admin sees all`() {
        val aliceCase = startCase(alice)
        val bobCase = startCase(bob)

        // createdBy is the login (email claim), the same value ${currentUserEmail} resolves to.
        assertThat(createdBy(aliceCase)).isEqualTo(alice.email)
        assertThat(createdBy(bobCase)).isEqualTo(bob.email)

        val aliceSearch = search(alice)
        assertThat(ids(aliceSearch)).containsExactly(aliceCase)
        assertThat(aliceSearch["totalElements"].asLong()).isEqualTo(1)
        val bobSearch = search(bob)
        assertThat(ids(bobSearch)).containsExactly(bobCase)
        assertThat(bobSearch["totalElements"].asLong()).isEqualTo(1)
        assertThat(ids(search(admin))).contains(aliceCase, bobCase)

        assertThat(status(get("/api/v1/document/$aliceCase"), alice)).isEqualTo(200)
        assertThat(status(get("/api/v1/document/$bobCase"), alice)).isIn(403, 404)
        assertThat(status(get("/api/v1/document/$aliceCase"), admin)).isEqualTo(200)

        val aliceTask = taskOf(aliceCase)
        val bobTask = taskOf(bobCase)
        assertThat(status(get("/api/v1/task/$aliceTask"), alice)).isEqualTo(200)
        assertThat(status(get("/api/v1/task/$bobTask"), alice)).isIn(403, 404)
        assertThat(status(post("/api/v1/task/$bobTask/claim"), alice)).isIn(403, 404)
    }

    @Test
    fun `a trainee starts a case in a shared case type through its start form, not in another trainee's dossier`() {
        // The UI starts a case by submitting the start event's form to
        // POST /api/v1/process-link/{processLinkId}/form/submission — not through
        // process-document/operation, which startCase() uses. Found on the live demo: this
        // returned 403 for every shared case type.
        val objectionStart = processLinkOf(processDefinitionId("objection-handling", "CD:objection:1.0.0"), "startEvent")
        val submission =
            """{"objector":{"firstName":"Alice","lastName":"Trainee","address":{"street":"Straat","houseNumber":"1",""" +
                """"postalCode":"1234AB","city":"Sittard"}},"originalDecision":{"reference":"D-1","date":"2026-09-01T00:00:00.000Z",""" +
                """"subject":"Vergunning"},"objection":{"grounds":"Onterecht","receivedDate":"2026-09-10T00:00:00.000Z"}}"""
        val started =
            perform(postJson("/api/v1/process-link/$objectionStart/form/submission?documentDefinitionName=objection", submission), alice)
        assertThat(started.response.status).describedAs(started.response.contentAsString).isBetween(200, 299)

        val aliceObjections = searchIn("objection", alice)
        assertThat(aliceObjections["totalElements"].asLong()).isEqualTo(1)
        assertThat(createdBy(ids(aliceObjections).single())).isEqualTo(alice.email)
        assertThat(searchIn("objection", bob)["totalElements"].asLong()).isEqualTo(0)

        // The same endpoint on another trainee's dossier stays refused.
        status(get("/api/v1/case-definition?active=true"), alice)
        val aliceKey = TraineeKeys.caseDefinitionKey(alice.identity)
        val aliceDossierLink = processLinkOf(processDefinitionId(SHARED_CASE, "CD:$aliceKey:1.0.0"), null)
        assertThat(
            status(postJson("/api/v1/process-link/$aliceDossierLink/form/submission?documentDefinitionName=$aliceKey", "{}"), bob),
        ).isEqualTo(403)
    }

    @Test
    fun `a trainee may read a shared case type's configuration but not change it`() {
        val settings = "/api/management/v1/case-definition/$SHARED_CASE/version/1.0.0/settings"
        assertThat(status(get(settings), alice)).isEqualTo(200)
        assertThat(status(patchJson(settings, """{"canHaveAssignee":false}"""), alice)).isEqualTo(403)

        val sharedProcessDefinition =
            repositoryService
                .createProcessDefinitionQuery()
                .processDefinitionKey(SHARED_CASE)
                .versionTag("CD:$SHARED_CASE:1.0.0")
                .singleResult()
                .id
        val processLink =
            """{"processDefinitionId":"$sharedProcessDefinition","activityId":"generate-letter",""" +
                """"activityType":"bpmn:UserTask:create","processLinkType":"form","formDefinitionId":"${UUID.randomUUID()}"}"""
        assertThat(status(postJson("/api/v1/process-link", processLink), alice)).isEqualTo(403)
    }

    @Test
    fun `a trainee can still change their own dossier's configuration`() {
        // Any request provisions the dossier.
        status(get("/api/v1/case-definition?active=true"), alice)
        val ownKey = TraineeKeys.caseDefinitionKey(alice.identity)

        val settings = "/api/management/v1/case-definition/$ownKey/version/1.0.0/settings"
        assertThat(status(get(settings), alice)).isEqualTo(200)
        // Authorization lets the PATCH through; the dossier is finalized at provisioning, so
        // Valtimo's own rule ("final, can't be updated") answers, not a 403.
        assertThat(status(patchJson(settings, """{"canHaveAssignee":false}"""), alice)).isNotEqualTo(403)

        val ownProcessDefinition =
            repositoryService
                .createProcessDefinitionQuery()
                .processDefinitionKey(SHARED_CASE)
                .versionTag("CD:$ownKey:1.0.0")
                .singleResult()
                .id
        val links = perform(get("/api/v1/process-link?processDefinitionId=$ownProcessDefinition"), alice)
        assertThat(links.response.status).isEqualTo(200)
        val pluginLink = objectMapper.readTree(links.response.contentAsString).first { it["processLinkType"].asText() == "plugin" }
        val update =
            objectMapper.writeValueAsString(
                mapOf(
                    "id" to pluginLink["id"].asText(),
                    "processLinkType" to "plugin",
                    "pluginConfigurationId" to pluginLink["pluginConfigurationId"].asText(),
                    "pluginActionDefinitionKey" to pluginLink["pluginActionDefinitionKey"].asText(),
                    "actionProperties" to pluginLink["actionProperties"],
                ),
            )
        val updated = perform(putJson("/api/v1/process-link", update), alice)
        assertThat(updated.response.status).describedAs(updated.response.contentAsString).isBetween(200, 299)

        // Another trainee's dossier stays out of reach: its configuration, even to read, and its process links.
        assertThat(status(get(settings), bob)).isEqualTo(403)
        assertThat(status(putJson("/api/v1/process-link", update), bob)).isEqualTo(403)
    }

    @Test
    fun `the case list shows shared case types and the trainee's own dossier, never another trainee's`() {
        status(get("/api/v1/case-definition?active=true"), alice)
        status(get("/api/v1/case-definition?active=true"), bob)
        val aliceKey = TraineeKeys.caseDefinitionKey(alice.identity)
        val bobKey = TraineeKeys.caseDefinitionKey(bob.identity)
        // Dossiers are active once provisioned (valtimo-epistola-plugin#145); do it here so this
        // test doesn't depend on that change.
        transactionTemplate.execute {
            jdbcTemplate.update("UPDATE case_definition SET active = true WHERE case_definition_key IN (?, ?)", aliceKey, bobKey)
        }

        val aliceList = caseDefinitionKeys(alice)
        assertThat(aliceList).contains(SHARED_CASE, aliceKey).doesNotContain(bobKey)
        assertThat(caseDefinitionKeys(bob)).contains(SHARED_CASE, bobKey).doesNotContain(aliceKey)
        assertThat(caseDefinitionKeys(admin)).contains(SHARED_CASE, aliceKey, bobKey)
    }

    private fun caseDefinitionKeys(user: TestUser): List<String> {
        val result = perform(get("/api/v1/case-definition?active=true&size=100"), user)
        assertThat(result.response.status).describedAs(result.response.contentAsString).isEqualTo(200)
        val body = objectMapper.readTree(result.response.contentAsString)
        val items = if (body.isArray) body else body["content"]
        return items.map { (it["key"] ?: it["caseDefinitionKey"] ?: it["id"]["key"]).asText() }
    }

    @Test
    fun `ROLE_ADMIN is not honoured for a trainee server-side, only for a genuine admin`() {
        // ROLE_ADMIN-only, neither widened for trainees nor on the hard-block list.
        val adminOnly = "/api/management/v1/external-plugin/definition"
        assertThat(status(get(adminOnly), alice)).isEqualTo(403)
        assertThat(status(get(adminOnly), admin)).isEqualTo(200)
    }

    private fun startCase(user: TestUser): String {
        val body =
            """{"processDefinitionKey":"$SHARED_CASE","request":{"definition":"$SHARED_CASE",""" +
                """"caseDefinitionKey":"$SHARED_CASE","caseDefinitionVersionTag":"1.0.0","content":{"title":"${user.email}"}}}"""
        val result = perform(postJson("/api/v1/process-document/operation/new-document-and-start-process", body), user)
        assertThat(result.response.status).describedAs(result.response.contentAsString).isEqualTo(200)
        return objectMapper.readTree(result.response.contentAsString)["document"]["id"].asText()
    }

    private fun search(user: TestUser): JsonNode = searchIn(SHARED_CASE, user)

    private fun searchIn(
        caseDefinitionKey: String,
        user: TestUser,
    ): JsonNode {
        val result =
            perform(postJson("/api/v1/document-definition/$caseDefinitionKey/search?page=0&size=10", "{}"), user)
        assertThat(result.response.status).describedAs(result.response.contentAsString).isEqualTo(200)
        return objectMapper.readTree(result.response.contentAsString)
    }

    private fun processDefinitionId(
        processDefinitionKey: String,
        versionTag: String,
    ): String =
        repositoryService
            .createProcessDefinitionQuery()
            .processDefinitionKey(processDefinitionKey)
            .versionTag(versionTag)
            .singleResult()
            .id

    /** A process link of that process definition, on [activityId] when given. */
    private fun processLinkOf(
        processDefinitionId: String,
        activityId: String?,
    ): String =
        jdbcTemplate
            .queryForList(
                "SELECT CAST(id AS varchar) FROM process_link WHERE process_definition_id = ?" +
                    (if (activityId != null) " AND activity_id = ?" else ""),
                String::class.java,
                *listOfNotNull(processDefinitionId, activityId).toTypedArray(),
            ).first()

    private fun ids(page: JsonNode): List<String> = page["content"].map { it["id"].asText() }

    private fun createdBy(documentId: String): String =
        runWithoutAuthorization {
            documentService.get(documentId).createdBy()
        }

    private fun taskOf(documentId: String): String =
        taskService
            .createTaskQuery()
            .processInstanceBusinessKey(documentId)
            .singleResult()
            .id

    private fun postJson(
        url: String,
        body: String,
    ) = post(url).contentType(MediaType.APPLICATION_JSON).content(body)

    private fun putJson(
        url: String,
        body: String,
    ) = put(url).contentType(MediaType.APPLICATION_JSON).content(body)

    private fun patchJson(
        url: String,
        body: String,
    ) = patch(url).contentType(MediaType.APPLICATION_JSON).content(body)

    private fun status(
        request: MockHttpServletRequestBuilder,
        user: TestUser,
    ): Int = perform(request, user).response.status

    private fun perform(
        request: MockHttpServletRequestBuilder,
        user: TestUser,
    ): MvcResult = mockMvc.perform(request.with(authentication(user.token()))).andReturn()

    private fun trainee(name: String) = user("$name-${UUID.randomUUID()}@example.com", "ROLE_USER", "ROLE_ADMIN", "ROLE_DEMO")

    private fun user(
        email: String,
        vararg roles: String,
    ) = TestUser(email, UUID.randomUUID().toString(), roles.toList())

    data class TestUser(
        val email: String,
        val identity: String,
        val roles: List<String>,
    ) {
        /** Shaped like the authentik profile's own converter builds it: principal name = email claim. */
        fun token(): JwtAuthenticationToken {
            val jwt =
                Jwt
                    .withTokenValue("test")
                    .header("alg", "none")
                    .subject(identity)
                    .claim("email", email)
                    .claim("preferred_username", email)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build()
            return JwtAuthenticationToken(jwt, roles.map { SimpleGrantedAuthority(it) }, email)
        }
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
            // The authentik profile's issuer; any reachable OIDC issuer boots the app.
            val issuer = "http://127.0.0.1:${keycloak.getMappedPort(8080)}/realms/master"
            registry.add("spring.security.oauth2.client.provider.keycloakjwt.issuer-uri") { issuer }
            registry.add("spring.security.oauth2.client.provider.keycloakapi.issuer-uri") { issuer }
        }
    }
}