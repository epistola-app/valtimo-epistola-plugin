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
 *   deliberately not widened; plus two ADMIN-gated mutations buried in the otherwise-`authenticated()`
 *   `ProcessHttpSecurityConfigurer`: process migration and raw BPMN deployment. Force-deleting a
 *   process instance, the third, is *not* blocked here — see [TraineeOwnershipInterceptor]'s KDoc
 *   for why that one turned out to be scopable instead.
 * - Decision tables (`DecisionHttpSecurityConfigurer`) — only the *global* list; the case-scoped
 *   decision-definition endpoints are, like Forms, already covered by the existing interceptor.
 * - Logs (`LoggingHttpSecurityConfigurer`).
 * - Case migration (`DocumentMigrationHttpSecurityConfigurer`) — migrates data across
 *   document-definitions with no case-key scoping at all.
 * - Dashboard management (`DashboardHttpSecurityConfigurer`).
 * - Two of the four case-definition endpoints [TrainingHttpSecurityConfigurer]'s KDoc documents as
 *   deliberately not widened: `POST .../case-definition/draft` (creates a brand-new, unrelated
 *   case-definition) and `POST .../case/import` / `.../case/import/preview` (arbitrary import,
 *   nothing in the URL to check ownership against) — those were only safe to leave alone while
 *   trainees had no real `ROLE_ADMIN`; now they need active blocking like everything else here.
 *   The other two named in that KDoc, `GET .../case-definition/check` and
 *   `GET .../metroline/available-modes`, turned out — on actually loading `/case-management` as a
 *   trainee and getting 403s from the first of them, not by re-reading the reasoning — to be
 *   genuinely safe to leave open: both take zero parameters and their response depends only on
 *   deployment-wide flags (`CaseDefinitionCheckerImpl.canUpdateGlobalConfiguration`, whether a
 *   `ZaakMetrolineDataService` bean exists), never on the caller's identity or any specific case,
 *   confirmed directly from Valtimo 13.44.0 source. Not in [BLOCKED_ENDPOINTS] below.
 * - This plugin's own admin page, `/api/v1/plugin/epistola/admin` — normally gated by the
 *   `EpistolaAdministration:MANAGE` PBAC permission (seeded to `ROLE_ADMIN` by default). Almost all
 *   of it is left reachable (real `ROLE_ADMIN` already satisfies that PBAC grant): health checks,
 *   the usage overview, and pending jobs are response-filtered by
 *   [TraineeOwnershipResponseBodyAdvice] down to the caller's own tenant/plugin configuration
 *   (health/usage also keep the shared template's own entries visible, read-only reference);
 *   catalog listing/redeploy, process-link export, and pending-job reconcile are scoped by
 *   [TraineeOwnershipInterceptor] instead, once it became clear each one identifies its target by
 *   a resolvable plugin-configuration/process-link/execution id, not an unowned arbitrary one. Only
 *   `/validations` and `/forms/legacy-override` stay hard-blocked here: both scan engine-wide with
 *   no per-resource identifier at all to scope by (every cloned dossier shares the same literal
 *   process-definition key, only the version tag differs, so `/validations`' violations can't be
 *   attributed to one trainee over another even in principle).
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
 *
 * The underlying rule, not a list to keep re-deriving by hand: a trainee should never be able to
 * change something that belongs to another user/tenant, or to the shared instance as a whole —
 * everything above is one or the other (arbitrary/unowned target, or genuinely global
 * configuration), which is also why each entry below carries its own specific 403 [BlockedEndpoint.reason]
 * rather than one generic "forbidden" message: a trainee hitting this should be able to tell
 * *why*, not just that they were denied.
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

        if (isTrainee) {
            val blocked = matchBlocked(request)
            if (blocked != null) {
                response.rejectAsForbidden(blocked.reason)
                return
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun matchBlocked(request: HttpServletRequest): BlockedEndpoint? =
        BLOCKED_ENDPOINTS.firstOrNull { endpoint ->
            (endpoint.method == null || endpoint.method.matches(request.method)) &&
                pathMatcher.match(endpoint.pattern, request.requestURI)
        }

    /** @param reason sent back verbatim as the 403 body — must be specific enough to act on, not just "forbidden". */
    private data class BlockedEndpoint(
        val method: HttpMethod?,
        val pattern: String,
        val reason: String,
    )

    private companion object {
        private const val CASE_DEFINITION_URL = "/api/management/v1/case-definition"
        private const val PROCESS_DEFINITION_URL = "/api/management/v1/process-definition"
        private const val OBJECT_MANAGEMENT_CONFIGURATION_URL = "/api/v1/object/management/configuration"
        private const val EPISTOLA_ADMIN_URL = "/api/v1/plugin/epistola/admin"

        private const val SHARED_CONFIG_REASON =
            "This changes shared, instance-wide configuration used by every user, not something specific to your own dossier."
        private const val ARBITRARY_TARGET_REASON =
            "This operates on an arbitrary id with no way to confirm it belongs to your own dossier, so it could affect another user's data."
        private const val ENGINE_WIDE_REASON =
            "This scans/lists across the whole engine — every trainee's cloned dossier shares the same underlying process key, so there is no way to show only your own."

        private val BLOCKED_ENDPOINTS: List<BlockedEndpoint> =
            buildList {
                fun block(
                    method: HttpMethod?,
                    pattern: String,
                    reason: String,
                ) = add(BlockedEndpoint(method, pattern, reason))

                // Access Control (ValtimoAuthorizationHttpSecurityConfigurer) — the PBAC editor
                // itself; left open, a trainee could grant themselves any permission, including
                // this plugin's own EpistolaAdministration:MANAGE.
                val accessControlReason =
                    "This manages roles and permissions for the whole instance, including what other users are allowed to do."
                block(HttpMethod.GET, "/api/management/v1/roles", accessControlReason)
                block(HttpMethod.POST, "/api/management/v1/roles", accessControlReason)
                block(HttpMethod.PUT, "/api/management/v1/roles/*", accessControlReason)
                block(HttpMethod.DELETE, "/api/management/v1/roles", accessControlReason)
                block(HttpMethod.GET, "/api/management/v1/roles/*/permissions", accessControlReason)
                block(HttpMethod.PUT, "/api/management/v1/roles/*/permissions", accessControlReason)
                block(HttpMethod.POST, "/api/management/v1/permissions/search", accessControlReason)
                block(HttpMethod.GET, "/api/management/v1/permissions/schema", accessControlReason)
                block(HttpMethod.GET, "/api/management/v1/pbac/registry", accessControlReason)

                // Translation management (LocalizationHttpSecurityConfigurer) — global i18n keys.
                block(HttpMethod.PUT, "/api/management/v1/localization/*", SHARED_CONFIG_REASON)
                block(HttpMethod.PUT, "/api/management/v1/localization", SHARED_CONFIG_REASON)

                // Choice fields (ChoiceFieldHttpSecurityConfigurer) — global reference data.
                block(HttpMethod.POST, "/api/v1/choice-fields", SHARED_CONFIG_REASON)
                block(HttpMethod.PUT, "/api/v1/choice-fields", SHARED_CONFIG_REASON)
                block(HttpMethod.DELETE, "/api/v1/choice-fields/*", SHARED_CONFIG_REASON)
                block(HttpMethod.POST, "/api/v1/choice-field-values", SHARED_CONFIG_REASON)
                block(HttpMethod.PUT, "/api/v1/choice-field-values", SHARED_CONFIG_REASON)
                block(HttpMethod.DELETE, "/api/v1/choice-field-values/*", SHARED_CONFIG_REASON)

                // Object management configuration (ObjectManagementHttpSecurityConfigurer) — the
                // admin config CRUD only; runtime object endpoints stay authenticated()-only,
                // unaffected.
                block(HttpMethod.POST, OBJECT_MANAGEMENT_CONFIGURATION_URL, SHARED_CONFIG_REASON)
                block(HttpMethod.GET, "$OBJECT_MANAGEMENT_CONFIGURATION_URL/*", SHARED_CONFIG_REASON)
                block(HttpMethod.PUT, OBJECT_MANAGEMENT_CONFIGURATION_URL, SHARED_CONFIG_REASON)
                block(HttpMethod.DELETE, "$OBJECT_MANAGEMENT_CONFIGURATION_URL/*", SHARED_CONFIG_REASON)
                block(HttpMethod.GET, "/api/management/v1/object/management/configuration", SHARED_CONFIG_REASON)

                // Forms (FormHttpSecurityConfigurerKotlin) — global CRUD only; case-scoped form
                // endpoints already fall under the case-definition management prefix, already
                // covered by TraineeOwnershipInterceptor.
                val formsReason = "This lists/manages forms across every case type in the instance, not just your own dossier."
                block(HttpMethod.GET, "/api/management/v1/form", formsReason)
                block(HttpMethod.POST, "/api/management/v1/form", formsReason)
                block(HttpMethod.PUT, "/api/management/v1/form", formsReason)
                block(HttpMethod.GET, "/api/management/v1/form/*", formsReason)
                block(HttpMethod.DELETE, "/api/management/v1/form/*", formsReason)
                block(HttpMethod.GET, "/api/management/v1/form/exists/*", formsReason)
                block(HttpMethod.GET, "/api/management/v1/form-option", formsReason)

                // System processes (ProcessDefinitionManagementHttpSecurityConfigurer) — the
                // case-unlinked surface TrainingHttpSecurityConfigurer's KDoc already named as
                // deliberately excluded: not tied to any case/dossier at all.
                val systemProcessReason = "This manages process definitions that aren't linked to any case, outside your dossier entirely."
                block(HttpMethod.GET, PROCESS_DEFINITION_URL, systemProcessReason)
                block(HttpMethod.POST, PROCESS_DEFINITION_URL, systemProcessReason)
                block(HttpMethod.PUT, PROCESS_DEFINITION_URL, systemProcessReason)
                block(HttpMethod.GET, "$PROCESS_DEFINITION_URL/*", systemProcessReason)
                block(HttpMethod.GET, "$PROCESS_DEFINITION_URL/key/*", systemProcessReason)
                block(HttpMethod.DELETE, "$PROCESS_DEFINITION_URL/key/*", systemProcessReason)
                block(HttpMethod.POST, "$PROCESS_DEFINITION_URL/validate", systemProcessReason)
                block(HttpMethod.DELETE, "$PROCESS_DEFINITION_URL/*/autofill/*", systemProcessReason)
                block(HttpMethod.GET, "$PROCESS_DEFINITION_URL/*/export", systemProcessReason)
                block(HttpMethod.POST, "$PROCESS_DEFINITION_URL/import/preview", systemProcessReason)
                block(HttpMethod.POST, "$PROCESS_DEFINITION_URL/import", systemProcessReason)

                // Two of three ADMIN-gated mutations inside the otherwise authenticated()-only
                // ProcessHttpSecurityConfigurer stay blocked. Migration takes two full process-
                // definition ids (resolvable via ProcessDefinitionOwnershipResolver, unlike a bare
                // key) but a trainee's dossier is finalized at provisioning and never gets a second
                // deployed version to migrate between, even their own - no legitimate use case, not
                // just an unscopable one. Raw BPMN deployment creates an entirely new, arbitrary
                // process definition with no existing target to check ownership against at all.
                // Force-deleting a process instance (POST /api/v1/process/{processInstanceId}/delete)
                // is NOT blocked here - see TraineeOwnershipInterceptor's `processInstanceId` check.
                block(HttpMethod.POST, "/api/v1/process/definition/*/*/migrate", ARBITRARY_TARGET_REASON)
                block(HttpMethod.POST, "/api/v1/process/definition/deployment", ARBITRARY_TARGET_REASON)

                // Decision tables (DecisionHttpSecurityConfigurer) — global list only; case-scoped
                // endpoints already fall under the case-definition management prefix, already covered.
                block(
                    HttpMethod.GET,
                    "/api/management/v1/decision-definition",
                    "This lists decision tables across every case type in the instance, not just your own dossier.",
                )

                // Logs (LoggingHttpSecurityConfigurer)
                block(HttpMethod.POST, "/api/management/v1/logging", SHARED_CONFIG_REASON)

                // Case migration (DocumentMigrationHttpSecurityConfigurer) — no case-key scoping at all.
                block(HttpMethod.POST, "/api/management/v1/document-definition/migration/conflicts", ARBITRARY_TARGET_REASON)
                block(HttpMethod.POST, "/api/management/v1/document-definition/migrate", ARBITRARY_TARGET_REASON)

                // Dashboard management (DashboardHttpSecurityConfigurer) — every sub-path, any method.
                block(null, "/api/management/v1/dashboard/**", SHARED_CONFIG_REASON)

                // Deliberately-not-widened case-definition endpoints (see TrainingHttpSecurityConfigurer's
                // KDoc) — safe to leave ADMIN-only only while trainees had no real ROLE_ADMIN.
                // (case-definition/check and metroline/available-modes, also named in that KDoc,
                // are deliberately NOT here — see this class's KDoc for why.)
                val newCaseDefinitionReason = "This creates or imports a case definition unrelated to your own dossier."
                block(HttpMethod.POST, "$CASE_DEFINITION_URL/draft", newCaseDefinitionReason)
                block(HttpMethod.POST, "/api/management/v1/case/import", newCaseDefinitionReason)
                block(HttpMethod.POST, "/api/management/v1/case/import/preview", newCaseDefinitionReason)

                // This plugin's own admin page — only the sub-resources with no safe per-trainee
                // scoping stay blocked here. /health, /versions, /changelog, /usage, and /pending
                // are response-filtered by TraineeOwnershipResponseBodyAdvice; catalog
                // listing/redeploy, export, and reconcile turned out to be scopable via
                // TraineeOwnershipInterceptor once ProcessInstanceOwnershipResolver existed - see
                // that interceptor's KDoc - so they're not blocked here either. /validations scans
                // every deployed process definition engine-wide and can't distinguish trainees from
                // it (every cloned dossier shares the same literal process-definition key, only the
                // version tag differs); /forms/legacy-override has no per-tenant scoping either.
                block(HttpMethod.GET, "$EPISTOLA_ADMIN_URL/validations", ENGINE_WIDE_REASON)
                block(HttpMethod.GET, "$EPISTOLA_ADMIN_URL/forms/legacy-override", ENGINE_WIDE_REASON)
            }
    }
}