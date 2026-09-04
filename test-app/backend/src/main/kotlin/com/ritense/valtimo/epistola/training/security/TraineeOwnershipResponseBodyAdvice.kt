// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.plugin.web.rest.result.PluginConfigurationDto
import com.ritense.processlink.web.rest.dto.ProcessLinkResponseDto
import org.springframework.context.annotation.Profile
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/**
 * Filters plugin-configuration and process-link list responses down to "owned by me" plus the
 * shared template (read-only reference, visible to every trainee).
 *
 * Runs on every controller response (`supports` is unconditionally `true`) but only acts on lists
 * of the two known DTOs — cheap for a trainee (one authorities check, then an early return for
 * anything else), a no-op for genuine `ROLE_ADMIN` staff.
 *
 * Known gap: `CaseDefinitionService.getCaseDefinitionsForManagement` returns a Spring Data `Page`,
 * not a bare `List` — case-definition list filtering isn't handled here (case-definition
 * management is out of scope for this pass, see [TrainingHttpSecurityConfigurer]'s KDoc), but if
 * that's ever widened, filtering a `Page` needs special-casing: rebuilding one changes its total
 * count to disagree with the filtered content length.
 *
 * `@Profile("training")` directly on this class — see [TraineeOwnershipRequestBodyAdvice]'s KDoc
 * for why: `@ControllerAdvice` is itself meta-annotated `@Component`, so without this the bean is
 * created by component-scan regardless of any profile-gated `@Bean` wiring elsewhere.
 */
@ControllerAdvice
@Profile("training")
class TraineeOwnershipResponseBodyAdvice(
    private val ownershipChecks: TraineeOwnershipChecks,
) : ResponseBodyAdvice<Any> {
    override fun supports(
        returnType: MethodParameter,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = true

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? {
        val traineeIdentity = ownershipChecks.currentTraineeIdentityOrNull() ?: return body
        return if (body is List<*>) body.filter { isOwnedOrShared(traineeIdentity, it) } else body
    }

    private fun isOwnedOrShared(
        traineeIdentity: String,
        item: Any?,
    ): Boolean =
        when (item) {
            is PluginConfigurationDto -> ownershipChecks.isOwnPluginConfiguration(traineeIdentity, item.id)
            is ProcessLinkResponseDto ->
                ownershipChecks.isOwnProcessDefinition(
                    traineeIdentity,
                    item.processDefinitionId,
                    allowShared = true,
                )
            else -> true
        }
}