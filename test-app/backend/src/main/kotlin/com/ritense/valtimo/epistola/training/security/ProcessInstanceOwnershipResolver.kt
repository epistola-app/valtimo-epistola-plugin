// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import org.operaton.bpm.engine.RuntimeService

/**
 * Resolves which case-/document-definition a *runtime* process object — a process instance or an
 * execution — belongs to, via the same case-document business key
 * [DocumentOwnershipResolver]/[TaskOwnershipResolver] already key off.
 *
 * Confirmed against Valtimo 13.44.0 source (`ProcessResource.delete`,
 * `EpistolaAdminResource.reconcilePending`) before wiring this up, not guessed: both take a bare
 * id with nothing else in the request to check ownership against — exactly the shape
 * [TraineeAdminSurfaceGuardFilter] originally hard-blocked these behind, until this resolver made
 * scoping them possible instead.
 */
class ProcessInstanceOwnershipResolver(
    private val runtimeService: RuntimeService,
    private val documentOwnershipResolver: DocumentOwnershipResolver,
) {
    fun resolveCaseDefinitionKeyForProcessInstance(processInstanceId: String): String? {
        val businessKey = resolveDocumentIdForProcessInstance(processInstanceId) ?: return null
        return documentOwnershipResolver.resolveCaseDefinitionKey(businessKey)
    }

    /** The case document's id: Valtimo uses it as the business key of a case-driven process instance. */
    fun resolveDocumentIdForProcessInstance(processInstanceId: String): String? =
        runCatching {
            runtimeService
                .createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult()
                ?.businessKey
        }.getOrNull()

    /** An execution's own id resolves to its process instance first — an execution never carries a business key directly. */
    fun resolveCaseDefinitionKeyForExecution(executionId: String): String? {
        val processInstanceId = resolveProcessInstanceIdForExecution(executionId) ?: return null
        return resolveCaseDefinitionKeyForProcessInstance(processInstanceId)
    }

    fun resolveDocumentIdForExecution(executionId: String): String? {
        val processInstanceId = resolveProcessInstanceIdForExecution(executionId) ?: return null
        return resolveDocumentIdForProcessInstance(processInstanceId)
    }

    private fun resolveProcessInstanceIdForExecution(executionId: String): String? =
        runCatching {
            runtimeService
                .createExecutionQuery()
                .executionId(executionId)
                .singleResult()
                ?.processInstanceId
        }.getOrNull()
}