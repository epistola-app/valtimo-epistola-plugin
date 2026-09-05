// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.valtimo.epistola.training.TraineeKeys
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpMethod
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Hard-blocks every Valtimo admin operation a trainee must not reach, now that trainees carry real
 * [TraineeKeys.ADMIN_AUTHORITY] (see that constant's KDoc for why: Valtimo's own admin Angular
 * routes/menu are hard-gated to `ROLE_ADMIN` client-side, with no finer-grained frontend role to
 * widen instead, so satisfying the frontend meant granting the real authority).
 *
 * Real `ROLE_ADMIN` satisfies every one of Valtimo's flat `hasAuthority(ADMIN)` HTTP gates
 * directly — the ~45 endpoints [TrainingHttpSecurityConfigurer] deliberately widens are no longer
 * the only ones a trainee can reach; every *other* admin-gated endpoint in the running app becomes
 * reachable too, unless something blocks it. That's what this filter does, for exactly the
 * endpoint families confirmed (against Valtimo 13.44.0 source) to have no PBAC hook and no
 * per-resource scoping of their own:
 *
 * - Access Control (`ValtimoAuthorizationHttpSecurityConfigurer`) — the PBAC editor itself; left
 *   open, a trainee could grant themselves any permission, including this plugin's own
 *   `EpistolaAdministration:MANAGE`.
 * - Translation management (`LocalizationHttpSecurityConfigurer`) — global i18n keys, shared by
 *   every user of the instance.
 * - Choice fields (`ChoiceFieldHttpSecurityConfigurer`) — global reference data.
 * - Object management configuration (`ObjectManagementHttpSecurityConfigurer`) — global
 *   Objects-API integration config, not case data.
 * - Forms (`FormHttpSecurityConfigurerKotlin`) — only the *global* form-definition CRUD; the
 *   case-scoped form endpoints under the case-definition management prefix are already covered by
 *   [TraineeOwnershipInterceptor] (same path prefix, same `caseDefinitionKey` variable it already
 *   recognises), so nothing extra is needed there.
 * - System processes (`ProcessDefinitionManagementHttpSecurityConfigurer`) — the case-*unlinked*
 *   process-definition surface [TrainingHttpSecurityConfigurer]'s KDoc already named as
 *   deliberately not widened; plus three ADMIN-gated mutations buried in the otherwise-`authenticated()`
 *   `ProcessHttpSecurityConfigurer`: process migration, force-deleting a process instance, and raw
 *   BPMN deployment.
 * - Decision tables (`DecisionHttpSecurityConfigurer`) — only the *global* list; the case-scoped
 *   decision-definition endpoints are, like Forms, already covered by the existing interceptor.
 * - Logs (`LoggingHttpSecurityConfigurer`).
 * - Case migration (`DocumentMigrationHttpSecurityConfigurer`) — migrates data across
 *   document-definitions with no case-key scoping at all.
 * - Dashboard management (`DashboardHttpSecurityConfigurer`).
 * - The four case-definition endpoints [TrainingHttpSecurityConfigurer]'s KDoc already documents
 *   as deliberately not widened (`draft`, `case/import`, `case/import/preview`, `case-definition/check`,
 *   `metroline/available-modes`) — those were only safe to leave alone while trainees had no real
 *   `ROLE_ADMIN`; now they need active blocking like everything else here.
 * - This plugin's own admin page, `/api/v1/plugin/epistola/admin` and everything under it —
 *   normally gated by the `EpistolaAdministration:MANAGE` PBAC permission (seeded to `ROLE_ADMIN`
 *   by default), blocked here too as a second, independent layer rather than relying on a
 *   PBAC-changeset revocation.
 *
 * Implemented as a filter — not more `authorizeHttpRequests` entries — deliberately: Valtimo
 * combines every registered [com.ritense.valtimo.contract.security.config.HttpSecurityConfigurer]
 * bean's rules onto one shared, first-match-wins matcher list (confirmed against
 * `ValtimoCoreSecurityFactory` source), so an *allow* rule can lose an ordering race against
 * ~80 other auto-configured beans — [TrainingHttpSecurityConfigurer]'s own KDoc already flags this
 * as unverified for widening. A *block*, registered via `addFilterBefore(_, AuthorizationFilter::class.java)`
 * the same way [TraineeProvisioningFilter] already is, has no such race: filter position is a
 * structural property of the assembled chain, fixed once at `.build()`, independent of any bean's
 * `@Order` — so this always runs before Spring Security's authorization decision, whichever
 * configurer bean would otherwise have allowed the request.
 *
 * Every path below is copied verbatim from Valtimo 13.44.0 source, not approximated — an
 * inaccurate pattern here is worse than an omission, since it would silently fail to block.
 */
