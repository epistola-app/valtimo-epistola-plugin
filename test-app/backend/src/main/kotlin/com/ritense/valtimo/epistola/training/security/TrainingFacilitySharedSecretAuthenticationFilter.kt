// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.valtimo.epistola.training.TraineeKeys
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Lets something outside the running app — a monitoring tool, an instructor dashboard, whatever
 * drives the training facility's own idea of "which trainees have done what" — authenticate
 * against Valtimo's full REST API with a static shared secret, no human login and no Keycloak
 * client involved.
 *
 * Deliberately **not** a Keycloak client-credentials grant, even though `test-app/backend` already
 * runs `oauth2ResourceServer` and would have accepted one without any new Valtimo code at all —
 * ruled out specifically to avoid a new piece of Keycloak realm/client configuration to keep in
 * sync. This mirrors epistola-suite's own `DemoSharedSecretAuthenticationFilter` instead: a single
 * static credential, checked directly, entirely inside this application.
 *
 * **This is a total bypass of Valtimo's normal login flow**, exactly like Epistola's version is a
 * total bypass of its tenant/permission model — the principal it installs carries real `ROLE_USER`
 * + [TraineeKeys.ADMIN_AUTHORITY], the same authority every trainee and every genuine admin
 * account carries, with no narrower role to grant instead (see [TrainingFacilitySharedSecretAuthenticationFilterTest]
 * and the training-facility doc's own callout for why: the whole point was exposing the whole API
 * to a monitoring tool, not building and maintaining a bespoke read-only "progress" endpoint).
 * Deliberately **not** [TraineeKeys.TRAINEE_AUTHORITY] — that role means "trainee," which would
 * both provision this credential a pointless dossier of its own on first use
 * ([TraineeProvisioningFilter]) and scope every other check in this package down to "your own
 * dossier only" ([TraineeOwnershipInterceptor] and friends) — the opposite of the cross-trainee
 * visibility a monitoring tool needs.
 *
 * Only wired in at all when [com.ritense.valtimo.epistola.training.TrainingProperties.facilitySharedSecret]
 * is configured (see `TrainingConfiguration`) — blank, the default, means this entire access path
 * does not exist. Uses a dedicated header (`X-Training-Facility-Secret`), not the `Authorization`
 * header — that header's `Bearer` scheme is already claimed by Spring's own OAuth2 resource-server
 * JWT filter, which would otherwise attempt to decode this static secret as a JWT and fail before
 * this filter ever got a chance to run.
 *
 * The secret is kept only as its digest, and candidates are digested before comparison, which
 * makes [MessageDigest.isEqual] a fixed-width compare — neither the secret's value nor its length
 * is observable in the time this takes, the same reasoning epistola-suite's own version documents.
 */
class TrainingFacilitySharedSecretAuthenticationFilter(
    secret: String,
) : OncePerRequestFilter() {
    private val secretDigest: ByteArray = sha256(secret)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val candidate = request.getHeader(SECRET_HEADER)
        if (candidate != null && matchesSecret(candidate)) {
            SecurityContextHolder.getContext().authentication = FACILITY_AUTHENTICATION
        }
        filterChain.doFilter(request, response)
    }

    private fun matchesSecret(candidate: String): Boolean = MessageDigest.isEqual(sha256(candidate), secretDigest)

    companion object {
        const val SECRET_HEADER = "X-Training-Facility-Secret"

        private val FACILITY_AUTHENTICATION =
            PreAuthenticatedAuthenticationToken(
                "training-facility",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER"), SimpleGrantedAuthority(TraineeKeys.ADMIN_AUTHORITY)),
            )

        private fun sha256(value: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    }
}