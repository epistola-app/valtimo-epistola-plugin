// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.case_.domain.definition.CaseDefinition
import com.ritense.case_.repository.CaseDefinitionRepository
import com.ritense.processlink.service.ProcessLinkService
import com.ritense.valtimo.contract.case_.CaseDefinitionId
import com.ritense.valtimo.epistola.training.TraineeKeys
import com.ritense.valtimo.epistola.training.TrainingProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private const val TRAINEE = "trainee1@demo"
private const val OTHER_TRAINEE = "trainee2@demo"

class TraineeOwnershipChecksTest {
    private val properties = TrainingProperties()
    private val documentOwnershipResolver: DocumentOwnershipResolver = mock()
    private val taskOwnershipResolver: TaskOwnershipResolver = mock()
    private val processInstanceOwnershipResolver: ProcessInstanceOwnershipResolver = mock()
    private val caseDefinitionRepository: CaseDefinitionRepository = mock()
    private val checks =
        TraineeOwnershipChecks(
            processDefinitionOwnershipResolver = mock(),
            processLinkService = mock<ProcessLinkService>(),
            documentOwnershipResolver = documentOwnershipResolver,
            taskOwnershipResolver = taskOwnershipResolver,
            processInstanceOwnershipResolver = processInstanceOwnershipResolver,
            caseDefinitionRepository = caseDefinitionRepository,
        )

    private fun caseDefinition(
        key: String,
        createdBy: String?,
        originalKey: String? = null,
    ): CaseDefinition =
        CaseDefinition(
            id = CaseDefinitionId(key, "1.0.0"),
            name = key,
            createdBy = createdBy,
            createdDate = null,
            originalKey = originalKey,
        )

    private fun givenCaseDefinitions(vararg definitions: CaseDefinition) {
        definitions.groupBy { it.id.key }.forEach { (key, versions) ->
            whenever(caseDefinitionRepository.findAllByIdKeyOrderByIdVersionTagDesc(key)).thenReturn(versions)
        }
    }

    @Test
    fun `isOwnPluginConfiguration accepts only the caller's own configuration by default`() {
        val own = TraineeKeys.pluginConfigurationId(TRAINEE).toString()
        val other = TraineeKeys.pluginConfigurationId(OTHER_TRAINEE).toString()
        val shared = TraineeKeys.TEMPLATE_PLUGIN_CONFIGURATION_ID.toString()

        assertThat(checks.isOwnPluginConfiguration(TRAINEE, own)).isTrue()
        assertThat(checks.isOwnPluginConfiguration(TRAINEE, other)).isFalse()
        assertThat(checks.isOwnPluginConfiguration(TRAINEE, shared)).isFalse()
    }

    @Test
    fun `isOwnPluginConfiguration with allowShared also accepts the shared template configuration`() {
        val shared = TraineeKeys.TEMPLATE_PLUGIN_CONFIGURATION_ID.toString()
        val other = TraineeKeys.pluginConfigurationId(OTHER_TRAINEE).toString()

        assertThat(checks.isOwnPluginConfiguration(TRAINEE, shared, allowShared = true)).isTrue()
        assertThat(checks.isOwnPluginConfiguration(TRAINEE, other, allowShared = true)).isFalse()
    }

    @Test
    fun `isOwnEpistolaTenant matches only the caller's own tenant, never a null tenant`() {
        val own = TraineeKeys.epistolaTenantId(TRAINEE)
        val other = TraineeKeys.epistolaTenantId(OTHER_TRAINEE)

        assertThat(checks.isOwnEpistolaTenant(TRAINEE, own)).isTrue()
        assertThat(checks.isOwnEpistolaTenant(TRAINEE, other)).isFalse()
        assertThat(checks.isOwnEpistolaTenant(TRAINEE, "demo")).isFalse()
        assertThat(checks.isOwnEpistolaTenant(TRAINEE, null)).isFalse()
    }

    @Test
    fun `isOwnOrTemplatePluginUsage accepts the caller's own configuration regardless of case`() {
        val own = TraineeKeys.pluginConfigurationId(TRAINEE).toString()

        assertThat(checks.isOwnOrTemplatePluginUsage(TRAINEE, own, "some-unrelated-case")).isTrue()
    }

    @Test
    fun `isOwnOrTemplatePluginUsage accepts the shared configuration for a shared case type`() {
        val shared = TraineeKeys.TEMPLATE_PLUGIN_CONFIGURATION_ID.toString()
        givenCaseDefinitions(caseDefinition(properties.templateCaseDefinitionKey, createdBy = null))

        assertThat(checks.isOwnOrTemplatePluginUsage(TRAINEE, shared, properties.templateCaseDefinitionKey)).isTrue()
    }

