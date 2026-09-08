// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.case.web.rest.dto.CaseDefinitionDraftCreateRequest
import com.ritense.plugin.domain.PluginConfigurationReferenceType
import com.ritense.plugin.web.rest.request.PluginProcessLinkCreateDto
import com.ritense.plugin.web.rest.request.PluginProcessLinkUpdateDto
import com.ritense.processlink.domain.ActivityTypeWithEventName
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.core.MethodParameter
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.security.access.AccessDeniedException
import java.util.UUID

private const val TRAINEE = "trainee1@demo"

/**
 * Focused on [CaseDefinitionDraftCreateRequest] and the plugin-process-link `pluginConfigurationId`
 * check — the ownership-resolution logic itself (fail-closed, the shared-template rules) is
 * [TraineeOwnershipChecksTest]'s job. The other body types this advice handles (plugin
 * configuration creation, plain process-link `processDefinitionId`, document) predate this class'
 * dedicated test coverage and aren't backfilled here.
 */
class TraineeOwnershipRequestBodyAdviceTest {
    private val ownershipChecks: TraineeOwnershipChecks = mock()
    private val advice = TraineeOwnershipRequestBodyAdvice(ownershipChecks)
    private val inputMessage: HttpInputMessage = mock()
    private val parameter: MethodParameter = mock()
    private val converterType: Class<out HttpMessageConverter<*>> = HttpMessageConverter::class.java

    @Test
    fun `passes through untouched for a non-trainee caller`() {
        whenever(ownershipChecks.currentTraineeIdentityOrNull()).thenReturn(null)
        val request = draftRequest("brand-new-key")

        val result = afterBodyRead(request)

        assertThat(result).isSameAs(request)
    }

    @Test
    fun `allows a brand-new key once the trainee is under the cap`() {
        whenever(ownershipChecks.currentTraineeIdentityOrNull()).thenReturn(TRAINEE)
        whenever(ownershipChecks.caseDefinitionKeyExists("brand-new-key")).thenReturn(false)
        whenever(ownershipChecks.canCreateAnotherCaseDefinition(TRAINEE)).thenReturn(true)
        val request = draftRequest("brand-new-key")

        val result = afterBodyRead(request)

        assertThat(result).isSameAs(request)
    }

    @Test
    fun `rejects a brand-new key once the trainee has reached the cap`() {
        whenever(ownershipChecks.currentTraineeIdentityOrNull()).thenReturn(TRAINEE)
        whenever(ownershipChecks.caseDefinitionKeyExists("brand-new-key")).thenReturn(false)
        whenever(ownershipChecks.canCreateAnotherCaseDefinition(TRAINEE)).thenReturn(false)

        assertThatThrownBy { afterBodyRead(draftRequest("brand-new-key")) }
            .isInstanceOf(AccessDeniedException::class.java)
    }

    @Test
    fun `allows drafting a new version of a key the trainee already owns, regardless of the cap`() {
        whenever(ownershipChecks.currentTraineeIdentityOrNull()).thenReturn(TRAINEE)
        whenever(ownershipChecks.caseDefinitionKeyExists("my-own-flow")).thenReturn(true)
        whenever(ownershipChecks.isOwnCaseDefinition(TRAINEE, "my-own-flow")).thenReturn(true)
        // Drafting a new version of an already-owned key isn't a *new* dossier, so it must not be
        // blocked by the cap even if the trainee happens to be at it.
        whenever(ownershipChecks.canCreateAnotherCaseDefinition(TRAINEE)).thenReturn(false)
        val request = draftRequest("my-own-flow")

        val result = afterBodyRead(request)

        assertThat(result).isSameAs(request)
    }

    @Test
    fun `rejects drafting a new version of a key the trainee does not own`() {
        whenever(ownershipChecks.currentTraineeIdentityOrNull()).thenReturn(TRAINEE)
        whenever(ownershipChecks.caseDefinitionKeyExists("someone-elses-flow")).thenReturn(true)
        whenever(ownershipChecks.isOwnCaseDefinition(TRAINEE, "someone-elses-flow")).thenReturn(false)

        assertThatThrownBy { afterBodyRead(draftRequest("someone-elses-flow")) }
            .isInstanceOf(AccessDeniedException::class.java)
    }

