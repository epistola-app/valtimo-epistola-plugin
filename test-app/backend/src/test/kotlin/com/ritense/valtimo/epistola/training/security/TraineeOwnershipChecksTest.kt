// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.processlink.service.ProcessLinkService
import com.ritense.valtimo.epistola.training.TraineeKeys
import com.ritense.valtimo.epistola.training.TrainingProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

private const val TRAINEE = "trainee1@demo"
private const val OTHER_TRAINEE = "trainee2@demo"

class TraineeOwnershipChecksTest {
    private val properties = TrainingProperties()
    private val checks =
        TraineeOwnershipChecks(
            processDefinitionOwnershipResolver = mock(),
            processLinkService = mock<ProcessLinkService>(),
            properties = properties,
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
}