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
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.core.context.SecurityContextHolder

private const val SECRET = "correct-horse-battery-staple"

class TrainingFacilitySharedSecretAuthenticationFilterTest {
    private val filter = TrainingFacilitySharedSecretAuthenticationFilter(SECRET)
    private val filterChain: FilterChain = mock()

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `authenticates as ROLE_USER and ROLE_ADMIN when the header carries the correct secret`() {
        val request = requestWithSecretHeader(SECRET)
        val response: HttpServletResponse = mock()

        filter.doFilter(request, response, filterChain)

        val authentication = SecurityContextHolder.getContext().authentication
        assertThat(authentication).isNotNull
        assertThat(authentication!!.isAuthenticated).isTrue()
        assertThat(authentication.authorities.map { it.authority })
            .containsExactlyInAnyOrder("ROLE_USER", TraineeKeys.ADMIN_AUTHORITY)
        // Never TRAINEE_AUTHORITY - this principal must not be treated as a trainee, see the
        // class KDoc for why.
        assertThat(authentication.authorities.map { it.authority }).doesNotContain(TraineeKeys.TRAINEE_AUTHORITY)
        verify(filterChain).doFilter(request, response)
    }

    @Test
    fun `does not authenticate and does not reject when the header is absent`() {
        val request = requestWithSecretHeader(null)
        val response: HttpServletResponse = mock()

        filter.doFilter(request, response, filterChain)

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        verify(filterChain).doFilter(request, response)
    }

    @Test
    fun `does not authenticate and does not reject when the header carries the wrong secret`() {
        val request = requestWithSecretHeader("not-the-secret")
        val response: HttpServletResponse = mock()

        filter.doFilter(request, response, filterChain)

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        verify(filterChain).doFilter(request, response)
    }

    private fun requestWithSecretHeader(value: String?): HttpServletRequest {
        val request: HttpServletRequest = mock()
        whenever(request.getHeader(TrainingFacilitySharedSecretAuthenticationFilter.SECRET_HEADER)).thenReturn(value)
        return request
    }
}