    @Test
    fun `isOwnOrTemplatePluginUsage rejects another trainee's dossier that still references the shared configuration`() {
        // A clone keeps the template's plugin configuration until its trainee rewires it — plain
        // "same configuration" would show one trainee another's dossier on the admin page.
        val shared = TraineeKeys.TEMPLATE_PLUGIN_CONFIGURATION_ID.toString()
        val othersDossier = TraineeKeys.caseDefinitionKey(OTHER_TRAINEE)
        givenCaseDefinitions(caseDefinition(othersDossier, createdBy = null, originalKey = properties.templateCaseDefinitionKey))

        assertThat(checks.isOwnOrTemplatePluginUsage(TRAINEE, shared, othersDossier)).isFalse()
    }

    @Test
    fun `isSharedCaseDefinition accepts only bundled case types, never a clone, a self-created draft, or an unknown key`() {
        val othersDossier = TraineeKeys.caseDefinitionKey(OTHER_TRAINEE)
        givenCaseDefinitions(
            caseDefinition("permit", createdBy = null),
            caseDefinition(othersDossier, createdBy = null, originalKey = "form-flow-demo"),
            caseDefinition("someone-elses-flow", createdBy = OTHER_TRAINEE),
        )
        whenever(caseDefinitionRepository.findAllByIdKeyOrderByIdVersionTagDesc("unknown-key")).thenReturn(emptyList())

        assertThat(checks.isSharedCaseDefinition("permit")).isTrue()
        assertThat(checks.isSharedCaseDefinition(othersDossier)).isFalse()
        assertThat(checks.isSharedCaseDefinition("someone-elses-flow")).isFalse()
        assertThat(checks.isSharedCaseDefinition("unknown-key")).isFalse()
        // allowShared on isOwnCaseDefinition uses exactly this rule.
        assertThat(checks.isOwnCaseDefinition(TRAINEE, "permit")).isFalse()
        assertThat(checks.isOwnCaseDefinition(TRAINEE, "permit", allowShared = true)).isTrue()
        assertThat(checks.isOwnCaseDefinition(TRAINEE, othersDossier, allowShared = true)).isFalse()
    }

    @Test
    fun `isOwnOrTemplatePluginUsage rejects another trainee's configuration outright`() {
        val other = TraineeKeys.pluginConfigurationId(OTHER_TRAINEE).toString()

        assertThat(checks.isOwnOrTemplatePluginUsage(TRAINEE, other, "some-unrelated-case")).isFalse()
    }

    @Test
    fun `isOwnCaseDefinition also accepts a case-definition the trainee created themselves through admin_dossiers`() {
        val ownKey = TraineeKeys.caseDefinitionKey(TRAINEE)
        whenever(caseDefinitionRepository.findAllByIdKeyOrderByIdVersionTagDesc("my-own-flow"))
            .thenReturn(listOf(caseDefinition("my-own-flow", createdBy = TRAINEE)))
        whenever(caseDefinitionRepository.findAllByIdKeyOrderByIdVersionTagDesc("someone-elses-flow"))
            .thenReturn(listOf(caseDefinition("someone-elses-flow", createdBy = OTHER_TRAINEE)))
        whenever(caseDefinitionRepository.findAllByIdKeyOrderByIdVersionTagDesc("unknown-key")).thenReturn(emptyList())

        // The auto-provisioned dossier's key still matches purely by hash, no repository call needed.
        assertThat(checks.isOwnCaseDefinition(TRAINEE, ownKey)).isTrue()
        assertThat(checks.isOwnCaseDefinition(TRAINEE, "my-own-flow")).isTrue()
        assertThat(checks.isOwnCaseDefinition(TRAINEE, "someone-elses-flow")).isFalse()
        assertThat(checks.isOwnCaseDefinition(TRAINEE, "unknown-key")).isFalse()
    }

    @Test
    fun `caseDefinitionKeyExists delegates to the repository`() {
        whenever(caseDefinitionRepository.existsByIdKey("my-flow")).thenReturn(true)
        whenever(caseDefinitionRepository.existsByIdKey("brand-new-key")).thenReturn(false)

        assertThat(checks.caseDefinitionKeyExists("my-flow")).isTrue()
        assertThat(checks.caseDefinitionKeyExists("brand-new-key")).isFalse()
    }

