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
 * reaches the controller with no ownership check at all. Caught twice by actually driving this
 * over real HTTP as two distinct trainees, not by reading the interceptor's own logic — once for
 * the admin-configuration surface below, once again for the document/task data-plane surface.
 *
 * Two different reasons a path ends up here:
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
            )
    }
}