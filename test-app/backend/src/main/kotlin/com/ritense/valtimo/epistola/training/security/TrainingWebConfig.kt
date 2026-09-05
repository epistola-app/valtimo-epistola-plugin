// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Registers [TraineeOwnershipInterceptor] against every path family
 * [TrainingHttpSecurityConfigurer] widens — a `HandlerInterceptor` only runs for the path patterns
 * it's registered against, so widening the HTTP gate for a path family here without *also* adding
 * its pattern below leaves it wide open: any `ROLE_DEMO` principal gets past Spring Security
 * (correctly) but then hits no ownership check at all (not correctly). Caught by actually driving
 * this over real HTTP as two distinct trainees, not by reading the interceptor's own logic — this
 * mismatch compiled fine and looked complete.
 *
 * Keep this list in exact sync with [TrainingHttpSecurityConfigurer.WIDENED_ENDPOINTS]'s path
 * prefixes whenever that list changes.
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
            )
    }
}