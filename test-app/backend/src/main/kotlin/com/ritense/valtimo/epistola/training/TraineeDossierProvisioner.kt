// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ritense.case.service.CaseDefinitionService
import com.ritense.case_.repository.CaseDefinitionRepository
import com.ritense.exporter.ExportService
import com.ritense.exporter.request.CaseDefinitionExportRequest
import com.ritense.importer.ImportService
import com.ritense.plugin.service.PluginService
import com.ritense.valtimo.contract.case_.CaseDefinitionId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayInputStream

/**
 * The privileged part of provisioning a trainee's dossier. Split out of
 * [TraineeDossierProvisioningService] to separate the one-time provisioning work from the cheap
 * fast-path existence check.
 *
 * Deliberately **not** wrapped in `@Transactional`: Valtimo's own export/import machinery manages
 * its own transactional granularity per artifact (`ImportContext.Companion.runImporter` runs each
 * importer independently, and exporters like `FormDefinitionExporter` are tolerant of individual
 * lookup failures). Confirmed by actually running this against Testcontainers, not just reading
 * the source: wrapping this method in `@Transactional` made every single provisioning call fail
 * with `UnexpectedRollbackException` — exporting `form-flow-demo` throws a caught-and-ignored
 * `NoSuchElementException` (`form-flow-demo.summary` form not found, a pre-existing quirk of this
 * particular demo case, not a bug introduced here), and letting that exception cross into an
 * *ambient* transaction marks the whole thing rollback-only even though Valtimo's own code
 * tolerates it. The trade-off: this sequence isn't atomic — a crash mid-provisioning can leave a
 * partially-created dossier — so every step below is written to be safely retryable instead.
 */
class TraineeDossierProvisioner(
    private val exportService: ExportService,
    private val importService: ImportService,
    private val caseDefinitionRepository: CaseDefinitionRepository,
    private val caseDefinitionService: CaseDefinitionService,
    private val pluginService: PluginService,
    private val epistolaTenantProvisioner: EpistolaTenantProvisioner,
    private val properties: TrainingProperties,
    private val epistolaBaseUrl: String,
) {
    private val log = KotlinLogging.logger {}

    fun provision(traineeIdentity: String): CaseDefinitionId {
        val key = TraineeKeys.caseDefinitionKey(traineeIdentity)

        // Re-check: two requests from the same trainee could both have passed the fast-path check
        // in TraineeDossierProvisioningService before either finished.
        if (caseDefinitionRepository.existsByIdKey(key)) {
            return CaseDefinitionId(key, properties.templateCaseDefinitionVersionTag)
        }

        val pluginConfigurationId = TraineeKeys.pluginConfigurationId(traineeIdentity)
        ensurePluginConfiguration(traineeIdentity, pluginConfigurationId)

        val templateCaseDefinitionId = CaseDefinitionId(properties.templateCaseDefinitionKey, properties.templateCaseDefinitionVersionTag)
        val exported = exportService.export(CaseDefinitionExportRequest(templateCaseDefinitionId))

        val traineeCaseDefinitionId =
            importService.import(
                ByteArrayInputStream(exported.toByteArray()),
                caseDefinitionRepository.findAllByFinalTrue().map { it.id },
                key,
                "Dossier — $traineeIdentity",
                mapOf(TraineeKeys.TEMPLATE_PLUGIN_CONFIGURATION_ID.id to pluginConfigurationId.id),
            ) ?: error("Import of the training dossier for '$traineeIdentity' did not return a case definition id")

        caseDefinitionService.finalizeCaseDefinition(traineeCaseDefinitionId)
        log.info { "Provisioned training dossier '$key'" }
        return traineeCaseDefinitionId
    }

    /**
     * Idempotent: a retry after a partial failure must not choke on "plugin configuration already
     * exists". The flip side, found while manually testing against a shared secret that changed
     * partway through a session: once created, a trainee's `apiKey`/`tenantId` are never
     * refreshed, even if `epistola.training.epistola-shared-secret` (or the Epistola instance
     * behind `epistola.base-url`) changes later — an already-provisioned trainee's plugin
     * configuration then has to be re-saved by hand (`PUT /api/v1/plugin/configuration/{id}`) to
     * pick up the new value. Not a bug to fix here, just a real operational trap: switching secrets
     * or instances mid-session doesn't retroactively fix dossiers provisioned before the switch.
     */
    private fun ensurePluginConfiguration(
        traineeIdentity: String,
        pluginConfigurationId: com.ritense.plugin.domain.PluginConfigurationId,
    ) {
        val alreadyExists = runCatching { pluginService.getPluginConfiguration(pluginConfigurationId) }.isSuccess
        if (alreadyExists) return

        val tenant = epistolaTenantProvisioner.ensureTenant(traineeIdentity)
        pluginService.createPluginConfiguration(
            pluginConfigurationId,
            "Epistola — $traineeIdentity",
            epistolaPluginProperties(tenant),
            EPISTOLA_PLUGIN_DEFINITION_KEY,
        )
    }

    /**
     * **Was** a literal `"${epistola.base-url}"` placeholder string, on the assumption that
     * Valtimo's plugin-property injection resolves `${...}` against the Spring `Environment` the
     * way `@Value` does elsewhere in this codebase. It doesn't — `@PluginProperty` fields are
     * plain JSON-deserialized values with no placeholder-resolution step, so every trainee's
     * `EpistolaPlugin.baseUrl` was the literal, invalid string `${epistola.base-url}`, and every
     * action against their tenant failed to connect. Found via the admin page's health check
     * reporting the trainee's own configuration as unreachable ("Failed to fetch catalogs"), not
     * by reading the property-injection code. Now the actual resolved value, injected the same way
     * [com.ritense.valtimo.epistola.training.TrainingConfiguration.sharedSecretEpistolaTenantProvisioner]
     * already does.
     */
    private fun epistolaPluginProperties(tenant: EpistolaTenantCredentials): ObjectNode =
        JsonNodeFactory.instance.objectNode().apply {
            put("baseUrl", epistolaBaseUrl)
            put("apiKey", tenant.apiKey)
            put("tenantId", tenant.tenantId)
            put("templateSyncEnabled", false)
        }

    companion object {
        private const val EPISTOLA_PLUGIN_DEFINITION_KEY = "epistola"
    }
}