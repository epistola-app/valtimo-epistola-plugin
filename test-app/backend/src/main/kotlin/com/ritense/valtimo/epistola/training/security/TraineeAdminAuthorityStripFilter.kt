// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.valtimo.epistola.training.TraineeKeys
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Removes [TraineeKeys.ADMIN_AUTHORITY] from a trainee's server-side authorities, so Valtimo's own
 * PBAC (`config/pbac/demo.permission.json`) is what scopes a trainee's case data — not the
 * interceptor layer fighting an unconditioned `ROLE_ADMIN` grant.
 *
 * Why the token still carries `ROLE_ADMIN` at all: Valtimo's admin Angular menu and route guards
 * are gated on it client-side, and the frontend reads roles straight from the token claims
 * (`test-app/frontend/src/environments/auth/authentik-config.ts`, `extractRoles`), never from the
 * backend. So the identity provider keeps emitting it, the admin screens keep rendering, and only
 * the backend stops honouring it. The admin endpoints a trainee legitimately needs for their own
 * dossier are widened to [TraineeKeys.TRAINEE_AUTHORITY] instead, in
 * [TrainingHttpSecurityConfigurer].
 *
 * Why it matters: PBAC unions every grant across every role a principal carries, and this app's
 * `all.permission.json` grants `ROLE_ADMIN` every case, task, note and document unconditioned. As
 * long as a trainee carried it, no conditioned `ROLE_DEMO` grant could narrow anything — see
 * docs/training-facility.md, "The critical finding".
 *
 * Registered by [TrainingHttpSecurityConfigurer] ahead of every other training filter and of
 * Spring's `AuthorizationFilter`, so both the HTTP gate and every later PBAC check see the reduced
 * authorities. Only exists under the `training` profile; without it, nothing here runs and the
 * app is exactly as shipped. Keyed on [TraineeKeys.TRAINEE_AUTHORITY] alone: genuine admins never
 * carry it and are never touched.
 */
class TraineeAdminAuthorityStripFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication != null && isTraineeWithAdmin(authentication)) {
            val context = SecurityContextHolder.createEmptyContext()
            context.authentication = withoutAdmin(authentication)
            SecurityContextHolder.setContext(context)
        }
        filterChain.doFilter(request, response)
    }

    private fun isTraineeWithAdmin(authentication: Authentication): Boolean =
        authentication.authorities.any { it.authority == TraineeKeys.TRAINEE_AUTHORITY } &&
            authentication.authorities.any { it.authority == TraineeKeys.ADMIN_AUTHORITY }

    companion object {
        /**
         * Keeps the token type where it matters: the authentik profile's user service reads the
         * JWT back off a [JwtAuthenticationToken] (`OidcAuthenticationConfiguration`), so a
         * replacement of a different type would lose the user's email/name. Any other type still
         * loses the authority (fail closed), keeping its principal and name.
         */
        fun withoutAdmin(authentication: Authentication): Authentication {
            val authorities: List<GrantedAuthority> = authentication.authorities.filter { it.authority != TraineeKeys.ADMIN_AUTHORITY }
            val stripped: AbstractAuthenticationToken =
                when (authentication) {
                    is JwtAuthenticationToken -> JwtAuthenticationToken(authentication.token, authorities, authentication.name)
                    else ->
                        UsernamePasswordAuthenticationToken.authenticated(
                            authentication.principal,
                            authentication.credentials,
                            authorities,
                        )
                }
            stripped.details = authentication.details
            return stripped
        }
    }
}