    @Test
    fun `plugin process-link create rejects a pluginConfigurationId the trainee does not own`() {
        whenever(ownershipChecks.currentTraineeIdentityOrNull()).thenReturn(TRAINEE)
        whenever(ownershipChecks.isOwnProcessDefinition(TRAINEE, "own-process-definition")).thenReturn(true)
        val othersConfigId = UUID.randomUUID()
        whenever(ownershipChecks.isOwnPluginConfiguration(TRAINEE, othersConfigId.toString(), allowShared = true)).thenReturn(false)

        assertThatThrownBy {
            afterBodyRead(
                pluginProcessLinkCreate(processDefinitionId = "own-process-definition", pluginConfigurationId = othersConfigId),
            )
        }.isInstanceOf(AccessDeniedException::class.java)
    }

    @Test
    fun `plugin process-link create allows the trainee's own pluginConfigurationId`() {
        whenever(ownershipChecks.currentTraineeIdentityOrNull()).thenReturn(TRAINEE)
        whenever(ownershipChecks.isOwnProcessDefinition(TRAINEE, "own-process-definition")).thenReturn(true)
        val ownConfigId = UUID.randomUUID()
        whenever(ownershipChecks.isOwnPluginConfiguration(TRAINEE, ownConfigId.toString(), allowShared = true)).thenReturn(true)
        val request = pluginProcessLinkCreate(processDefinitionId = "own-process-definition", pluginConfigurationId = ownConfigId)

        val result = afterBodyRead(request)

        assertThat(result).isSameAs(request)
    }

    @Test
    fun `plugin process-link update rejects a pluginConfigurationId the trainee does not own`() {
        whenever(ownershipChecks.currentTraineeIdentityOrNull()).thenReturn(TRAINEE)
        val linkId = UUID.randomUUID()
        whenever(ownershipChecks.resolveProcessDefinitionIdOfProcessLink(linkId)).thenReturn("own-process-definition")
        whenever(ownershipChecks.isOwnProcessDefinition(TRAINEE, "own-process-definition")).thenReturn(true)
        val othersConfigId = UUID.randomUUID()
        whenever(ownershipChecks.isOwnPluginConfiguration(TRAINEE, othersConfigId.toString(), allowShared = true)).thenReturn(false)

        assertThatThrownBy {
            afterBodyRead(pluginProcessLinkUpdate(id = linkId, pluginConfigurationId = othersConfigId))
        }.isInstanceOf(AccessDeniedException::class.java)
    }

    @Test
    fun `plugin process-link create does not check ownership for a BUILDING_BLOCK reference`() {
        whenever(ownershipChecks.currentTraineeIdentityOrNull()).thenReturn(TRAINEE)
        whenever(ownershipChecks.isOwnProcessDefinition(TRAINEE, "own-process-definition")).thenReturn(true)
        val request =
            pluginProcessLinkCreate(
                processDefinitionId = "own-process-definition",
                pluginConfigurationId = UUID.randomUUID(),
                referenceType = PluginConfigurationReferenceType.BUILDING_BLOCK,
            )

        val result = afterBodyRead(request)

        assertThat(result).isSameAs(request)
    }

    private fun pluginProcessLinkCreate(
        processDefinitionId: String,
        pluginConfigurationId: UUID?,
        referenceType: PluginConfigurationReferenceType = PluginConfigurationReferenceType.FIXED,
    ) = PluginProcessLinkCreateDto(
        processDefinitionId = processDefinitionId,
        activityId = "some-activity",
        pluginConfigurationId = pluginConfigurationId,
        pluginActionDefinitionKey = "generate-document",
        activityType = ActivityTypeWithEventName.TASK_START,
        referenceType = referenceType,
    )

    private fun pluginProcessLinkUpdate(
        id: UUID,
        pluginConfigurationId: UUID?,
        referenceType: PluginConfigurationReferenceType = PluginConfigurationReferenceType.FIXED,
    ) = PluginProcessLinkUpdateDto(
        id = id,
        pluginConfigurationId = pluginConfigurationId,
        pluginActionDefinitionKey = "generate-document",
        referenceType = referenceType,
    )

    private fun draftRequest(caseDefinitionKey: String) =
        CaseDefinitionDraftCreateRequest(
            caseDefinitionKey = caseDefinitionKey,
            caseDefinitionVersion = "1.0.0",
            name = "Some dossier",
        )

    private fun afterBodyRead(body: Any): Any =
        advice.afterBodyRead(body, inputMessage, parameter, HttpServletRequest::class.java, converterType)
}