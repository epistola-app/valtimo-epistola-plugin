// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training

import com.ritense.case.service.CaseDefinitionService
import com.ritense.case_.repository.CaseDefinitionRepository
import com.ritense.exporter.ExportService
import com.ritense.importer.ImportService
import com.ritense.plugin.service.PluginService
import com.ritense.processlink.service.ProcessLinkService
import com.ritense.valtimo.epistola.training.security.ProcessDefinitionOwnershipResolver
import com.ritense.valtimo.epistola.training.security.TraineeOwnershipChecks
import com.ritense.valtimo.epistola.training.security.TraineeOwnershipInterceptor
import com.ritense.valtimo.epistola.training.security.TraineeProvisioningFilter
import com.ritense.valtimo.epistola.training.security.TrainingHttpSecurityConfigurer
import com.ritense.valtimo.epistola.training.security.TrainingWebConfig
import org.operaton.bpm.engine.RepositoryService
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

/**
 * Wires up the interactive-training feature: a personal "dossier" (document-definition + BPMN
 * process + process-links + Epistola plugin configuration) auto-cloned per trainee at login, with
 * a scoped-admin authorization layer so trainees can configure process-links/plugin actions on
 * their own dossier without reaching anyone else's.
 *
 * Everything the feature adds lives under this package and is wired here — gated behind the
 * `training` Spring profile so it's fully opt-in. Omit the profile (the default) and none of these
 * beans are created; Valtimo's own admin-gated endpoints stay exactly as shipped.
 *
 * To enable this feature for real, also supply an [EpistolaTenantProvisioner] bean — see its KDoc.
 */
@Configuration
@Profile("training")
@EnableConfigurationProperties(TrainingProperties::class)
class TrainingConfiguration {
    @Bean
    @ConditionalOnMissingBean(EpistolaTenantProvisioner::class)
    fun notConfiguredEpistolaTenantProvisioner(): EpistolaTenantProvisioner = NotConfiguredEpistolaTenantProvisioner()

    @Bean
    fun processDefinitionOwnershipResolver(repositoryService: RepositoryService) = ProcessDefinitionOwnershipResolver(repositoryService)

    @Bean
    fun traineeOwnershipChecks(
        processDefinitionOwnershipResolver: ProcessDefinitionOwnershipResolver,
        processLinkService: ProcessLinkService,
        properties: TrainingProperties,
    ) = TraineeOwnershipChecks(processDefinitionOwnershipResolver, processLinkService, properties)

    @Bean
    fun traineeDossierProvisioner(
        exportService: ExportService,
        importService: ImportService,
        caseDefinitionRepository: CaseDefinitionRepository,
        caseDefinitionService: CaseDefinitionService,
        pluginService: PluginService,
        epistolaTenantProvisioner: EpistolaTenantProvisioner,
        properties: TrainingProperties,
    ) = TraineeDossierProvisioner(
        exportService,
        importService,
        caseDefinitionRepository,
        caseDefinitionService,
        pluginService,
        epistolaTenantProvisioner,
        properties,
    )

    @Bean
    fun traineeDossierProvisioningService(
        caseDefinitionRepository: CaseDefinitionRepository,
        provisioner: TraineeDossierProvisioner,
        properties: TrainingProperties,
    ) = TraineeDossierProvisioningService(caseDefinitionRepository, provisioner, properties)

    @Bean
    fun traineeProvisioningFilter(provisioningService: TraineeDossierProvisioningService) = TraineeProvisioningFilter(provisioningService)

    // Very low @Order so this bean's authorizeHttpRequests rules are registered ahead of
    // Valtimo's own module HttpSecurityConfigurers for the same paths (first-match-wins) — see
    // TrainingHttpSecurityConfigurer's KDoc for why this needs verifying against a live boot.
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    fun trainingHttpSecurityConfigurer(traineeProvisioningFilter: TraineeProvisioningFilter) =
        TrainingHttpSecurityConfigurer(traineeProvisioningFilter)

    @Bean
    fun traineeOwnershipInterceptor(ownershipChecks: TraineeOwnershipChecks) = TraineeOwnershipInterceptor(ownershipChecks)

    @Bean
    fun trainingWebConfig(traineeOwnershipInterceptor: TraineeOwnershipInterceptor) = TrainingWebConfig(traineeOwnershipInterceptor)

    // TraineeOwnershipRequestBodyAdvice / TraineeOwnershipResponseBodyAdvice are NOT wired here:
    // @ControllerAdvice is itself meta-annotated @Component, so they're already found by
    // component-scan — each carries its own @Profile("training") directly (see their KDoc for why
    // that redundancy is required, not optional) rather than being registered a second time here.
}