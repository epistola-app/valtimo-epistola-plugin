// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.valtimo.epistola.training.TraineeKeys
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Trainees carry real [TraineeKeys.ADMIN_AUTHORITY] (see that constant's KDoc) so Valtimo's own
 * frontend admin routes work for them — this filter is the compensating control, and it is the
 * only thing standing between that grant and a trainee reaching every other Valtimo admin
 * endpoint. No Spring context needed: [TraineeAdminSurfaceGuardFilter] only reads
 * [SecurityContextHolder] and the raw request, so a plain unit test with mocked servlet types is
 * enough to lock in the block/allow boundary without a full boot.
 */
class TraineeAdminSurfaceGuardFilterTest {
    private val filter = TraineeAdminSurfaceGuardFilter()
    private val filterChain: FilterChain = mock()

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `blocks a trainee-with-ROLE_ADMIN from a global admin endpoint`() {
        authenticateAs(TraineeKeys.TRAINEE_AUTHORITY, TraineeKeys.ADMIN_AUTHORITY)
        val request = requestFor("GET", "/api/management/v1/roles")
        val (response, _) = responseWithWriter()

        filter.doFilter(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_FORBIDDEN
        verify(filterChain, never()).doFilter(request, response)
    }

    @Test
    fun `the 403 body explains why, not just that it was denied`() {
        // A trainee hitting this should be able to tell why from the response alone, not just
        // that they were forbidden — every BlockedEndpoint carries its own specific reason.
        // Regression coverage for a real bug found over real HTTP: sendError(403, message)'s
        // message never actually reached the client (Spring Boot strips it by default) — see
        // TraineeRejection.kt's KDoc.
        authenticateAs(TraineeKeys.TRAINEE_AUTHORITY, TraineeKeys.ADMIN_AUTHORITY)
        val (response, body) = responseWithWriter()

        filter.doFilter(requestFor("GET", "/api/management/v1/roles"), response, filterChain)

        assertThat(body.toString()).contains("roles and permissions")
    }

    @Test
    fun `blocks a trainee-with-ROLE_ADMIN from a wildcard-matched admin endpoint`() {
        authenticateAs(TraineeKeys.TRAINEE_AUTHORITY, TraineeKeys.ADMIN_AUTHORITY)
        val request = requestFor("GET", "/api/management/v1/roles/ROLE_ADMIN/permissions")
        val (response, _) = responseWithWriter()

        filter.doFilter(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_FORBIDDEN
    }

    @Test
    fun `blocks a trainee-with-ROLE_ADMIN from a double-wildcard admin subtree`() {
        authenticateAs(TraineeKeys.TRAINEE_AUTHORITY, TraineeKeys.ADMIN_AUTHORITY)
        val request = requestFor("POST", "/api/management/v1/dashboard/widget-configuration")
        val (response, _) = responseWithWriter()

        filter.doFilter(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_FORBIDDEN
    }

    @Test
    fun `does not touch a genuine ROLE_ADMIN-only principal`() {
        authenticateAs(TraineeKeys.ADMIN_AUTHORITY)
        val request = requestFor("GET", "/api/management/v1/roles")
        val response: HttpServletResponse = mock()

        filter.doFilter(request, response, filterChain)

        verify(filterChain).doFilter(request, response)
        verify(response, never()).status = HttpServletResponse.SC_FORBIDDEN
    }

    @Test
    fun `does not block a trainee on an endpoint TrainingHttpSecurityConfigurer widens for them`() {
        authenticateAs(TraineeKeys.TRAINEE_AUTHORITY, TraineeKeys.ADMIN_AUTHORITY)
        val request = requestFor("GET", "/api/v1/plugin/configuration")
        val response: HttpServletResponse = mock()

        filter.doFilter(request, response, filterChain)

        verify(filterChain).doFilter(request, response)
    }

    @Test
    fun `does not block the global, identity-independent case-management checks`() {
        // case-definition/check and metroline/available-modes depend only on deployment-wide
        // flags (CaseDefinitionCheckerImpl.canUpdateGlobalConfiguration, whether a
        // ZaakMetrolineDataService bean exists), never on the caller — confirmed against Valtimo
        // 13.44.0 source after a trainee got real 403s loading /case-management in the browser.
        authenticateAs(TraineeKeys.TRAINEE_AUTHORITY, TraineeKeys.ADMIN_AUTHORITY)

        for (path in listOf("/api/management/v1/case-definition/check", "/api/management/v1/metroline/available-modes")) {
            val request = requestFor("GET", path)
            val response: HttpServletResponse = mock()
            val localChain: FilterChain = mock()

            filter.doFilter(request, response, localChain)

            verify(localChain).doFilter(request, response)
        }
    }

    @Test
    fun `does not block the response-filtered admin overview endpoints`() {
        authenticateAs(TraineeKeys.TRAINEE_AUTHORITY, TraineeKeys.ADMIN_AUTHORITY)

        for (path in listOf("health", "versions", "changelog", "usage", "pending")) {
            val request = requestFor("GET", "/api/v1/plugin/epistola/admin/$path")
            val response: HttpServletResponse = mock()
            val localChain: FilterChain = mock()

            filter.doFilter(request, response, localChain)

            verify(localChain).doFilter(request, response)
        }
    }

    @Test
    fun `blocks admin sub-resources with no safe per-trainee scoping`() {
        authenticateAs(TraineeKeys.TRAINEE_AUTHORITY, TraineeKeys.ADMIN_AUTHORITY)

        // catalog listing/redeploy, export, and reconcile used to be here too, until
        // ProcessInstanceOwnershipResolver made them scopable via TraineeOwnershipInterceptor
        // instead — see TraineeOwnershipInterceptorTest for their allow/deny coverage now.
        val blocked =
            listOf(
                "GET" to "/api/v1/plugin/epistola/admin/validations",
                "GET" to "/api/v1/plugin/epistola/admin/forms/legacy-override",
            )

        for ((method, path) in blocked) {
            val request = requestFor(method, path)
            val (localResponse, _) = responseWithWriter()

            filter.doFilter(request, localResponse, filterChain)

            verify(localResponse).status = HttpServletResponse.SC_FORBIDDEN
        }
    }

    @Test
    fun `does not block the endpoints scoped instead by TraineeOwnershipInterceptor`() {
        authenticateAs(TraineeKeys.TRAINEE_AUTHORITY, TraineeKeys.ADMIN_AUTHORITY)

        val nowScoped =
            listOf(
                "GET" to "/api/v1/plugin/epistola/admin/configurations/some-id/catalogs",
                "POST" to "/api/v1/plugin/epistola/admin/configurations/some-id/catalogs/some-slug/redeploy",
                "GET" to "/api/v1/plugin/epistola/admin/export/some-process-link-id",
                "POST" to "/api/v1/plugin/epistola/admin/pending/some-execution-id/reconcile",
                "POST" to "/api/v1/process/some-process-instance-id/delete",
            )

        for ((method, path) in nowScoped) {
            val request = requestFor(method, path)
            val response: HttpServletResponse = mock()
            val localChain: FilterChain = mock()

            filter.doFilter(request, response, localChain)

            verify(localChain).doFilter(request, response)
        }
    }

    @Test
    fun `does nothing when there is no authentication`() {
        SecurityContextHolder.clearContext()
        val request = requestFor("GET", "/api/management/v1/roles")
        val response: HttpServletResponse = mock()

        filter.doFilter(request, response, filterChain)

        verify(filterChain).doFilter(request, response)
    }

    private fun authenticateAs(vararg authorities: String) {
        val token =
            TestingAuthenticationToken(
                "trainee1@demo",
                "n/a",
                authorities.map { SimpleGrantedAuthority(it) },
            )
        token.isAuthenticated = true
        SecurityContextHolder.getContext().authentication = token
    }

    private fun requestFor(
        method: String,
        uri: String,
    ): HttpServletRequest {
        val request: HttpServletRequest = mock()
        whenever(request.method).thenReturn(method)
        whenever(request.requestURI).thenReturn(uri)
        return request
    }

    /** [rejectAsForbidden] writes through `response.writer`, so a mock needs a real one backing it. */
    private fun responseWithWriter(): Pair<HttpServletResponse, StringWriter> {
        val body = StringWriter()
        val response: HttpServletResponse = mock()
        whenever(response.writer).thenReturn(PrintWriter(body))
        return response to body
    }
}