// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.web.servlet.HandlerMapping
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

private const val TRAINEE = "trainee1-subject"
private const val LOGIN = "trainee1@demo"

/**
 * Unit coverage for [TraineeOwnershipInterceptor]'s own responsibility: routing a request to the
 * right [TraineeOwnershipChecks] call based on which path variable/query param is present, and
 * computing `allowShared`/read-only correctly per branch. The ownership-resolution logic itself
 * (fail-closed on an unresolvable id, the shared-template rules) is [TraineeOwnershipChecksTest]'s
 * job — this file mocks [TraineeOwnershipChecks] entirely so a wrong wire-up here can't hide behind
 * a coincidentally-correct resolver.
 */
class TraineeOwnershipInterceptorTest {
    private val ownershipChecks: TraineeOwnershipChecks = mock()
    private val interceptor = TraineeOwnershipInterceptor(ownershipChecks)

    @Test
    fun `short-circuits entirely for a non-trainee caller`() {
        whenever(ownershipChecks.currentTraineeOrNull()).thenReturn(null)
        val request = requestWithPathVariables("GET", "pluginConfigurationId" to "some-id")
        val response: HttpServletResponse = mock()

        val allowed = interceptor.preHandle(request, response, Any())

        assertThat(allowed).isTrue()
        verify(ownershipChecks, never()).isOwnPluginConfiguration(any(), any(), any())
    }

    @Test
    fun `falls through when no known path variable is present`() {
        whenever(ownershipChecks.currentTraineeOrNull()).thenReturn(Trainee(TRAINEE, LOGIN))
        val request = requestWithPathVariables("GET")
        val response: HttpServletResponse = mock()

        val allowed = interceptor.preHandle(request, response, Any())

        assertThat(allowed).isTrue()
    }

    @Test
    fun `pluginConfigurationId branch defers to isOwnPluginConfiguration with no allowShared`() {
        whenever(ownershipChecks.currentTraineeOrNull()).thenReturn(Trainee(TRAINEE, LOGIN))
        whenever(ownershipChecks.isOwnPluginConfiguration(TRAINEE, "own-id")).thenReturn(true)
        whenever(ownershipChecks.isOwnPluginConfiguration(TRAINEE, "other-id")).thenReturn(false)

        assertAllowed(requestWithPathVariables("GET", "pluginConfigurationId" to "own-id"))
        assertForbidden(requestWithPathVariables("GET", "pluginConfigurationId" to "other-id"))
    }

    @Test
    fun `processLinkId branch resolves to a process-definition id first, fails closed on a malformed UUID`() {
        whenever(ownershipChecks.currentTraineeOrNull()).thenReturn(Trainee(TRAINEE, LOGIN))
        val ownLinkId = UUID.randomUUID()
        val otherLinkId = UUID.randomUUID()
        whenever(ownershipChecks.resolveProcessDefinitionIdOfProcessLink(ownLinkId)).thenReturn("own-process-definition")
        whenever(ownershipChecks.resolveProcessDefinitionIdOfProcessLink(otherLinkId)).thenReturn("other-process-definition")
        whenever(ownershipChecks.isOwnProcessDefinition(TRAINEE, "own-process-definition")).thenReturn(true)
        whenever(ownershipChecks.isOwnProcessDefinition(TRAINEE, "other-process-definition")).thenReturn(false)

        assertAllowed(requestWithPathVariables("DELETE", "processLinkId" to ownLinkId.toString()))
        assertForbidden(requestWithPathVariables("DELETE", "processLinkId" to otherLinkId.toString()))
        // Not a UUID at all -> never reaches the resolver, fails closed rather than throwing.
        assertForbidden(requestWithPathVariables("DELETE", "processLinkId" to "not-a-uuid"))
    }

    @Test
    fun `processDefinitionId query param allows shared only for GET`() {
        whenever(ownershipChecks.currentTraineeOrNull()).thenReturn(Trainee(TRAINEE, LOGIN))
        whenever(ownershipChecks.isOwnProcessDefinition(TRAINEE, "some-id", allowShared = true)).thenReturn(true)
        whenever(ownershipChecks.isOwnProcessDefinition(TRAINEE, "some-id", allowShared = false)).thenReturn(false)

        val getRequest = requestWithPathVariables("GET")
        whenever(getRequest.getParameter("processDefinitionId")).thenReturn("some-id")
        assertThat(interceptor.preHandle(getRequest, mock(), Any())).isTrue()

        val postRequest = requestWithPathVariables("POST")
        whenever(postRequest.getParameter("processDefinitionId")).thenReturn("some-id")
        assertForbidden(postRequest)
    }

    @Test
    fun `case-definition management branch accepts any of the three Valtimo variable names, allows shared only for GET`() {
        whenever(ownershipChecks.currentTraineeOrNull()).thenReturn(Trainee(TRAINEE, LOGIN))
        whenever(ownershipChecks.isOwnCaseDefinition(TRAINEE, "form-flow-demo", allowShared = true)).thenReturn(true)
        whenever(ownershipChecks.isOwnCaseDefinition(TRAINEE, "form-flow-demo", allowShared = false)).thenReturn(false)

        for (variableName in listOf("caseDefinitionKey", "caseDefinitionName", "key")) {
            assertAllowed(requestWithPathVariables("GET", variableName to "form-flow-demo"))
            assertForbidden(requestWithPathVariables("PUT", variableName to "form-flow-demo"))
        }
    }

