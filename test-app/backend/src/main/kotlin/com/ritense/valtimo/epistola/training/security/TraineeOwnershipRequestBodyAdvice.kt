// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.case.web.rest.dto.CaseDefinitionDraftCreateRequest
import com.ritense.document.domain.impl.request.ModifyDocumentRequest
import com.ritense.document.domain.impl.request.NewDocumentRequest
import com.ritense.document.service.impl.SearchRequest
import com.ritense.plugin.domain.PluginConfigurationReferenceType
import com.ritense.plugin.web.rest.request.CreatePluginConfigurationDto
import com.ritense.plugin.web.rest.request.PluginProcessLinkCreateDto
import com.ritense.plugin.web.rest.request.PluginProcessLinkUpdateDto
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
import java.util.UUID

/**
 * Body-carried counterpart to [TraineeOwnershipInterceptor]: `POST /api/v1/process-link` only
 * carries its `processDefinitionId` in the body (no path variable), and
 * `PUT /api/v1/process-link` only carries the process-link id, not the process-definition it
 * belongs to — both need resolving here, before the controller runs. Same story for the
 * document-data-plane endpoints added alongside [TraineeOwnershipInterceptor]'s document/task
 * checks: `NewDocumentRequest`/`ModifyDocumentRequest`/`SearchRequest` (create/modify/search) only
 * carry their identifying field in the body, never a path variable.
 *
 * `PluginProcessLinkCreateDto`/`PluginProcessLinkUpdateDto` also carry a `pluginConfigurationId` —
 * checked here too, separately from `processDefinitionId`: owning the process a link is wired to
 * says nothing about owning the plugin configuration it references. Without this, a trainee could
 * wire their own dossier's action to another trainee's Epistola plugin configuration (a different
 * tenant) by naming its id — found the same way as the configurator-endpoint gap: by actually
 * checking every body field this DTO carries, not just the one the base interface exposes. Only
 * checked for `PluginConfigurationReferenceType.FIXED` — `BUILDING_BLOCK` resolves the
 * configuration dynamically at runtime from the enclosing building block, never from a fixed id on
 * the wire, so there is nothing to check against here for that mode. `allowShared`: `form-flow-demo`'s
 * own stock process-links already point at the shared template configuration directly —
 * *referencing* it from a trainee's own process-link is the same legitimate pattern, not a new
 * risk (unlike modifying the shared configuration itself, which stays disallowed everywhere else).
 *
 * Plugin-configuration creation is blocked outright for trainees: their one `PluginConfiguration`
 * is provisioned automatically alongside their dossier, so there is no legitimate reason for a
 * trainee to create another one via this endpoint.
 *
 * `POST /api/management/v1/case-definition/draft` (Valtimo's own "create a new dossier" flow,
 * behind `/admin/dossiers`) is checked here too, not blocked outright like plugin-configuration
 * creation — see [TraineeOwnershipChecks.isOwnCaseDefinition]'s KDoc for how a self-created
 * dossier is recognized after the fact (`CaseDefinition.createdBy`), and
 * [TraineeOwnershipChecks.canCreateAnotherCaseDefinition] for the cap enforced here, before the
 * request ever reaches Valtimo's controller.
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
            ProcessLinkUpdateRequestDto::class.java.isAssignableFrom(methodParameter.parameterType) ||
            NewDocumentRequest::class.java.isAssignableFrom(methodParameter.parameterType) ||
            ModifyDocumentRequest::class.java.isAssignableFrom(methodParameter.parameterType) ||
            SearchRequest::class.java.isAssignableFrom(methodParameter.parameterType) ||
            CaseDefinitionDraftCreateRequest::class.java.isAssignableFrom(methodParameter.parameterType)

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
            is PluginProcessLinkCreateDto -> {
                requireOwnProcessDefinition(traineeIdentity, body.processDefinitionId)
                requireOwnPluginConfigurationReference(traineeIdentity, body.referenceType, body.pluginConfigurationId)
            }
            is PluginProcessLinkUpdateDto -> {
                requireOwnProcessDefinition(traineeIdentity, ownershipChecks.resolveProcessDefinitionIdOfProcessLink(body.id))
                requireOwnPluginConfigurationReference(traineeIdentity, body.referenceType, body.pluginConfigurationId)
            }
            is ProcessLinkCreateRequestDto ->
                requireOwnProcessDefinition(traineeIdentity, body.processDefinitionId)
            is ProcessLinkUpdateRequestDto ->
                requireOwnProcessDefinition(traineeIdentity, ownershipChecks.resolveProcessDefinitionIdOfProcessLink(body.id))
            is NewDocumentRequest ->
                requireOwnCaseDefinition(traineeIdentity, body.documentDefinitionName())
            is ModifyDocumentRequest ->
                requireOwnDocument(traineeIdentity, body.documentId())
            is SearchRequest ->
                // No allowShared — see TraineeOwnershipInterceptor's document-id check for why
                // sharing form-flow-demo's structure doesn't extend to searching its actual data.
                requireOwnCaseDefinition(traineeIdentity, body.documentDefinitionName)
            is CaseDefinitionDraftCreateRequest ->
                requireCanCreateCaseDefinition(traineeIdentity, body)
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

    private fun requireOwnCaseDefinition(
        traineeIdentity: String,
        caseDefinitionKey: String?,
        allowShared: Boolean = false,
    ) {
        if (caseDefinitionKey == null || !ownershipChecks.isOwnCaseDefinition(traineeIdentity, caseDefinitionKey, allowShared)) {
            throw AccessDeniedException("Not your dossier")
        }
    }

    private fun requireOwnPluginConfigurationReference(
        traineeIdentity: String,
        referenceType: PluginConfigurationReferenceType,
        pluginConfigurationId: UUID?,
    ) {
        if (referenceType != PluginConfigurationReferenceType.FIXED) return
        val id = pluginConfigurationId ?: return
        if (!ownershipChecks.isOwnPluginConfiguration(traineeIdentity, id.toString(), allowShared = true)) {
            throw AccessDeniedException("Not your dossier")
        }
    }

    private fun requireOwnDocument(
        traineeIdentity: String,
        documentId: String?,
    ) {
        if (documentId == null || !ownershipChecks.isOwnDocument(traineeIdentity, documentId)) {
            throw AccessDeniedException("Not your dossier")
        }
    }

    private fun requireCanCreateCaseDefinition(
        traineeIdentity: String,
        request: CaseDefinitionDraftCreateRequest,
    ) {
        if (ownershipChecks.caseDefinitionKeyExists(request.caseDefinitionKey)) {
            // An *existing* key (drafting a new version of it) must already be the caller's own —
            // otherwise a trainee could draft a new version of another trainee's dossier, or of a
            // shared/unrelated case type, by naming its key and picking any not-yet-used version
            // tag. Not a *new* dossier, so it never counts against the cap below, regardless of
            // whether the caller is already at it.
            if (!ownershipChecks.isOwnCaseDefinition(traineeIdentity, request.caseDefinitionKey)) {
                throw AccessDeniedException("Not your dossier")
            }
            return
        }
        if (!ownershipChecks.canCreateAnotherCaseDefinition(traineeIdentity)) {
            throw AccessDeniedException(
                "You already have the maximum of " +
                    "${TraineeOwnershipChecks.MAX_TRAINEE_CREATED_CASE_DEFINITIONS} self-created dossiers",
            )
        }
    }
}