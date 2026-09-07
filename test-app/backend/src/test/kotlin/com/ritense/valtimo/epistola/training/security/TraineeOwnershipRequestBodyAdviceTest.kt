// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.case.web.rest.dto.CaseDefinitionDraftCreateRequest
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

private const val TRAINEE = "trainee1@demo"

/**
 * Focused on [CaseDefinitionDraftCreateRequest] handling — the ownership-resolution logic itself
 * (fail-closed, the shared-template rules) is [TraineeOwnershipChecksTest]'s job. The other body
 * types this advice handles (plugin configuration, process-link, document) predate this class'
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

    private fun draftRequest(caseDefinitionKey: String) =
        CaseDefinitionDraftCreateRequest(
            caseDefinitionKey = caseDefinitionKey,
            caseDefinitionVersion = "1.0.0",
            name = "Some dossier",
        )

    private fun afterBodyRead(body: Any): Any =
        advice.afterBodyRead(body, inputMessage, parameter, HttpServletRequest::class.java, converterType)
}