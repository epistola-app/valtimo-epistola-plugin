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
            properties = properties,
        )

    private fun caseDefinition(
        key: String,
        createdBy: String?,
    ): CaseDefinition =
        CaseDefinition(
            id = CaseDefinitionId(key, "1.0.0"),
            name = key,
            createdBy = createdBy,
            createdDate = null,
        )

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
    fun `isOwnOrTemplatePluginUsage accepts the shared configuration only for the template dossier`() {
        val shared = TraineeKeys.TEMPLATE_PLUGIN_CONFIGURATION_ID.toString()

        assertThat(checks.isOwnOrTemplatePluginUsage(TRAINEE, shared, properties.templateCaseDefinitionKey)).isTrue()
    }

    @Test
    fun `isOwnOrTemplatePluginUsage rejects other case-definitions that merely share the configuration`() {
        // Found by actually loading the admin page as a trainee: this test-app's own bundled demo
        // case types (e.g. "example") also wire their process-links through the same shared
        // "Epistola Document Suite" configuration as form-flow-demo — plain "same configuration"
        // is not precise enough.
        val shared = TraineeKeys.TEMPLATE_PLUGIN_CONFIGURATION_ID.toString()

        assertThat(checks.isOwnOrTemplatePluginUsage(TRAINEE, shared, "example")).isFalse()
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
    fun `isOwnDocument resolves the document's case-definition and compares it like isOwnCaseDefinition`() {
        val ownKey = TraineeKeys.caseDefinitionKey(TRAINEE)
        whenever(documentOwnershipResolver.resolveCaseDefinitionKey("own-doc")).thenReturn(ownKey)
        whenever(documentOwnershipResolver.resolveCaseDefinitionKey("other-doc")).thenReturn("some-unrelated-case")
        whenever(documentOwnershipResolver.resolveCaseDefinitionKey("template-doc")).thenReturn(properties.templateCaseDefinitionKey)
        whenever(documentOwnershipResolver.resolveCaseDefinitionKey("unresolvable-doc")).thenReturn(null)

        assertThat(checks.isOwnDocument(TRAINEE, "own-doc")).isTrue()
        assertThat(checks.isOwnDocument(TRAINEE, "other-doc")).isFalse()
        assertThat(checks.isOwnDocument(TRAINEE, "template-doc")).isFalse()
        assertThat(checks.isOwnDocument(TRAINEE, "template-doc", allowShared = true)).isTrue()
        // A document that no longer resolves (deleted, or the resolver failed) must fail closed,
        // not be silently treated as owned.
        assertThat(checks.isOwnDocument(TRAINEE, "unresolvable-doc")).isFalse()
    }

    @Test
    fun `isOwnTask resolves the task's case-definition and compares it like isOwnCaseDefinition`() {
        val ownKey = TraineeKeys.caseDefinitionKey(TRAINEE)
        whenever(taskOwnershipResolver.resolveCaseDefinitionKey("own-task")).thenReturn(ownKey)
        whenever(taskOwnershipResolver.resolveCaseDefinitionKey("other-task")).thenReturn("some-unrelated-case")
        whenever(taskOwnershipResolver.resolveCaseDefinitionKey("unresolvable-task")).thenReturn(null)

        assertThat(checks.isOwnTask(TRAINEE, "own-task")).isTrue()
        assertThat(checks.isOwnTask(TRAINEE, "other-task")).isFalse()
        assertThat(checks.isOwnTask(TRAINEE, "unresolvable-task")).isFalse()
    }

    @Test
    fun `isOwnProcessInstance resolves the instance's case-definition and never accepts the shared template`() {
        val ownKey = TraineeKeys.caseDefinitionKey(TRAINEE)
        whenever(processInstanceOwnershipResolver.resolveCaseDefinitionKeyForProcessInstance("own-instance")).thenReturn(ownKey)
        whenever(processInstanceOwnershipResolver.resolveCaseDefinitionKeyForProcessInstance("other-instance"))
            .thenReturn("some-unrelated-case")
        whenever(processInstanceOwnershipResolver.resolveCaseDefinitionKeyForProcessInstance("template-instance"))
            .thenReturn(properties.templateCaseDefinitionKey)
        whenever(processInstanceOwnershipResolver.resolveCaseDefinitionKeyForProcessInstance("unresolvable-instance")).thenReturn(null)

        assertThat(checks.isOwnProcessInstance(TRAINEE, "own-instance")).isTrue()
        assertThat(checks.isOwnProcessInstance(TRAINEE, "other-instance")).isFalse()
        // No allowShared parameter at all — force-deleting the shared template's own process
        // instance must never be allowed, not even read-only, since delete has no read-only form.
        assertThat(checks.isOwnProcessInstance(TRAINEE, "template-instance")).isFalse()
        assertThat(checks.isOwnProcessInstance(TRAINEE, "unresolvable-instance")).isFalse()
    }

    @Test
    fun `isOwnExecution resolves the execution's case-definition and never accepts the shared template`() {
        val ownKey = TraineeKeys.caseDefinitionKey(TRAINEE)
        whenever(processInstanceOwnershipResolver.resolveCaseDefinitionKeyForExecution("own-execution")).thenReturn(ownKey)
        whenever(processInstanceOwnershipResolver.resolveCaseDefinitionKeyForExecution("other-execution"))
            .thenReturn("some-unrelated-case")
        whenever(processInstanceOwnershipResolver.resolveCaseDefinitionKeyForExecution("template-execution"))
            .thenReturn(properties.templateCaseDefinitionKey)
        whenever(processInstanceOwnershipResolver.resolveCaseDefinitionKeyForExecution("unresolvable-execution")).thenReturn(null)

        assertThat(checks.isOwnExecution(TRAINEE, "own-execution")).isTrue()
        assertThat(checks.isOwnExecution(TRAINEE, "other-execution")).isFalse()
        assertThat(checks.isOwnExecution(TRAINEE, "template-execution")).isFalse()
        assertThat(checks.isOwnExecution(TRAINEE, "unresolvable-execution")).isFalse()
    }
}