// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.valtimo.epistola.training.TraineeKeys
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

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
    private val response: HttpServletResponse = mock()
    private val filterChain: FilterChain = mock()

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `blocks a trainee-with-ROLE_ADMIN from a global admin endpoint`() {
        authenticateAs(TraineeKeys.TRAINEE_AUTHORITY, TraineeKeys.ADMIN_AUTHORITY)
        val request = requestFor("GET", "/api/management/v1/roles")

        filter.doFilter(request, response, filterChain)

        verify(response).sendError(eq(HttpServletResponse.SC_FORBIDDEN), any())
        verify(filterChain, never()).doFilter(request, response)
    }

    @Test
    fun `blocks a trainee-with-ROLE_ADMIN from a wildcard-matched admin endpoint`() {
        authenticateAs(TraineeKeys.TRAINEE_AUTHORITY, TraineeKeys.ADMIN_AUTHORITY)
        val request = requestFor("GET", "/api/management/v1/roles/ROLE_ADMIN/permissions")

        filter.doFilter(request, response, filterChain)

        verify(response).sendError(eq(HttpServletResponse.SC_FORBIDDEN), any())
    }

    @Test
    fun `blocks a trainee-with-ROLE_ADMIN from a double-wildcard admin subtree`() {
        authenticateAs(TraineeKeys.TRAINEE_AUTHORITY, TraineeKeys.ADMIN_AUTHORITY)
        val request = requestFor("POST", "/api/management/v1/dashboard/widget-configuration")

        filter.doFilter(request, response, filterChain)

        verify(response).sendError(eq(HttpServletResponse.SC_FORBIDDEN), any())
    }

    @Test
    fun `does not touch a genuine ROLE_ADMIN-only principal`() {
        authenticateAs(TraineeKeys.ADMIN_AUTHORITY)
        val request = requestFor("GET", "/api/management/v1/roles")

        filter.doFilter(request, response, filterChain)

        verify(filterChain).doFilter(request, response)
        verify(response, never()).sendError(any(), any())
    }

    @Test
    fun `does not block a trainee on an endpoint TrainingHttpSecurityConfigurer widens for them`() {
        authenticateAs(TraineeKeys.TRAINEE_AUTHORITY, TraineeKeys.ADMIN_AUTHORITY)
        val request = requestFor("GET", "/api/v1/plugin/configuration")

        filter.doFilter(request, response, filterChain)

        verify(filterChain).doFilter(request, response)
    }

    @Test
    fun `does not block the response-filtered admin overview endpoints`() {
        authenticateAs(TraineeKeys.TRAINEE_AUTHORITY, TraineeKeys.ADMIN_AUTHORITY)

        for (path in listOf("health", "versions", "changelog", "usage", "pending")) {
            val request = requestFor("GET", "/api/v1/plugin/epistola/admin/$path")
            val localChain: FilterChain = mock()

            filter.doFilter(request, response, localChain)

            verify(localChain).doFilter(request, response)
        }
    }

    @Test
    fun `blocks admin sub-resources with no safe per-trainee scoping`() {
        authenticateAs(TraineeKeys.TRAINEE_AUTHORITY, TraineeKeys.ADMIN_AUTHORITY)

        val blocked =
            listOf(
                "GET" to "/api/v1/plugin/epistola/admin/validations",
                "GET" to "/api/v1/plugin/epistola/admin/configurations/some-id/catalogs",
                "POST" to "/api/v1/plugin/epistola/admin/configurations/some-id/catalogs/some-slug/redeploy",
                "GET" to "/api/v1/plugin/epistola/admin/export/some-process-link-id",
                "POST" to "/api/v1/plugin/epistola/admin/pending/some-execution-id/reconcile",
                "GET" to "/api/v1/plugin/epistola/admin/forms/legacy-override",
            )

        for ((method, path) in blocked) {
            val request = requestFor(method, path)
            val localResponse: HttpServletResponse = mock()

            filter.doFilter(request, localResponse, filterChain)

            verify(localResponse).sendError(eq(HttpServletResponse.SC_FORBIDDEN), any())
        }
    }

    @Test
    fun `does nothing when there is no authentication`() {
        SecurityContextHolder.clearContext()
        val request = requestFor("GET", "/api/management/v1/roles")

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
}