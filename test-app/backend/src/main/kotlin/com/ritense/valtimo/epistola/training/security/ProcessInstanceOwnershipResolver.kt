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
        val businessKey =
            runCatching {
                runtimeService
                    .createProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .singleResult()
                    ?.businessKey
            }.getOrNull() ?: return null
        return documentOwnershipResolver.resolveCaseDefinitionKey(businessKey)
    }

    /** An execution's own id resolves to its process instance first — an execution never carries a business key directly. */
    fun resolveCaseDefinitionKeyForExecution(executionId: String): String? {
        val processInstanceId =
            runCatching {
                runtimeService
                    .createExecutionQuery()
                    .executionId(executionId)
                    .singleResult()
                    ?.processInstanceId
            }.getOrNull() ?: return null
        return resolveCaseDefinitionKeyForProcessInstance(processInstanceId)
    }
}