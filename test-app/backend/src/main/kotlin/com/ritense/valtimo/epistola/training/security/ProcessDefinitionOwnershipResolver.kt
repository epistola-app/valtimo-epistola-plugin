// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.valtimo.contract.case_.CaseDefinitionId
import org.operaton.bpm.engine.RepositoryService

/**
 * Resolves which case-definition a process-definition belongs to.
 *
 * Needed because `ProcessLinkResource` identifies process links by `processDefinitionId`, never
 * by case-definition key directly — and cloning a dossier does NOT give it its own BPMN process
 * key (`OperatonProcessDefinitionImporter` keeps `"form-flow-demo"` literal on every clone).
 * Valtimo disambiguates clones purely by `versionTag = "CD:" + caseDefinitionId`
 * (`updateCaseDefinitionProcessesVersionTags`), so that's the only place the owning
 * case-definition key can be read back from.
 */
class ProcessDefinitionOwnershipResolver(
    private val repositoryService: RepositoryService,
) {
    fun resolveCaseDefinitionKey(processDefinitionId: String): String? {
        val definition = runCatching { repositoryService.getProcessDefinition(processDefinitionId) }.getOrNull() ?: return null
        val versionTag = definition.versionTag ?: return null
        return runCatching { CaseDefinitionId.fromProcessVersionTag(versionTag)?.key }.getOrNull()
    }
}