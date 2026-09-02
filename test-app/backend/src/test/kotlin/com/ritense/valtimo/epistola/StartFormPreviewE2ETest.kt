// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola

import app.epistola.valtimo.service.EpistolaService
import app.epistola.valtimo.web.rest.EpistolaGenerationResource
import app.epistola.valtimo.web.rest.dto.StartPreviewRequest
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import com.ritense.authorization.AuthorizationContext.Companion.runWithoutAuthorization
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayInputStream
import java.time.Duration

/**
 * End-to-end test for the start-event preview (issue #67), booting the real test-app [Application]
 * against Testcontainers Postgres + Keycloak.
 *
 * <p>Unit tests mock the process-link lookup, so they cannot catch a wrong process definition key, a
 * missing start-event link, or a mapping that does not match the start form. This exercises the
 * **real deployed** `permit` configuration: the `permit-confirmation` process, its
 * `generate-confirmation` link, and the `permit-start` form whose fields the link's dataMapping
 * reads via `$doc.*`.
 *
 * <p>The point being proved is the one that is hardest to trust from mocks: that a preview renders
 * from start-form input alone, with **no case document in existence** — `$doc` is the caller's
 * overrides and nothing else.
 *
 * <p>[EpistolaService] is mocked so no real Epistola is needed; the assertion is on the data that
 * reaches it.
 *
 * <p>Controller calls run inside `runWithoutAuthorization`, matching the convention of the other
 * E2E tests here (there is no `spring-security-test` on this classpath). This test therefore covers
 * the **data path**, not the gate — the authorization contract, including the
 * document-VIEW-independent-of-execution-CREATE rule, is pinned by
 * `EpistolaGenerationResourceStartPreviewAuthorizationTest` in the plugin module.
 */
@SpringBootTest(classes = [com.ritense.valtimo.Application::class])
@ActiveProfiles("test")
class StartFormPreviewE2ETest {
    @MockitoBean
    lateinit var epistolaService: EpistolaService

    @Autowired
    lateinit var generationResource: EpistolaGenerationResource

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private fun givenEpistolaRenders() {
        // anyOrNull for variantId/environmentId: this link configures neither, and mockito-kotlin's
        // any() does not match null — the stub would silently not apply and the mock return null.
        whenever(
            epistolaService.previewDocument(
                any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any(),
            ),
        ).thenReturn(ByteArrayInputStream(byteArrayOf(0x25, 0x50, 0x44, 0x46)))
    }

    private fun startPreviewRequest(documentId: String? = null) =
        StartPreviewRequest(
            "permit-confirmation",
            "generate-confirmation",
            documentId,
            mapOf(
                "doc" to
                    mapOf(
                        "applicant" to mapOf("firstName" to "Jan", "lastName" to "de Vries"),
                        "property" to mapOf("kadastraalNummer" to "ASD01-A-1234"),
                    ),
            ),
        )

    /**
     * The headline case: a letter previewed from a start form before any case exists. Proves the
     * whole chain against real deployed config — key resolves to a definition, the definition has
     * the generate-confirmation link, and the link's `$doc.*` mapping resolves purely from the
     * caller's overrides.
     */
    @Test
    fun `previews a letter from start-form input with no case document in existence`() {
        givenEpistolaRenders()

        val response = runWithoutAuthorization { generationResource.previewStartDocument(startPreviewRequest()) }

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val data = argumentCaptor<Map<String, Any>>()
        verifyPreviewCalledWith(data)
        @Suppress("UNCHECKED_CAST")
        val applicant = data.firstValue["applicant"] as Map<String, Any>
        assertThat(applicant["firstName"]).isEqualTo("Jan")
        assertThat(applicant["lastName"]).isEqualTo("de Vries")
    }

    private fun verifyPreviewCalledWith(captor: org.mockito.kotlin.KArgumentCaptor<Map<String, Any>>) {
        org.mockito.kotlin.verify(epistolaService).previewDocument(
            any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), captor.capture(),
        )
    }

    /**
     * The real deployed link, not a mock, must be the one that is found. Pins that the demo
     * fixture's key/activity pair stays valid — if either is renamed, this fails rather than the
     * start form silently showing an error at runtime.
     */
    @Test
    fun `resolves the real generate-confirmation link from the deployed permit process`() {
        givenEpistolaRenders()

        val response = runWithoutAuthorization { generationResource.previewStartDocument(startPreviewRequest()) }

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        org.mockito.kotlin.verify(epistolaService).previewDocument(
            any(), any(), any(),
            eq("municipality-demo"),
            eq("bevestigingsbrief-vergunning"),
            anyOrNull(), anyOrNull(), any(),
        )
    }

    @Test
    fun `returns 404 for a process definition key that is not deployed`() {
        val response =
            runWithoutAuthorization {
                generationResource.previewStartDocument(
                    StartPreviewRequest("no-such-process", "generate-confirmation", null, null),
                )
            }

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    /**
     * The activity id arrives from the wire on this endpoint, so an activity that is not a
     * generate-document link must not be previewable. `startEvent` is a real activity on this
     * process, but it is a form link.
     */
    @Test
    fun `returns 404 for an activity that is not a generate-document link`() {
        val response =
            runWithoutAuthorization {
                generationResource.previewStartDocument(
                    StartPreviewRequest("permit-confirmation", "startEvent", null, null),
                )
            }

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `returns 404 for a document id that does not exist`() {
        val response =
            runWithoutAuthorization {
                generationResource.previewStartDocument(
                    startPreviewRequest(documentId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"),
                )
            }

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    /**
     * A smuggled `taskId` is inert, and this pins *why* — which is not what one might assume.
     *
     * Spring Boot disables `FAIL_ON_UNKNOWN_PROPERTIES`, so the real application mapper **drops**
     * the unknown field rather than rejecting the request. (A `@JsonIgnoreProperties(ignoreUnknown
     * = false)` on the DTO would not change that: it declines to opt out of failing, it does not
     * opt in.) The safety therefore comes from the DTO having no such field for anything to read,
     * and from `generateStartPreview` hard-coding `processInstanceId = null` — not from rejection.
     *
     * Asserted against the real application mapper precisely because a plain `new ObjectMapper()`
     * behaves differently, which is what made the original assumption look correct in unit tests.
     */
    @Test
    fun `silently ignores a smuggled taskId, which no code path can read`() {
        val body =
            """
            {"processDefinitionKey":"permit-confirmation",
             "sourceActivityId":"generate-confirmation",
             "taskId":"smuggled-task-id"}
            """.trimIndent()

        val parsed = objectMapper.readValue(body, StartPreviewRequest::class.java)

        assertThat(parsed.processDefinitionKey()).isEqualTo("permit-confirmation")
        // Nothing on the record carries it, so nothing downstream can act on it.
        assertThat(StartPreviewRequest::class.java.recordComponents.map { it.name })
            .doesNotContain("taskId", "processInstanceId")
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
            // Force IPv4: "localhost" can resolve to ::1, but the container port is published on
            // 127.0.0.1 — the JVM's discovery RestTemplate otherwise gets a ConnectException.
            val issuer = "http://127.0.0.1:${keycloak.getMappedPort(8080)}/realms/master"
            registry.add("spring.security.oauth2.client.provider.keycloakjwt.issuer-uri") { issuer }
            registry.add("spring.security.oauth2.client.provider.keycloakapi.issuer-uri") { issuer }
        }
    }
}
