// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

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
            )
    }
}