class TraineeAdminSurfaceGuardFilter : OncePerRequestFilter() {
    private val pathMatcher = AntPathMatcher()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        val isTrainee = authentication != null && authentication.authorities.any { it.authority == TraineeKeys.TRAINEE_AUTHORITY }

        if (isTrainee && isBlocked(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Not available to trainees")
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun isBlocked(request: HttpServletRequest): Boolean =
        BLOCKED_ENDPOINTS.any { (method, pattern) ->
            (method == null || method.matches(request.method)) && pathMatcher.match(pattern, request.requestURI)
        }

    private companion object {
        private const val CASE_DEFINITION_URL = "/api/management/v1/case-definition"
        private const val PROCESS_DEFINITION_URL = "/api/management/v1/process-definition"
        private const val OBJECT_MANAGEMENT_CONFIGURATION_URL = "/api/v1/object/management/configuration"

        private val BLOCKED_ENDPOINTS: List<Pair<HttpMethod?, String>> =
            buildList {
                // Access Control (ValtimoAuthorizationHttpSecurityConfigurer) — the PBAC editor itself
                add(HttpMethod.GET to "/api/management/v1/roles")
                add(HttpMethod.POST to "/api/management/v1/roles")
                add(HttpMethod.PUT to "/api/management/v1/roles/*")
                add(HttpMethod.DELETE to "/api/management/v1/roles")
                add(HttpMethod.GET to "/api/management/v1/roles/*/permissions")
                add(HttpMethod.PUT to "/api/management/v1/roles/*/permissions")
                add(HttpMethod.POST to "/api/management/v1/permissions/search")
                add(HttpMethod.GET to "/api/management/v1/permissions/schema")
                add(HttpMethod.GET to "/api/management/v1/pbac/registry")

                // Translation management (LocalizationHttpSecurityConfigurer)
                add(HttpMethod.PUT to "/api/management/v1/localization/*")
                add(HttpMethod.PUT to "/api/management/v1/localization")

                // Choice fields (ChoiceFieldHttpSecurityConfigurer)
                add(HttpMethod.POST to "/api/v1/choice-fields")
                add(HttpMethod.PUT to "/api/v1/choice-fields")
                add(HttpMethod.DELETE to "/api/v1/choice-fields/*")
                add(HttpMethod.POST to "/api/v1/choice-field-values")
                add(HttpMethod.PUT to "/api/v1/choice-field-values")
                add(HttpMethod.DELETE to "/api/v1/choice-field-values/*")

                // Object management configuration (ObjectManagementHttpSecurityConfigurer) — the
                // admin config CRUD only; runtime object endpoints stay authenticated()-only, unaffected.
                add(HttpMethod.POST to OBJECT_MANAGEMENT_CONFIGURATION_URL)
                add(HttpMethod.GET to "$OBJECT_MANAGEMENT_CONFIGURATION_URL/*")
                add(HttpMethod.PUT to OBJECT_MANAGEMENT_CONFIGURATION_URL)
                add(HttpMethod.DELETE to "$OBJECT_MANAGEMENT_CONFIGURATION_URL/*")
                add(HttpMethod.GET to "/api/management/v1/object/management/configuration")

                // Forms (FormHttpSecurityConfigurerKotlin) — global CRUD only; case-scoped form
                // endpoints already fall under CASE_DEFINITION_URL, already covered by
                // TraineeOwnershipInterceptor.
                add(HttpMethod.GET to "/api/management/v1/form")
                add(HttpMethod.POST to "/api/management/v1/form")
                add(HttpMethod.PUT to "/api/management/v1/form")
                add(HttpMethod.GET to "/api/management/v1/form/*")
                add(HttpMethod.DELETE to "/api/management/v1/form/*")
                add(HttpMethod.GET to "/api/management/v1/form/exists/*")
                add(HttpMethod.GET to "/api/management/v1/form-option")

                // System processes (ProcessDefinitionManagementHttpSecurityConfigurer) — the
                // case-unlinked surface TrainingHttpSecurityConfigurer's KDoc already named as
                // deliberately excluded.
                add(HttpMethod.GET to PROCESS_DEFINITION_URL)
                add(HttpMethod.POST to PROCESS_DEFINITION_URL)
                add(HttpMethod.PUT to PROCESS_DEFINITION_URL)
                add(HttpMethod.GET to "$PROCESS_DEFINITION_URL/*")
                add(HttpMethod.GET to "$PROCESS_DEFINITION_URL/key/*")
                add(HttpMethod.DELETE to "$PROCESS_DEFINITION_URL/key/*")
                add(HttpMethod.POST to "$PROCESS_DEFINITION_URL/validate")
                add(HttpMethod.DELETE to "$PROCESS_DEFINITION_URL/*/autofill/*")
                add(HttpMethod.GET to "$PROCESS_DEFINITION_URL/*/export")
                add(HttpMethod.POST to "$PROCESS_DEFINITION_URL/import/preview")
                add(HttpMethod.POST to "$PROCESS_DEFINITION_URL/import")

                // Three ADMIN-gated mutations inside the otherwise authenticated()-only
                // ProcessHttpSecurityConfigurer: process migration, force-delete, raw BPMN deploy.
                add(HttpMethod.POST to "/api/v1/process/definition/*/*/migrate")
                add(HttpMethod.POST to "/api/v1/process/*/delete")
                add(HttpMethod.POST to "/api/v1/process/definition/deployment")

                // Decision tables (DecisionHttpSecurityConfigurer) — global list only; case-scoped
                // endpoints already fall under CASE_DEFINITION_URL, already covered.
                add(HttpMethod.GET to "/api/management/v1/decision-definition")

                // Logs (LoggingHttpSecurityConfigurer)
                add(HttpMethod.POST to "/api/management/v1/logging")

                // Case migration (DocumentMigrationHttpSecurityConfigurer) — no case-key scoping at all
                add(HttpMethod.POST to "/api/management/v1/document-definition/migration/conflicts")
                add(HttpMethod.POST to "/api/management/v1/document-definition/migrate")

                // Dashboard management (DashboardHttpSecurityConfigurer) — every sub-path, any method
                add(null to "/api/management/v1/dashboard/**")

                // Deliberately-not-widened case-definition endpoints (see TrainingHttpSecurityConfigurer's
                // KDoc) — safe to leave ADMIN-only only while trainees had no real ROLE_ADMIN.
                add(HttpMethod.POST to "$CASE_DEFINITION_URL/draft")
                add(HttpMethod.POST to "/api/management/v1/case/import")
                add(HttpMethod.POST to "/api/management/v1/case/import/preview")
                add(HttpMethod.GET to "$CASE_DEFINITION_URL/check")
                add(HttpMethod.GET to "/api/management/v1/metroline/available-modes")

                // This plugin's own admin page — normally PBAC-gated (EpistolaAdministration:MANAGE,
                // seeded to ROLE_ADMIN by default); blocked here too rather than relying on a
                // PBAC-changeset revocation.
                add(null to "/api/v1/plugin/epistola/admin/**")
            }
    }
}