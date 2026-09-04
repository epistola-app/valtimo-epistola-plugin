// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.case.web.rest.dto.CaseDefinitionResponseDto
import com.ritense.plugin.web.rest.result.PluginConfigurationDto
import com.ritense.processlink.web.rest.dto.ProcessLinkResponseDto
import org.springframework.context.annotation.Profile
import org.springframework.core.MethodParameter
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/**
 * Filters plugin-configuration, process-link, and case-definition list responses down to "owned
 * by me" plus the shared template (read-only reference, visible to every trainee).
 *
 * Runs on every controller response (`supports` is unconditionally `true`) but only acts on lists
 * or pages of the known DTOs — cheap for a trainee (one authorities check, then an early return
 * for anything else), a no-op for genuine `ROLE_ADMIN` staff.
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
        return when (body) {
            is List<*> -> body.filter { isOwnedOrShared(traineeIdentity, it) }
            // GET /api/management/v1/case-definition is the one bare, cross-dossier list in the
            // widened surface (every other list endpoint is already path-scoped to one dossier by
            // TraineeOwnershipInterceptor, so filtering its content again here would be redundant).
            // Reported as a single self-contained page rather than preserving the original total:
            // a trainee only ever has at most their own dossier plus the shared template, so
            // real pagination across pages never applies to what they're allowed to see.
            is Page<*> -> {
                val filteredContent = body.content.filter { isOwnedOrShared(traineeIdentity, it) }
                PageImpl(filteredContent, body.pageable, filteredContent.size.toLong())
            }
            else -> body
        }
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
            is CaseDefinitionResponseDto ->
                ownershipChecks.isOwnCaseDefinition(
                    traineeIdentity,
                    item.caseDefinitionKey,
                    allowShared = true,
                )
            else -> true
        }
}