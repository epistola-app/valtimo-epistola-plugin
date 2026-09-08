// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Registers [TraineeOwnershipInterceptor] against every path family it needs to check — a
 * `HandlerInterceptor` only runs for the path patterns it's registered against, so adding a check
 * to the interceptor without *also* registering its pattern here leaves it wide open: the request
 * reaches the controller with no ownership check at all. Caught three times by actually driving
 * this over real HTTP as two distinct trainees, not by reading the interceptor's own logic — once
 * for the admin-configuration surface below, once for the document/task data-plane surface, and
 * once more for the plugin's own configurator-browsing endpoints (see the third bullet).
 *
 * Three different reasons a path ends up here:
 * - The **admin-configuration surface** (`plugin/configuration`, `process-link`, and the
 *   `management` case-definition prefixes) is reachable only because
 *   [TrainingHttpSecurityConfigurer] widened its flat `ROLE_ADMIN` HTTP gate — keep this part in
 *   exact sync with [TrainingHttpSecurityConfigurer.WIDENED_ENDPOINTS]'s path prefixes whenever
 *   that list changes.
 * - The **document/task data plane** (`document`, the document-definition search endpoint,
 *   `document-search`, `task`) was never gated by `hasAuthority(ADMIN)` at all — trainees could
 *   always reach it as ordinary authenticated users. It needs registering here for a different
 *   reason: Valtimo's own `all.permission.json` grants `ROLE_ADMIN` unconditioned PBAC access to
 *   it, and trainees now carry real `ROLE_ADMIN` too (see
 *   [com.ritense.valtimo.epistola.training.TraineeKeys.ADMIN_AUTHORITY]'s KDoc) — this interceptor
 *   is what re-narrows that back down to their own dossier.
 * - A handful of endpoints [TraineeAdminSurfaceGuardFilter] originally hard-blocked outright turned
 *   out to be scopable the same way once [ProcessInstanceOwnershipResolver] existed: force-deleting
 *   a process instance, and this plugin's own admin sub-resources scoped by plugin-configuration id
 *   or execution id (catalog listing/redeploy, reconcile, process-link export).
 * - The plugin's own configurator-browsing surface (`EpistolaTemplateResource`, everything under
 *   `epistola/configurations` — the catalog/template/attribute/environment/variant browser used
 *   while authoring a data mapping) is a *third* reason: this one was already `hasAuthority(ROLE_ADMIN)`
 *   before this feature existed (a legitimate admin-only surface, gated by the plugin's own
 *   `EpistolaHttpSecurityConfigurer`, not by [TrainingHttpSecurityConfigurer]'s widening), so
 *   trainees reach it purely because they now carry that real authority too. Confirmed live: the
 *   same `configurationId` that 403s through the admin-configurations path above
 *   returned 200 with another trainee's actual tenant data through this sibling path — found by
 *   chasing a user's question about catalog scoping, not by routine testing, and closed
 *   immediately. Every one of `EpistolaTemplateResource`'s endpoints already takes a bare
 *   `configurationId` path variable, so no interceptor code changes were needed — only this
 *   registration.
 */
class TrainingWebConfig(
    private val traineeOwnershipInterceptor: TraineeOwnershipInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry
            .addInterceptor(traineeOwnershipInterceptor)
            .addPathPatterns(
                "/api/v1/plugin/configuration/**",
                "/api/v1/process-link",
                "/api/v1/process-link/**",
                "/api/management/v1/case-definition/**",
                "/api/management/v1/case/**",
                "/api/management/v2/case/**",
                "/api/v1/document/**",
                "/api/v1/document-definition/*/search",
                "/api/v1/document-search",
                "/api/v1/task/**",
                "/api/v2/task/**",
                "/api/v1/process/*/delete",
                "/api/v1/plugin/epistola/admin/configurations/**",
                "/api/v1/plugin/epistola/admin/export/**",
                "/api/v1/plugin/epistola/admin/pending/**",
                "/api/v1/plugin/epistola/configurations/**",
            )
    }
}