    @Test
    fun `canCreateAnotherCaseDefinition allows up to the cap and rejects beyond it, counting distinct keys not rows`() {
        val nineOwnKeys = (1..9).map { caseDefinition("flow-$it", createdBy = TRAINEE) }
        // A second version of an already-owned key must not count twice toward the cap.
        val secondVersionOfFlowOne = caseDefinition("flow-1", createdBy = TRAINEE).copy(id = CaseDefinitionId("flow-1", "2.0.0"))
        val otherTraineesFlow = caseDefinition("other-flow", createdBy = OTHER_TRAINEE)

        whenever(caseDefinitionRepository.findAll()).thenReturn(nineOwnKeys + secondVersionOfFlowOne + otherTraineesFlow)
        assertThat(checks.canCreateAnotherCaseDefinition(TRAINEE)).isTrue()

        whenever(caseDefinitionRepository.findAll())
            .thenReturn(nineOwnKeys + caseDefinition("flow-10", createdBy = TRAINEE) + otherTraineesFlow)
        assertThat(checks.canCreateAnotherCaseDefinition(TRAINEE)).isFalse()
    }

    @Test
    fun `isOwnDocument accepts exactly the documents the caller created, whatever case type they belong to`() {
        whenever(documentOwnershipResolver.resolveCreatedBy("own-doc")).thenReturn(TRAINEE)
        whenever(documentOwnershipResolver.resolveCreatedBy("other-doc")).thenReturn(OTHER_TRAINEE)
        whenever(documentOwnershipResolver.resolveCreatedBy("unresolvable-doc")).thenReturn(null)

        assertThat(checks.isOwnDocument(TRAINEE, "own-doc")).isTrue()
        assertThat(checks.isOwnDocument(TRAINEE, "other-doc")).isFalse()
        // A document that no longer resolves (deleted, or the resolver failed) must fail closed,
        // not be silently treated as owned.
        assertThat(checks.isOwnDocument(TRAINEE, "unresolvable-doc")).isFalse()
    }

    @Test
    fun `isOwnTask resolves the task's case document and applies isOwnDocument`() {
        whenever(taskOwnershipResolver.resolveDocumentId("own-task")).thenReturn("own-doc")
        whenever(taskOwnershipResolver.resolveDocumentId("other-task")).thenReturn("other-doc")
        whenever(taskOwnershipResolver.resolveDocumentId("unresolvable-task")).thenReturn(null)
        whenever(documentOwnershipResolver.resolveCreatedBy("own-doc")).thenReturn(TRAINEE)
        whenever(documentOwnershipResolver.resolveCreatedBy("other-doc")).thenReturn(OTHER_TRAINEE)

        assertThat(checks.isOwnTask(TRAINEE, "own-task")).isTrue()
        assertThat(checks.isOwnTask(TRAINEE, "other-task")).isFalse()
        assertThat(checks.isOwnTask(TRAINEE, "unresolvable-task")).isFalse()
    }

    @Test
    fun `isOwnProcessInstance and isOwnExecution resolve their case document and apply isOwnDocument`() {
        whenever(processInstanceOwnershipResolver.resolveDocumentIdForProcessInstance("own-instance")).thenReturn("own-doc")
        whenever(processInstanceOwnershipResolver.resolveDocumentIdForProcessInstance("other-instance")).thenReturn("other-doc")
        whenever(processInstanceOwnershipResolver.resolveDocumentIdForProcessInstance("unresolvable-instance")).thenReturn(null)
        whenever(processInstanceOwnershipResolver.resolveDocumentIdForExecution("own-execution")).thenReturn("own-doc")
        whenever(processInstanceOwnershipResolver.resolveDocumentIdForExecution("other-execution")).thenReturn("other-doc")
        whenever(processInstanceOwnershipResolver.resolveDocumentIdForExecution("unresolvable-execution")).thenReturn(null)
        whenever(documentOwnershipResolver.resolveCreatedBy("own-doc")).thenReturn(TRAINEE)
        whenever(documentOwnershipResolver.resolveCreatedBy("other-doc")).thenReturn(OTHER_TRAINEE)

        assertThat(checks.isOwnProcessInstance(TRAINEE, "own-instance")).isTrue()
        assertThat(checks.isOwnProcessInstance(TRAINEE, "other-instance")).isFalse()
        assertThat(checks.isOwnProcessInstance(TRAINEE, "unresolvable-instance")).isFalse()
        assertThat(checks.isOwnExecution(TRAINEE, "own-execution")).isTrue()
        assertThat(checks.isOwnExecution(TRAINEE, "other-execution")).isFalse()
        assertThat(checks.isOwnExecution(TRAINEE, "unresolvable-execution")).isFalse()
    }
}