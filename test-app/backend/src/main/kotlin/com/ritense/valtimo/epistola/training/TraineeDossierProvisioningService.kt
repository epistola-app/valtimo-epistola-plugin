// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training

import com.ritense.authorization.AuthorizationContext
import com.ritense.case_.repository.CaseDefinitionRepository
import com.ritense.valtimo.contract.case_.CaseDefinitionId
import java.util.concurrent.Callable

/**
 * Public entry point for "make sure this trainee has a dossier" — cheap on every call after the
 * first: a single indexed existence check. [TraineeDossierProvisioner] does the actual, one-time,
 * privileged provisioning work.
 */
class TraineeDossierProvisioningService(
    private val caseDefinitionRepository: CaseDefinitionRepository,
    private val provisioner: TraineeDossierProvisioner,
    private val properties: TrainingProperties,
) {
    fun ensureDossier(traineeIdentity: String): CaseDefinitionId {
        val key = TraineeKeys.caseDefinitionKey(traineeIdentity)
        if (caseDefinitionRepository.existsByIdKey(key)) {
            return CaseDefinitionId(key, properties.templateCaseDefinitionVersionTag)
        }

        // Case documents/process links/plugin configs aren't covered by ordinary PBAC — this
        // mirrors how Valtimo's own background/system operations (e.g.
        // ProcessDocumentDeletedEventListener) provision or clean up state with no acting user.
        return AuthorizationContext.runWithoutAuthorization(Callable { provisioner.provision(traineeIdentity) })
    }
}