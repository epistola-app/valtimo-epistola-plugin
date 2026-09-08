// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `epistola.training.*` — only read when the `training` Spring profile is active (see
 * [TrainingConfiguration]).
 */
@ConfigurationProperties(prefix = "epistola.training")
data class TrainingProperties(
    /** Case-/document-definition key of the case cloned into every trainee's dossier. */
    val templateCaseDefinitionKey: String = "form-flow-demo",
    val templateCaseDefinitionVersionTag: String = "1.0.0",
    /**
     * epistola-suite's demo-profile all-tenant-superuser credential (`epistola.demo.shared-secret`
     * on the Epistola side) — see [SharedSecretEpistolaTenantProvisioner]'s KDoc. Blank (the
     * default) means that provisioner isn't wired in and [EpistolaTenantProvisioner] falls back to
     * [NotConfiguredEpistolaTenantProvisioner].
     */
    val epistolaSharedSecret: String = "",
    /**
     * A static bearer credential something outside the running app (a monitoring tool, an
     * instructor dashboard) presents to check on trainee progress across the whole API, without a
     * human login — see
     * [com.ritense.valtimo.epistola.training.security.TrainingFacilitySharedSecretAuthenticationFilter]'s
     * KDoc. Blank (the default) means that filter isn't wired in at all — this direction of access
     * doesn't exist unless explicitly configured.
     */
    val facilitySharedSecret: String = "",
)