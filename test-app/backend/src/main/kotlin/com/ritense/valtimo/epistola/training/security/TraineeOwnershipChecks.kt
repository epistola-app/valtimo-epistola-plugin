// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.plugin.domain.PluginConfigurationId
import com.ritense.processlink.domain.ProcessLink
import com.ritense.processlink.service.ProcessLinkService
import com.ritense.valtimo.epistola.training.TraineeIdentity
import com.ritense.valtimo.epistola.training.TraineeKeys
import com.ritense.valtimo.epistola.training.TrainingProperties
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * Shared ownership logic used by [TraineeOwnershipInterceptor], [TraineeOwnershipRequestBodyAdvice]
 * and [TraineeOwnershipResponseBodyAdvice] — kept in one place so the three enforcement points
 * (path/query params, request bodies, response bodies) can't drift apart.
 */
class TraineeOwnershipChecks(
    private val processDefinitionOwnershipResolver: ProcessDefinitionOwnershipResolver,
    private val processLinkService: ProcessLinkService,
    private val properties: TrainingProperties,
) {
    /** Null when the caller isn't a trainee at all — genuine `ROLE_ADMIN` staff are never scoped. */
    fun currentTraineeIdentityOrNull(): String? {
        val authentication = SecurityContextHolder.getContext().authentication ?: return null
        if (authentication.authorities.none { it.authority == TraineeKeys.TRAINEE_AUTHORITY }) return null
        return TraineeIdentity.resolve(authentication)
    }

    fun isOwnPluginConfiguration(
        traineeIdentity: String,
        pluginConfigurationId: String,
    ): Boolean =
        runCatching { PluginConfigurationId.existingId(pluginConfigurationId) }.getOrNull() ==
            TraineeKeys.pluginConfigurationId(traineeIdentity)

    /**
     * @param allowShared also accept the shared template's process definition — only safe for
     *   read-only checks, since the template must stay immutable for every trainee.
     */
    fun isOwnProcessDefinition(
        traineeIdentity: String,
        processDefinitionId: String,
        allowShared: Boolean = false,
    ): Boolean {
        val caseDefinitionKey = processDefinitionOwnershipResolver.resolveCaseDefinitionKey(processDefinitionId) ?: return false
        if (caseDefinitionKey == TraineeKeys.caseDefinitionKey(traineeIdentity)) return true
        return allowShared && caseDefinitionKey == properties.templateCaseDefinitionKey
    }

    fun resolveProcessDefinitionIdOfProcessLink(processLinkId: UUID): String? =
        runCatching { processLinkService.getProcessLink(processLinkId, ProcessLink::class.java).processDefinitionId }.getOrNull()

    /**
     * For the case-definition management surface (`CaseHttpSecurityConfigurer`,
     * `InternalCaseHttpSecurityConfigurer`): every endpoint there is path-scoped directly by the
     * case-/document-definition key (Valtimo's own controllers call it `caseDefinitionKey`,
     * `caseDefinitionName`, or bare `key` depending on the endpoint — verified against Valtimo
     * 13.44.0 source, not guessed — but it's always the same case key value), so this is a plain
     * string comparison, no resolution step needed.
     *
     * @param allowShared also accept the shared template's key — only safe for read-only checks.
     */
    fun isOwnCaseDefinition(
        traineeIdentity: String,
        caseDefinitionKey: String,
        allowShared: Boolean = false,
    ): Boolean {
        if (caseDefinitionKey == TraineeKeys.caseDefinitionKey(traineeIdentity)) return true
        return allowShared && caseDefinitionKey == properties.templateCaseDefinitionKey
    }
}