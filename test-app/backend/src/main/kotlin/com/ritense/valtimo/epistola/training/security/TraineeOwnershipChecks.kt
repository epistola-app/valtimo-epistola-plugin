// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.case_.repository.CaseDefinitionRepository
import com.ritense.plugin.domain.PluginConfigurationId
import com.ritense.processlink.domain.ProcessLink
import com.ritense.processlink.service.ProcessLinkService
import com.ritense.valtimo.epistola.training.TraineeIdentity
import com.ritense.valtimo.epistola.training.TraineeKeys
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
    private val caseDefinitionRepository: CaseDefinitionRepository,
) {
    /** Null when the caller isn't a trainee at all — genuine `ROLE_ADMIN` staff are never scoped. */
    fun currentTraineeOrNull(): Trainee? {
        val authentication = SecurityContextHolder.getContext().authentication ?: return null
        if (authentication.authorities.none { it.authority == TraineeKeys.TRAINEE_AUTHORITY }) return null
        return Trainee(identity = TraineeIdentity.resolve(authentication), login = authentication.name)
    }

    fun currentTraineeIdentityOrNull(): String? = currentTraineeOrNull()?.identity

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
     * it belongs to a shared case type ([isSharedCaseDefinition]), not merely to the same
     * configuration — a trainee's clone also references it until they rewire it.
     */
    fun isOwnOrTemplatePluginUsage(
        traineeIdentity: String,
        pluginConfigurationId: String,
        caseDefinitionKey: String,
    ): Boolean {
        if (isOwnPluginConfiguration(traineeIdentity, pluginConfigurationId)) return true
        return isOwnPluginConfiguration(traineeIdentity, pluginConfigurationId, allowShared = true) &&
            isSharedCaseDefinition(caseDefinitionKey)
    }

    /**
     * @param allowShared also accept a process definition of a shared case type
     *   ([isSharedCaseDefinition]) — only safe for read-only checks, since shared case types must
     *   stay immutable for every trainee.
     */
    fun isOwnProcessDefinition(
        traineeIdentity: String,
        processDefinitionId: String,
        allowShared: Boolean = false,
    ): Boolean {
        val caseDefinitionKey = processDefinitionOwnershipResolver.resolveCaseDefinitionKey(processDefinitionId) ?: return false
        return isOwnCaseDefinition(traineeIdentity, caseDefinitionKey, allowShared)
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
     * @param allowShared also accept a shared case type ([isSharedCaseDefinition]) — only safe for
     *   read-only checks: every trainee may look at a shared case type's configuration, none may
     *   change it.
     *
     * Beyond the one auto-provisioned dossier (whose key already **is** [TraineeKeys.caseDefinitionKey]),
     * a trainee can also create additional case-definitions of their own through Valtimo's own
     * `/admin/dossiers` UI (`POST .../case-definition/draft` — see [canCreateAnotherCaseDefinition]
     * for the cap on how many). Those aren't named by a hash of the trainee's identity — the
     * trainee picks the key themselves — so they're recognized instead by
     * `CaseDefinition.createdBy`, a real Valtimo column `CaseDefinitionService.createCaseDefinitionDraft`
     * already populates from the authenticated caller via `SecurityUtils.getCurrentUserLogin()`
     * (`= authentication.getName()`). Confirmed to resolve to exactly the same value as
     * [TraineeIdentity.resolve] in this app — both land on the JWT's `email` claim (this app's own
     * `Jwt.toAuthenticationToken()` builds `JwtAuthenticationToken` with an explicit
     * `email ?: preferred_username ?: subject` principal, not Spring's default `sub`-based name) —
     * so no extra code is needed to populate it correctly. No new table: this is Valtimo's own
     * existing column, queried on demand, not cached anywhere.
     */
    fun isOwnCaseDefinition(
        traineeIdentity: String,
        caseDefinitionKey: String,
        allowShared: Boolean = false,
    ): Boolean {
        if (caseDefinitionKey == TraineeKeys.caseDefinitionKey(traineeIdentity)) return true
        if (allowShared && isSharedCaseDefinition(caseDefinitionKey)) return true
        return isTraineeCreatedCaseDefinition(traineeIdentity, caseDefinitionKey)
    }

    /**
     * A case type every trainee may see and work in: one this app ships (deployed from the
     * test-app's own `config/case` directory), as opposed to a trainee's cloned dossier or a case
     * definition someone created through `/admin/dossiers`.
     *
     * Recognised from Valtimo's own columns rather than a configured list, so a newly bundled demo
     * case type is shared without extra configuration: a clone made by [TraineeDossierProvisioner]
     * via Valtimo's import carries `originalKey` (the template it was copied from), and a draft
     * created through the admin UI carries `createdBy`. A bundled one has neither — confirmed on
     * the live demo database (`form-flow-demo`: both null; a trainee's clone: `originalKey =
     * form-flow-demo`). Every version must qualify, and an unknown key is not shared (fail closed).
     * A case type an administrator creates by hand is therefore not shared either, until someone
     * decides it should be.
     */
    fun isSharedCaseDefinition(caseDefinitionKey: String): Boolean {
        val versions = caseDefinitionRepository.findAllByIdKeyOrderByIdVersionTagDesc(caseDefinitionKey)
        return versions.isNotEmpty() && versions.all { it.createdBy == null && it.originalKey == null }
    }

    private fun isTraineeCreatedCaseDefinition(
        traineeIdentity: String,
        caseDefinitionKey: String,
    ): Boolean = caseDefinitionRepository.findAllByIdKeyOrderByIdVersionTagDesc(caseDefinitionKey).any { it.createdBy == traineeIdentity }

    /** Whether a case-definition with this key already exists — any version, draft or final. */
    fun caseDefinitionKeyExists(caseDefinitionKey: String): Boolean = caseDefinitionRepository.existsByIdKey(caseDefinitionKey)

    /**
     * Caps how many additional case-definitions (beyond the one auto-provisioned dossier) a
     * trainee can create through Valtimo's own `/admin/dossiers` UI — self-service creation is
     * otherwise unbounded, and every one is real infrastructure (schema, BPMN, forms) that nothing
     * currently cleans up (dossier retention/cleanup is a tracked gap — see the training facility
     * doc). Counts *distinct* case-definition keys, not rows: a case-definition can have several
     * draft/finalized versions under the same key, which is still one dossier, not several. Fetches
     * every case-definition rather than a filtered query — this is a demo/training instance, not a
     * production-scale one, and `CaseDefinitionRepository` (Valtimo's own, not ours to extend)
     * exposes no `createdBy`-filtered query to delegate to instead.
     */
    fun canCreateAnotherCaseDefinition(traineeIdentity: String): Boolean =
        traineeCreatedCaseDefinitionKeys(traineeIdentity).size < MAX_TRAINEE_CREATED_CASE_DEFINITIONS

    private fun traineeCreatedCaseDefinitionKeys(traineeIdentity: String): Set<String> =
        caseDefinitionRepository.findAll().filter { it.createdBy == traineeIdentity }.mapTo(mutableSetOf()) { it.id.key }

    /**
     * The data-plane rule: a trainee works on a case — sees it, fills its forms, completes its
     * tasks, writes its notes — exactly when they created it (`JsonSchemaDocument.createdBy`),
     * whatever case type it belongs to: a shared one or their own dossier. This is the same rule
     * `demo.permission.json` gives Valtimo's PBAC (`createdBy == ${currentUserEmail}`), so these
     * checks and PBAC agree; they are kept on the endpoints this package already guards as a second
     * line, and for the few endpoints (e.g. this plugin's `evaluate-mapping`) that load a document
     * without a PBAC check of their own.
     *
     * [login] is `Authentication.getName()` ([Trainee.login]) — the value Valtimo stamps into
     * `createdBy` (`SecurityUtils.getCurrentUserLogin()`), not [Trainee.identity]. In the authentik
     * profile both it and `${currentUserEmail}` resolve through the same claims (email, then
     * preferred_username, then sub), so they are always equal. Fails closed when the document
     * doesn't exist or has no creator.
     */
    fun isOwnDocument(
        login: String,
        documentId: String,
    ): Boolean {
        val createdBy = documentOwnershipResolver.resolveCreatedBy(documentId) ?: return false
        return createdBy == login
    }

    /** [isOwnDocument] for the case document a user task belongs to. */
    fun isOwnTask(
        login: String,
        taskId: String,
    ): Boolean {
        val documentId = taskOwnershipResolver.resolveDocumentId(taskId) ?: return false
        return isOwnDocument(login, documentId)
    }

    /**
     * [isOwnDocument] for a runtime process instance —
     * `POST /api/v1/process/{processInstanceId}/delete` takes a bare id, resolved to its case
     * document via [ProcessInstanceOwnershipResolver].
     */
    fun isOwnProcessInstance(
        login: String,
        processInstanceId: String,
    ): Boolean {
        val documentId = processInstanceOwnershipResolver.resolveDocumentIdForProcessInstance(processInstanceId) ?: return false
        return isOwnDocument(login, documentId)
    }

    /**
     * [isOwnDocument] for an execution — `POST .../pending/{executionId}/reconcile` manually
     * retries a stuck Epistola catch event on the caller's own case.
     */
    fun isOwnExecution(
        login: String,
        executionId: String,
    ): Boolean {
        val documentId = processInstanceOwnershipResolver.resolveDocumentIdForExecution(executionId) ?: return false
        return isOwnDocument(login, documentId)
    }

    companion object {
        const val MAX_TRAINEE_CREATED_CASE_DEFINITIONS = 10
    }
}

/**
 * The calling trainee. [identity] ([TraineeIdentity.resolve]) keys their provisioned dossier and
 * plugin configuration; [login] (`Authentication.getName()`) is what Valtimo records as the creator
 * of a case they start.
 */
data class Trainee(
    val identity: String,
    val login: String,
)