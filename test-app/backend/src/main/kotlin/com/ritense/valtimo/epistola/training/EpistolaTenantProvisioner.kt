// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training

/**
 * Provisions (or looks up) the Epistola tenant + API key a trainee's dossier should generate
 * documents through.
 *
 * Deliberately left as an extension point, not implemented here: Epistola's tenant model is
 * genuinely multi-tenant (unlike Valtimo), so this is an Epistola-suite concern, not a Valtimo
 * one. Two things any real implementation needs to account for:
 *  - An [app.epistola.suite]-style `ApiKey` is hard FK-bound to exactly one tenant — there is no
 *    key that can act across trainees, so this returns fresh, tenant-scoped credentials per call.
 *  - Tenant creation requires a platform-admin-authenticated caller (Epistola's `CreateTenant`
 *    checks a `PlatformRole`, which a bare API key cannot carry) — a real implementation needs an
 *    OIDC service-account/client-credentials identity mapped to a platform role, not just an
 *    Epistola API key.
 */
interface EpistolaTenantProvisioner {
    fun ensureTenant(traineeIdentity: String): EpistolaTenantCredentials
}

data class EpistolaTenantCredentials(
    val tenantId: String,
    val apiKey: String,
)

/**
 * Default bean when nothing else is configured — fails loudly and specifically instead of
 * silently provisioning a broken dossier. Supply a real [EpistolaTenantProvisioner] bean to
 * enable the training feature end-to-end.
 */
class NotConfiguredEpistolaTenantProvisioner : EpistolaTenantProvisioner {
    override fun ensureTenant(traineeIdentity: String): EpistolaTenantCredentials =
        throw IllegalStateException(
            "No EpistolaTenantProvisioner bean is configured. The training feature needs one " +
                "that creates/looks up an Epistola tenant + API key for '$traineeIdentity' " +
                "before it can provision a dossier — see EpistolaTenantProvisioner's KDoc.",
        )
}