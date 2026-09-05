// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.valtimo.epistola.training.TraineeDossierProvisioningService
import com.ritense.valtimo.epistola.training.TraineeIdentity
import com.ritense.valtimo.epistola.training.TraineeKeys
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Registered by [TrainingHttpSecurityConfigurer] to run after authentication but before
 * `authorizeHttpRequests` decides. A principal only becomes a trainee by explicitly carrying the
 * real [TraineeKeys.TRAINEE_AUTHORITY] Keycloak role — this filter never adds it. That's what lets
 * this instance also serve genuine non-admin, non-trainee users unaffected: `ROLE_ADMIN` staff are
 * never touched, and a plain `ROLE_USER` login without the trainee role is left alone too, not
 * swept in by default.
 *
 * The only thing this filter does for a qualifying principal is ensure their dossier exists (a
 * cheap existence check on every request after the first) — the widened matchers and the
 * ownership interceptor/advice recognise them purely from the authority already being there.
 */
class TraineeProvisioningFilter(
    private val provisioningService: TraineeDossierProvisioningService,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        val isTrainee =
            authentication != null &&
                authentication.isAuthenticated &&
                authentication.authorities.any { it.authority == TraineeKeys.TRAINEE_AUTHORITY } &&
                authentication.authorities.none { it.authority == TraineeKeys.ADMIN_AUTHORITY }

        if (isTrainee) {
            provisioningService.ensureDossier(TraineeIdentity.resolve(authentication!!))
        }

        filterChain.doFilter(request, response)
    }
}