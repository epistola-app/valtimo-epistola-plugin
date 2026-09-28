// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant

class TraineeAdminAuthorityStripFilterTest {
    private val filter = TraineeAdminAuthorityStripFilter()

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `a trainee loses ROLE_ADMIN and keeps everything else, token and name included`() {
        val token = token("ROLE_USER", "ROLE_ADMIN", "ROLE_DEMO")

        val seen = runFilter(token)

        assertThat(seen.authorities.map { it.authority }).containsExactlyInAnyOrder("ROLE_USER", "ROLE_DEMO")
        assertThat(seen).isInstanceOf(JwtAuthenticationToken::class.java)
        assertThat((seen as JwtAuthenticationToken).token).isSameAs(token.token)
        assertThat(seen.name).isEqualTo("trainee@example.com")
    }

    @Test
    fun `a genuine admin is left untouched`() {
        val token = token("ROLE_USER", "ROLE_ADMIN")

        assertThat(runFilter(token)).isSameAs(token)
    }

    private fun runFilter(authentication: Authentication): Authentication {
        SecurityContextHolder.getContext().authentication = authentication
        var seen: Authentication? = null
        val chain = FilterChain { _, _ -> seen = SecurityContextHolder.getContext().authentication }
        filter.doFilter(mock<HttpServletRequest>(), mock<HttpServletResponse>(), chain)
        return seen!!
    }

    private fun token(vararg roles: String): JwtAuthenticationToken {
        val jwt =
            Jwt
                .withTokenValue("test")
                .header("alg", "none")
                .subject("subject")
                .claim("email", "trainee@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build()
        return JwtAuthenticationToken(jwt, roles.map { SimpleGrantedAuthority(it) }, "trainee@example.com")
    }
}