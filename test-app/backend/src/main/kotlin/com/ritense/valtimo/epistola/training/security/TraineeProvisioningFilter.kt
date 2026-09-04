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
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Registered by [TrainingHttpSecurityConfigurer] to run after authentication but before
 * `authorizeHttpRequests` decides. Any authenticated principal that isn't already staff
 * (`ROLE_ADMIN`) is treated as a trainee: their dossier is provisioned on first sight (a cheap
 * existence check on every later request), and [TraineeKeys.TRAINEE_AUTHORITY] is added to their
 * authorities so the widened matchers and the ownership interceptor/advice recognise them.
 *
 * This is the in-process alternative to assigning the role via the IdP's own admin API — it means
 * *any* authenticated non-admin login becomes a trainee. That's a real, security-relevant default
 * to confirm deliberately rather than inherit by accident.
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
        if (authentication == null ||
            !authentication.isAuthenticated ||
            authentication is AnonymousAuthenticationToken ||
            authentication.authorities.any { it.authority == TraineeKeys.ADMIN_AUTHORITY }
        ) {
            filterChain.doFilter(request, response)
            return
        }

        provisioningService.ensureDossier(TraineeIdentity.resolve(authentication))

        if (authentication.authorities.none { it.authority == TraineeKeys.TRAINEE_AUTHORITY }) {
            SecurityContextHolder.getContext().authentication = TraineeAugmentedAuthentication(authentication)
        }

        filterChain.doFilter(request, response)
    }
}

private class TraineeAugmentedAuthentication(
    private val delegate: Authentication,
) : Authentication by delegate {
    private val augmentedAuthorities: Collection<GrantedAuthority> =
        delegate.authorities + SimpleGrantedAuthority(TraineeKeys.TRAINEE_AUTHORITY)

    override fun getAuthorities(): Collection<GrantedAuthority> = augmentedAuthorities
}