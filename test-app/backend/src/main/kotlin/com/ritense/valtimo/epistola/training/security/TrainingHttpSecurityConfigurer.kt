// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.valtimo.contract.security.config.HttpConfigurerConfigurationException
import com.ritense.valtimo.contract.security.config.HttpSecurityConfigurer
import com.ritense.valtimo.epistola.training.TraineeKeys
import org.springframework.http.HttpMethod.DELETE
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher

/**
 * Widens the flat `hasAuthority(ADMIN)` HTTP gate Valtimo puts on process-link and
 * plugin-configuration endpoints (`ProcessLinkHttpSecurityConfigurer`, `PluginHttpSecurityConfigurer`
 * in `valtimo-platform/valtimo` — confirmed directly from source: no PBAC hook exists for either)
 * to also accept [TraineeKeys.TRAINEE_AUTHORITY], so a trainee never needs real `ROLE_ADMIN`.
 * [TraineeOwnershipInterceptor], [TraineeOwnershipRequestBodyAdvice] and
 * [TraineeOwnershipResponseBodyAdvice] then narrow a trainee down to only their own dossier (plus
 * read-only access to the shared template).
 *
 * Deliberately scoped to plugin-configuration and process-link only — the broader
 * `CaseHttpSecurityConfigurer` surface (case-tab management, case export/import, list-columns,
 * startable items, widget tabs) is NOT widened here, so trainees stay blocked from it by Valtimo's
 * own default `ROLE_ADMIN` gate until an equivalent ownership check is built for it too.
 *
 * **Needs empirical verification**: this relies on Valtimo aggregating [HttpSecurityConfigurer]
 * beans in `@Order` sequence with first-match-wins `authorizeHttpRequests` semantics, and on this
 * bean's very low `@Order` (see `TrainingConfiguration`) actually making these rules apply before
 * Valtimo's own module configurers register their stricter ones for the same paths. Confirm a
 * `ROLE_TRAINEE`-only principal can reach these endpoints, and that Valtimo's own admin-only
 * endpoints elsewhere are still denied, against a real running app before trusting this.
 */
class TrainingHttpSecurityConfigurer(
    private val traineeProvisioningFilter: TraineeProvisioningFilter,
) : HttpSecurityConfigurer {
    override fun configure(http: HttpSecurity) {
        try {
            http.authorizeHttpRequests { requests ->
                requests
                    .requestMatchers(antMatcher(GET, PLUGIN_CONFIGURATION_URL))
                    .hasAnyAuthority(*TRAINEE_OR_ADMIN)
                    .requestMatchers(antMatcher(POST, PLUGIN_CONFIGURATION_URL))
                    .hasAnyAuthority(*TRAINEE_OR_ADMIN)
                    .requestMatchers(
                        antMatcher(PUT, "$PLUGIN_CONFIGURATION_URL/{pluginConfigurationId}"),
                    ).hasAnyAuthority(*TRAINEE_OR_ADMIN)
                    .requestMatchers(
                        antMatcher(DELETE, "$PLUGIN_CONFIGURATION_URL/{pluginConfigurationId}"),
                    ).hasAnyAuthority(*TRAINEE_OR_ADMIN)
                    .requestMatchers(antMatcher(GET, PROCESS_LINK_URL))
                    .hasAnyAuthority(*TRAINEE_OR_ADMIN)
                    .requestMatchers(antMatcher(GET, "$PROCESS_LINK_URL/types"))
                    .hasAnyAuthority(*TRAINEE_OR_ADMIN)
                    .requestMatchers(antMatcher(POST, PROCESS_LINK_URL))
                    .hasAnyAuthority(*TRAINEE_OR_ADMIN)
                    .requestMatchers(antMatcher(PUT, PROCESS_LINK_URL))
                    .hasAnyAuthority(*TRAINEE_OR_ADMIN)
                    .requestMatchers(antMatcher(DELETE, "$PROCESS_LINK_URL/{processLinkId}"))
                    .hasAnyAuthority(*TRAINEE_OR_ADMIN)
            }
            http.addFilterBefore(traineeProvisioningFilter, AuthorizationFilter::class.java)
        } catch (e: Exception) {
            throw HttpConfigurerConfigurationException(e)
        }
    }

    companion object {
        private const val PLUGIN_CONFIGURATION_URL = "/api/v1/plugin/configuration"
        private const val PROCESS_LINK_URL = "/api/v1/process-link"
        private val TRAINEE_OR_ADMIN = arrayOf(TraineeKeys.ADMIN_AUTHORITY, TraineeKeys.TRAINEE_AUTHORITY)
    }
}