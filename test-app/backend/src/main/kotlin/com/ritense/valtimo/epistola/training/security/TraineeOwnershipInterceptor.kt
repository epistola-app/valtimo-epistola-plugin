// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID

/**
 * Enforces per-trainee ownership on the process-link and plugin-configuration endpoints that
 * [TrainingHttpSecurityConfigurer] widened past their flat `ROLE_ADMIN` gate — for the requests
 * identifiable purely from the URL (path variables / query params). POST/PUT bodies are covered
 * by [TraineeOwnershipRequestBodyAdvice] (a `HandlerInterceptor` runs before Spring MVC has bound
 * the request body), list responses by [TraineeOwnershipResponseBodyAdvice].
 *
 * Deliberately scoped to plugin-configuration and process-link only — see
 * [TrainingHttpSecurityConfigurer]'s KDoc for what's NOT covered yet.
 */
class TraineeOwnershipInterceptor(
    private val ownershipChecks: TraineeOwnershipChecks,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val traineeIdentity = ownershipChecks.currentTraineeIdentityOrNull() ?: return true

        pathVariable(request, "pluginConfigurationId")?.let { pluginConfigurationId ->
            return allowOrForbid(response, ownershipChecks.isOwnPluginConfiguration(traineeIdentity, pluginConfigurationId))
        }

        pathVariable(request, "processLinkId")?.let { processLinkId ->
            val processDefinitionId =
                runCatching { UUID.fromString(processLinkId) }
                    .getOrNull()
                    ?.let { ownershipChecks.resolveProcessDefinitionIdOfProcessLink(it) }
            val owned = processDefinitionId != null && ownershipChecks.isOwnProcessDefinition(traineeIdentity, processDefinitionId)
            return allowOrForbid(response, owned)
        }

        request.getParameter("processDefinitionId")?.let { processDefinitionId ->
            val readOnly = request.method.equals("GET", ignoreCase = true)
            return allowOrForbid(
                response,
                ownershipChecks.isOwnProcessDefinition(traineeIdentity, processDefinitionId, allowShared = readOnly),
            )
        }

        return true
    }

    private fun allowOrForbid(
        response: HttpServletResponse,
        allowed: Boolean,
    ): Boolean {
        if (!allowed) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Not your dossier")
        }
        return allowed
    }

    private fun pathVariable(
        request: HttpServletRequest,
        name: String,
    ): String? {
        @Suppress("UNCHECKED_CAST")
        val variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>
        return variables?.get(name)
    }
}