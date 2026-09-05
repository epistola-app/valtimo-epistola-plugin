// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.TaskService

/**
 * Resolves which case-/document-definition a user task belongs to.
 *
 * A task identifies its process instance, not a document — the case document (== Valtimo's
 * `businessKey` for a dossier-driven process, confirmed project-wide, see this repo's own
 * `EpistolaPluginResource`'s task-scoped authorization) has to be read back from the process
 * instance itself, then resolved the same way [DocumentOwnershipResolver] resolves any other
 * document id.
 */
class TaskOwnershipResolver(
    private val taskService: TaskService,
    private val runtimeService: RuntimeService,
    private val documentOwnershipResolver: DocumentOwnershipResolver,
) {
    fun resolveCaseDefinitionKey(taskId: String): String? {
        val processInstanceId =
            runCatching {
                taskService
                    .createTaskQuery()
                    .taskId(taskId)
                    .singleResult()
                    ?.processInstanceId
            }.getOrNull() ?: return null
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
}