    @Test
    fun `document id branch defers to isOwnDocument with the caller's login, not their identity`() {
        whenever(ownershipChecks.currentTraineeOrNull()).thenReturn(Trainee(TRAINEE, LOGIN))
        whenever(ownershipChecks.isOwnDocument(LOGIN, "own-doc")).thenReturn(true)
        whenever(ownershipChecks.isOwnDocument(LOGIN, "other-doc")).thenReturn(false)

        assertAllowed(requestWithPathVariables("GET", "id" to "own-doc"))
        assertForbidden(requestWithPathVariables("DELETE", "id" to "other-doc"))
    }

    @Test
    fun `document-definition search branch allows own and shared case types, PBAC narrowing the results`() {
        whenever(ownershipChecks.currentTraineeOrNull()).thenReturn(Trainee(TRAINEE, LOGIN))
        whenever(ownershipChecks.isOwnCaseDefinition(TRAINEE, "form-flow-demo", allowShared = true)).thenReturn(true)
        whenever(ownershipChecks.isOwnCaseDefinition(TRAINEE, "other-trainees-dossier", allowShared = true)).thenReturn(false)

        assertAllowed(requestWithPathVariables("POST", "name" to "form-flow-demo"))
        assertForbidden(requestWithPathVariables("POST", "name" to "other-trainees-dossier"))
    }

    @Test
    fun `taskId branch defers to isOwnTask with the caller's login`() {
        whenever(ownershipChecks.currentTraineeOrNull()).thenReturn(Trainee(TRAINEE, LOGIN))
        whenever(ownershipChecks.isOwnTask(LOGIN, "own-task")).thenReturn(true)
        whenever(ownershipChecks.isOwnTask(LOGIN, "other-task")).thenReturn(false)

        assertAllowed(requestWithPathVariables("POST", "taskId" to "own-task"))
        assertForbidden(requestWithPathVariables("GET", "taskId" to "other-task"))
    }

    @Test
    fun `processInstanceId branch defers to isOwnProcessInstance with the caller's login`() {
        whenever(ownershipChecks.currentTraineeOrNull()).thenReturn(Trainee(TRAINEE, LOGIN))
        whenever(ownershipChecks.isOwnProcessInstance(LOGIN, "own-instance")).thenReturn(true)
        whenever(ownershipChecks.isOwnProcessInstance(LOGIN, "other-instance")).thenReturn(false)

        assertAllowed(requestWithPathVariables("POST", "processInstanceId" to "own-instance"))
        assertForbidden(requestWithPathVariables("POST", "processInstanceId" to "other-instance"))
    }

    @Test
    fun `configurationId branch allows shared only for GET, matching the catalogs-list vs redeploy split`() {
        whenever(ownershipChecks.currentTraineeOrNull()).thenReturn(Trainee(TRAINEE, LOGIN))
        whenever(ownershipChecks.isOwnPluginConfiguration(TRAINEE, "some-id", allowShared = true)).thenReturn(true)
        whenever(ownershipChecks.isOwnPluginConfiguration(TRAINEE, "some-id", allowShared = false)).thenReturn(false)

        assertAllowed(requestWithPathVariables("GET", "configurationId" to "some-id"))
        assertForbidden(requestWithPathVariables("POST", "configurationId" to "some-id"))
    }

    @Test
    fun `executionId branch defers to isOwnExecution with the caller's login`() {
        whenever(ownershipChecks.currentTraineeOrNull()).thenReturn(Trainee(TRAINEE, LOGIN))
        whenever(ownershipChecks.isOwnExecution(LOGIN, "own-execution")).thenReturn(true)
        whenever(ownershipChecks.isOwnExecution(LOGIN, "other-execution")).thenReturn(false)

        assertAllowed(requestWithPathVariables("POST", "executionId" to "own-execution"))
        assertForbidden(requestWithPathVariables("POST", "executionId" to "other-execution"))
    }

    @Test
    fun `a management mutation that names no case type is refused, a read or the draft endpoint is not`() {
        whenever(ownershipChecks.currentTraineeOrNull()).thenReturn(Trainee(TRAINEE, LOGIN))

        assertForbidden(requestWithPathVariables("POST", uri = "/api/management/v1/case-definition/something-new"))
        assertForbidden(requestWithPathVariables("DELETE", uri = "/api/management/v2/case/unknown"))
        assertAllowed(requestWithPathVariables("GET", uri = "/api/management/v1/case-definition/check"))
        assertAllowed(requestWithPathVariables("POST", uri = "/api/management/v1/case-definition/draft"))
        assertAllowed(requestWithPathVariables("POST", uri = "/api/v1/process-link"))
    }

    private fun assertAllowed(request: HttpServletRequest) {
        val response: HttpServletResponse = mock()

        val allowed = interceptor.preHandle(request, response, Any())

        assertThat(allowed).isTrue()
        verify(response, never()).status = HttpServletResponse.SC_FORBIDDEN
    }

    private fun assertForbidden(request: HttpServletRequest) {
        val body = StringWriter()
        val response: HttpServletResponse = mock()
        whenever(response.writer).thenReturn(PrintWriter(body))

        val allowed = interceptor.preHandle(request, response, Any())

        assertThat(allowed).isFalse()
        verify(response).status = HttpServletResponse.SC_FORBIDDEN
    }

    private fun requestWithPathVariables(
        method: String,
        vararg pathVariables: Pair<String, String>,
        uri: String? = null,
    ): HttpServletRequest {
        val request: HttpServletRequest = mock()
        whenever(request.method).thenReturn(method)
        whenever(request.requestURI).thenReturn(uri)
        whenever(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)).thenReturn(pathVariables.toMap())
        return request
    }
}