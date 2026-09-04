// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.valtimo.contract.security.config.HttpConfigurerConfigurationException
import com.ritense.valtimo.contract.security.config.HttpSecurityConfigurer
import com.ritense.valtimo.epistola.training.TraineeKeys
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.DELETE
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PATCH
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher

/**
 * Widens the flat `hasAuthority(ADMIN)` HTTP gate Valtimo puts on process-link,
 * plugin-configuration, and case-definition management endpoints — confirmed directly from
 * Valtimo 13.44.0 source (`ProcessLinkHttpSecurityConfigurer`, `PluginHttpSecurityConfigurer`,
 * `CaseHttpSecurityConfigurer`, `InternalCaseHttpSecurityConfigurer`): none of these have a PBAC
 * hook, only this flat HTTP check — to also accept [TraineeKeys.TRAINEE_AUTHORITY], so a trainee
 * never needs real `ROLE_ADMIN`. [TraineeOwnershipInterceptor], [TraineeOwnershipRequestBodyAdvice]
 * and [TraineeOwnershipResponseBodyAdvice] then narrow a trainee down to only their own dossier
 * (plus read-only access to the shared template).
 *
 * The case-definition management surface is every endpoint that is path-scoped directly by a
 * case-/document-definition key — case tabs, settings, list/task-list columns, widget/header
 * tabs, startable items, case export, internal status, configuration issues, dangling
 * plugin-configuration mappings, active-version toggling, and case-definition CRUD itself. Every
 * one of those endpoints is path-scoped, so [TraineeOwnershipInterceptor] can check ownership from
 * the URL alone — no body inspection needed there, unlike process-link.
 *
 * **Deliberately NOT widened**, and left `ROLE_ADMIN`-only:
 *  - `POST .../case-definition/draft` — creates a brand-new, unrelated case-definition. Trainees
 *    get their dossier exclusively through the clone-on-login flow; there's no legitimate reason
 *    for one to hit this directly.
 *  - `POST .../case/import` / `.../case/import/preview` — arbitrary case-definition import from an
 *    uploaded file, with no case key in the URL to check ownership against before the import
 *    decides what key the result gets.
 *  - `GET .../case-definition/check` and `GET .../metroline/available-modes` — global,
 *    not case-specific; nothing to scope.
 *  - `ProcessDefinitionManagementHttpSecurityConfigurer`'s case-*unlinked* "system" process
 *    surface — a different configurer, out of scope for dossier administration.
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
                var registry = requests
                for ((method, path) in WIDENED_ENDPOINTS) {
                    registry = registry.requestMatchers(antMatcher(method, path)).hasAnyAuthority(*TRAINEE_OR_ADMIN)
                }
            }
            http.addFilterBefore(traineeProvisioningFilter, AuthorizationFilter::class.java)
        } catch (e: Exception) {
            throw HttpConfigurerConfigurationException(e)
        }
    }

    companion object {
        private const val PLUGIN_CONFIGURATION_URL = "/api/v1/plugin/configuration"
        private const val PROCESS_LINK_URL = "/api/v1/process-link"

        private const val MANAGEMENT_CASE_DEFINITION_URL = "/api/management/v1/case-definition"
        private const val MANAGEMENT_CASE_LIST_COLUMN_URL = "/api/management/v1/case/{caseDefinitionName}/list-column"
        private const val MANAGEMENT_TASK_LIST_COLUMN_URL = "/api/management/v1/case/{caseDefinitionName}/task-list-column"
        private const val MANAGEMENT_TASK_LIST_COLUMN_V2_URL = "/api/management/v2/case/{caseDefinitionName}/task-list-column"
        private const val MANAGEMENT_TAB_URL =
            "/api/management/v1/case-definition/{caseDefinitionKey}/version/{caseDefinitionVersionTag}/tab"
        private const val MANAGEMENT_WIDGET_TAB_URL =
            "/api/management/v1/case-definition/{caseDefinitionKey}/version/{caseDefinitionVersionTag}/widget-tab"
        private const val MANAGEMENT_HEADER_WIDGET_URL =
            "/api/management/v1/case-definition/{caseDefinitionKey}/version/{caseDefinitionVersionTag}/header-widget"
        private const val MANAGEMENT_STARTABLE_ITEMS_URL =
            "/api/management/v1/case-definition/{caseDefinitionKey}/version/{caseDefinitionVersionTag}/startable-item"
        private const val MANAGEMENT_INTERNAL_STATUS_URL = "/api/management/v1/case-definition/{caseDefinitionName}/internal-status"

        private val TRAINEE_OR_ADMIN = arrayOf(TraineeKeys.ADMIN_AUTHORITY, TraineeKeys.TRAINEE_AUTHORITY)

        private val WIDENED_ENDPOINTS: List<Pair<HttpMethod, String>> =
            buildList {
                // Plugin configuration (PluginHttpSecurityConfigurer)
                add(GET to PLUGIN_CONFIGURATION_URL)
                add(POST to PLUGIN_CONFIGURATION_URL)
                add(PUT to "$PLUGIN_CONFIGURATION_URL/{pluginConfigurationId}")
                add(DELETE to "$PLUGIN_CONFIGURATION_URL/{pluginConfigurationId}")

                // Process link (ProcessLinkHttpSecurityConfigurer)
                add(GET to PROCESS_LINK_URL)
                add(GET to "$PROCESS_LINK_URL/types")
                add(POST to PROCESS_LINK_URL)
                add(PUT to PROCESS_LINK_URL)
                add(DELETE to "$PROCESS_LINK_URL/{processLinkId}")

                // Case-definition CRUD / settings / active-version (CaseHttpSecurityConfigurer)
                add(GET to MANAGEMENT_CASE_DEFINITION_URL) // bare list — response-filtered, see TraineeOwnershipResponseBodyAdvice
                add(GET to "$MANAGEMENT_CASE_DEFINITION_URL/{caseDefinitionName}/version")
                add(GET to "$MANAGEMENT_CASE_DEFINITION_URL/{caseDefinitionKey}/version/{caseDefinitionVersionTag}/configuration-issues")
                add(
                    GET to
                        "$MANAGEMENT_CASE_DEFINITION_URL/{caseDefinitionKey}/version/{caseDefinitionVersionTag}/dangling-plugin-configurations",
                )
                add(
                    PUT to
                        "$MANAGEMENT_CASE_DEFINITION_URL/{caseDefinitionKey}/version/{caseDefinitionVersionTag}/plugin-configuration-mappings",
                )
                add(GET to "$MANAGEMENT_CASE_DEFINITION_URL/{caseDefinitionKey}/version/{caseDefinitionVersionTag}/settings")
                add(PATCH to "$MANAGEMENT_CASE_DEFINITION_URL/{caseDefinitionKey}/version/{caseDefinitionVersionTag}/settings")
                add(GET to "$MANAGEMENT_CASE_DEFINITION_URL/{caseDefinitionKey}")
                add(POST to "$MANAGEMENT_CASE_DEFINITION_URL/{caseDefinitionKey}/version/{caseDefinitionVersionTag}/active")
                add(GET to "$MANAGEMENT_CASE_DEFINITION_URL/{key}/version/{version}")
                add(DELETE to "$MANAGEMENT_CASE_DEFINITION_URL/{key}/version/{version}")
                add(PATCH to "$MANAGEMENT_CASE_DEFINITION_URL/{key}/version/{version}")
                add(POST to "$MANAGEMENT_CASE_DEFINITION_URL/{key}/version/{version}/finalize")
                add(GET to "$MANAGEMENT_CASE_DEFINITION_URL/{key}/version/{version}/finalizable")

                // List columns / task list columns
                add(GET to MANAGEMENT_CASE_LIST_COLUMN_URL)
                add(POST to MANAGEMENT_CASE_LIST_COLUMN_URL)
                add(PUT to MANAGEMENT_CASE_LIST_COLUMN_URL)
                add(DELETE to "$MANAGEMENT_CASE_LIST_COLUMN_URL/{columnKey}")
                add(GET to MANAGEMENT_TASK_LIST_COLUMN_URL)
                add(POST to MANAGEMENT_TASK_LIST_COLUMN_URL)
                add(POST to MANAGEMENT_TASK_LIST_COLUMN_V2_URL)
                add(PUT to "$MANAGEMENT_TASK_LIST_COLUMN_URL/{columnKey}")
                add(DELETE to "$MANAGEMENT_TASK_LIST_COLUMN_URL/{columnKey}")

                // Case tabs
                add(POST to MANAGEMENT_TAB_URL)
                add(PUT to MANAGEMENT_TAB_URL)
                add(PUT to "$MANAGEMENT_TAB_URL/{tabKey}")
                add(DELETE to "$MANAGEMENT_TAB_URL/{tabKey}")
                add(GET to "$MANAGEMENT_TAB_URL/{tabKey}")
                add(GET to MANAGEMENT_TAB_URL)

                // Case export (read-only, own dossier only — import stays excluded, see class KDoc)
                add(GET to "/api/management/v1/case/{caseDefinitionName}/version/{caseDefinitionVersion}/export")

                // Widget tabs / header widgets
                add(GET to "$MANAGEMENT_WIDGET_TAB_URL/{tabKey}")
                add(POST to "$MANAGEMENT_WIDGET_TAB_URL/{tabKey}")
                add(POST to MANAGEMENT_HEADER_WIDGET_URL)
                add(GET to MANAGEMENT_HEADER_WIDGET_URL)
                add(PUT to MANAGEMENT_HEADER_WIDGET_URL)
                add(DELETE to MANAGEMENT_HEADER_WIDGET_URL)

                // Startable items
                add(GET to MANAGEMENT_STARTABLE_ITEMS_URL)
                add(POST to MANAGEMENT_STARTABLE_ITEMS_URL)
                add(DELETE to "$MANAGEMENT_STARTABLE_ITEMS_URL/{itemKey}/version/{versionTag}")
                add(DELETE to "$MANAGEMENT_STARTABLE_ITEMS_URL/{itemKey}")
                add(PUT to "$MANAGEMENT_STARTABLE_ITEMS_URL/order")
                add(GET to "$MANAGEMENT_STARTABLE_ITEMS_URL/{itemKey}/version/{versionTag}/properties")
                add(GET to "$MANAGEMENT_STARTABLE_ITEMS_URL/{itemKey}/properties")
                add(PUT to "$MANAGEMENT_STARTABLE_ITEMS_URL/{itemKey}/version/{versionTag}")
                add(PUT to "$MANAGEMENT_STARTABLE_ITEMS_URL/{itemKey}")

                // Internal status (InternalCaseHttpSecurityConfigurer)
                add(GET to MANAGEMENT_INTERNAL_STATUS_URL)
                add(POST to MANAGEMENT_INTERNAL_STATUS_URL)
                add(PUT to MANAGEMENT_INTERNAL_STATUS_URL)
                add(PUT to "$MANAGEMENT_INTERNAL_STATUS_URL/{internalStatusKey}")
                add(DELETE to "$MANAGEMENT_INTERNAL_STATUS_URL/{internalStatusKey}")
            }
    }
}