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
 * first: a single indexed lookup of the trainee's *active* case definition. [TraineeDossierProvisioner]
 * does the actual, one-time, privileged provisioning work.
 *
 * The fast path checks "active", not just "exists": Valtimo's case list only shows active case
 * definitions, and dossiers provisioned before [TraineeDossierProvisioner] activated them exist
 * but are inactive — invisible to their trainee, who then only sees the shared template (and gets
 * a 403 opening it). Falling through to the provisioner repairs those.
 */
class TraineeDossierProvisioningService(
    private val caseDefinitionRepository: CaseDefinitionRepository,
    private val provisioner: TraineeDossierProvisioner,
) {
    fun ensureDossier(traineeIdentity: String): CaseDefinitionId {
        val key = TraineeKeys.caseDefinitionKey(traineeIdentity)
        caseDefinitionRepository.findByActiveIsTrueAndIdKey(key)?.let { return it.id }

        // Case documents/process links/plugin configs aren't covered by ordinary PBAC — this
        // mirrors how Valtimo's own background/system operations (e.g.
        // ProcessDocumentDeletedEventListener) provision or clean up state with no acting user.
        return AuthorizationContext.runWithoutAuthorization(Callable { provisioner.provision(traineeIdentity) })
    }
}