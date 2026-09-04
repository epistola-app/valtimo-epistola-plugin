// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.plugin.web.rest.request.CreatePluginConfigurationDto
import com.ritense.processlink.web.rest.dto.ProcessLinkCreateRequestDto
import com.ritense.processlink.web.rest.dto.ProcessLinkUpdateRequestDto
import org.springframework.context.annotation.Profile
import org.springframework.core.MethodParameter
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice
import java.lang.reflect.Type

/**
 * Body-carried counterpart to [TraineeOwnershipInterceptor]: `POST /api/v1/process-link` only
 * carries its `processDefinitionId` in the body (no path variable), and
 * `PUT /api/v1/process-link` only carries the process-link id, not the process-definition it
 * belongs to — both need resolving here, before the controller runs.
 *
 * Plugin-configuration creation is blocked outright for trainees: their one `PluginConfiguration`
 * is provisioned automatically alongside their dossier, so there is no legitimate reason for a
 * trainee to create another one via this endpoint.
 *
 * `@Profile("training")` **directly on this class**, not just on `TrainingConfiguration`'s `@Bean`
 * wiring: `@ControllerAdvice` is itself meta-annotated `@Component`, so Spring's component scan
 * picks this class up regardless of any profile-gated `@Bean` method elsewhere — without this
 * annotation, the bean is created even when the `training` profile is off, and then fails to wire
 * ([TraineeOwnershipChecks] only exists as a `@Bean` inside the profile-gated
 * `TrainingConfiguration`), breaking the app for every non-training deployment. Caught by actually
 * running the *existing* test suite, not by reading either class's source — this is exactly the
 * "must be fully optional" failure mode this whole feature is required to avoid.
 */
@ControllerAdvice
@Profile("training")
class TraineeOwnershipRequestBodyAdvice(
    private val ownershipChecks: TraineeOwnershipChecks,
) : RequestBodyAdvice {
    override fun supports(
        methodParameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean =
        CreatePluginConfigurationDto::class.java.isAssignableFrom(methodParameter.parameterType) ||
            ProcessLinkCreateRequestDto::class.java.isAssignableFrom(methodParameter.parameterType) ||
            ProcessLinkUpdateRequestDto::class.java.isAssignableFrom(methodParameter.parameterType)

    override fun beforeBodyRead(
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): HttpInputMessage = inputMessage

    override fun afterBodyRead(
        body: Any,
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Any {
        val traineeIdentity = ownershipChecks.currentTraineeIdentityOrNull() ?: return body

        when (body) {
            is CreatePluginConfigurationDto ->
                throw AccessDeniedException("Trainees cannot create plugin configurations directly")
            is ProcessLinkCreateRequestDto ->
                requireOwnProcessDefinition(traineeIdentity, body.processDefinitionId)
            is ProcessLinkUpdateRequestDto ->
                requireOwnProcessDefinition(traineeIdentity, ownershipChecks.resolveProcessDefinitionIdOfProcessLink(body.id))
        }

        return body
    }

    override fun handleEmptyBody(
        body: Any?,
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Any? = body

    private fun requireOwnProcessDefinition(
        traineeIdentity: String,
        processDefinitionId: String?,
    ) {
        if (processDefinitionId == null || !ownershipChecks.isOwnProcessDefinition(traineeIdentity, processDefinitionId)) {
            throw AccessDeniedException("Not your dossier")
        }
    }
}