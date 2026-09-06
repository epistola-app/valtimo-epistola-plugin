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
    private val documentOwnershipResolver: DocumentOwnershipResolver,
    private val taskOwnershipResolver: TaskOwnershipResolver,
    private val processInstanceOwnershipResolver: ProcessInstanceOwnershipResolver,
    private val properties: TrainingProperties,
) {
    /** Null when the caller isn't a trainee at all — genuine `ROLE_ADMIN` staff are never scoped. */
    fun currentTraineeIdentityOrNull(): String? {
        val authentication = SecurityContextHolder.getContext().authentication ?: return null
        if (authentication.authorities.none { it.authority == TraineeKeys.TRAINEE_AUTHORITY }) return null
        return TraineeIdentity.resolve(authentication)
    }

    /**
     * @param allowShared also accept the shared template's Epistola plugin configuration — only
     *   safe for read-only checks (e.g. the admin page's health/usage overviews), since that
     *   configuration must stay immutable for every trainee.
     */
    fun isOwnPluginConfiguration(
        traineeIdentity: String,
        pluginConfigurationId: String,
        allowShared: Boolean = false,
    ): Boolean {
        val id = runCatching { PluginConfigurationId.existingId(pluginConfigurationId) }.getOrNull() ?: return false
        if (id == TraineeKeys.pluginConfigurationId(traineeIdentity)) return true
        return allowShared && id == TraineeKeys.TEMPLATE_PLUGIN_CONFIGURATION_ID
    }

    /**
     * Scopes the admin page's per-tenant data (e.g. pending jobs) to the caller's own Epistola
     * tenant. Deliberately no `allowShared` here, unlike [isOwnPluginConfiguration] — pending jobs
     * are operational data about in-flight processes, not a read-only structural reference, so the
     * shared template's tenant stays fully hidden rather than visible-but-immutable. A `null`
     * tenant id (the `PendingJob.STATUS_UNWIRED` case, where the tenant is unknowable) never
     * matches, so it's hidden from trainees rather than guessed at.
     */
    fun isOwnEpistolaTenant(
        traineeIdentity: String,
        tenantId: String?,
    ): Boolean = tenantId != null && tenantId == TraineeKeys.epistolaTenantId(traineeIdentity)

    /**
     * For the admin page's plugin-usage overview specifically: [isOwnPluginConfiguration]'s plain
     * `allowShared` isn't precise enough here, because this test-app's own bundled demo case types
     * (e.g. `example`) also wire their process-links through the same shared "Epistola Document
     * Suite" configuration — found by actually loading the admin page as a trainee and seeing
     * unrelated case types' usage entries leak through. A usage entry only counts as "shared" when
     * it belongs to the shared template dossier itself, not merely to the same configuration.
     */
    fun isOwnOrTemplatePluginUsage(
        traineeIdentity: String,
        pluginConfigurationId: String,
        caseDefinitionKey: String,
    ): Boolean {
        if (isOwnPluginConfiguration(traineeIdentity, pluginConfigurationId)) return true
        return isOwnPluginConfiguration(traineeIdentity, pluginConfigurationId, allowShared = true) &&
            caseDefinitionKey == properties.templateCaseDefinitionKey
    }

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

    /**
     * Data-plane counterpart to [isOwnCaseDefinition]: a document instance identifies itself only
     * by its own id, never by document-/case-definition name directly, so it needs resolving via
     * [DocumentOwnershipResolver] first. `false` (not owned) when the document doesn't exist or its
     * definition can't be resolved, same fail-closed default as every other resolver-backed check
     * here.
     *
     * @param allowShared also accept a document belonging to the shared template dossier — only
     *   safe for read-only checks.
     */
    fun isOwnDocument(
        traineeIdentity: String,
        documentId: String,
        allowShared: Boolean = false,
    ): Boolean {
        val caseDefinitionKey = documentOwnershipResolver.resolveCaseDefinitionKey(documentId) ?: return false
        return isOwnCaseDefinition(traineeIdentity, caseDefinitionKey, allowShared)
    }

    /**
     * Data-plane counterpart to [isOwnCaseDefinition] for user tasks: a task identifies only its
     * own id, resolved back to the owning case document (and from there, the document definition)
     * via [TaskOwnershipResolver].
     *
     * @param allowShared also accept a task belonging to the shared template dossier — only safe
     *   for read-only checks (view/list), never for mutations (assign/complete/etc.).
     */
    fun isOwnTask(
        traineeIdentity: String,
        taskId: String,
        allowShared: Boolean = false,
    ): Boolean {
        val caseDefinitionKey = taskOwnershipResolver.resolveCaseDefinitionKey(taskId) ?: return false
        return isOwnCaseDefinition(traineeIdentity, caseDefinitionKey, allowShared)
    }

    /**
     * Data-plane counterpart to [isOwnCaseDefinition] for a *runtime* process instance —
     * `POST /api/v1/process/{processInstanceId}/delete` takes a bare id with nothing else to check
     * ownership against, resolved via [ProcessInstanceOwnershipResolver]. No `allowShared`: this is
     * a destructive operation, never appropriate against the shared template regardless of read vs
     * write (there is no read-only variant of "delete").
     */
    fun isOwnProcessInstance(
        traineeIdentity: String,
        processInstanceId: String,
    ): Boolean {
        val caseDefinitionKey =
            processInstanceOwnershipResolver.resolveCaseDefinitionKeyForProcessInstance(processInstanceId) ?: return false
        return isOwnCaseDefinition(traineeIdentity, caseDefinitionKey)
    }

    /**
     * Data-plane counterpart to [isOwnCaseDefinition] for an execution —
     * `POST .../pending/{executionId}/reconcile` manually retries a trainee's own stuck Epistola
     * catch event, resolved via [ProcessInstanceOwnershipResolver]. No `allowShared`, same reasoning
     * as [isOwnProcessInstance] — reconciling is a mutation, and the shared template dossier is
     * meant to stay untouched by every trainee, not reconciled by whichever one happens to click it.
     */
    fun isOwnExecution(
        traineeIdentity: String,
        executionId: String,
    ): Boolean {
        val caseDefinitionKey = processInstanceOwnershipResolver.resolveCaseDefinitionKeyForExecution(executionId) ?: return false
        return isOwnCaseDefinition(traineeIdentity, caseDefinitionKey)
    }
}