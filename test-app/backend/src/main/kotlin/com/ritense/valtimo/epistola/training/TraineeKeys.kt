// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training

import com.ritense.plugin.domain.PluginConfigurationId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Deterministic identifiers derived from a trainee's identity, so ownership of a dossier's
 * artifacts never needs its own lookup table — it's always a recomputation, not a query.
 */
object TraineeKeys {
    /**
     * A **real** Keycloak realm role (`docker/keycloak/valtimo-realm.json`), assigned explicitly
     * to whichever users should be trainees — deliberately not synthesized in-process.
     *
     * The earlier design treated "any authenticated non-admin" as a trainee, which breaks the
     * moment this instance also serves genuine non-admin users who aren't trainees (a real,
     * mixed-use deployment, not a training-only one) — there was no third category between "admin"
     * and "trainee". Named `ROLE_DEMO`, not `ROLE_TRAINEE`, specifically so it reads as "this
     * person is here to try the demo," distinct from Valtimo's own generic `ROLE_USER`.
     *
     * **Trainees' tokens also carry [ADMIN_AUTHORITY]; the backend drops it.** Valtimo's own admin
     * Angular routes/menu are hard-gated to `ROLE_ADMIN` client-side, read from the token — with
     * `ROLE_DEMO` alone a trainee had no Admin section at all. So the identity provider grants it,
     * and [com.ritense.valtimo.epistola.training.security.TraineeAdminAuthorityStripFilter] removes
     * it server-side, so Valtimo's unconditioned `ROLE_ADMIN` PBAC grants never apply to a trainee.
     * What a trainee may do comes from `demo.permission.json` (PBAC, `ROLE_DEMO`) and the endpoints
     * [com.ritense.valtimo.epistola.training.security.TrainingHttpSecurityConfigurer] widens to
     * [TRAINEE_AUTHORITY]. Every check in this package keys off [TRAINEE_AUTHORITY] presence alone,
     * never off [ADMIN_AUTHORITY]'s absence.
     */
    const val TRAINEE_AUTHORITY = "ROLE_DEMO"
    const val ADMIN_AUTHORITY = "ROLE_ADMIN"

    /** The single, app-wide Epistola plugin configuration every demo case type is wired to. */
    val TEMPLATE_PLUGIN_CONFIGURATION_ID: PluginConfigurationId =
        PluginConfigurationId.existingId("e6525773-1863-4e92-92a1-9ed79508a819")

    private const val PLUGIN_CONFIGURATION_NAMESPACE = "epistola-training-plugin-configuration"

    /**
     * The case-/document-definition key for a trainee's cloned dossier.
     *
     * **Was** the trainee's raw identity, unmodified, on the theory that it needed to be
     * byte-for-byte what `${currentUserId}` resolves to for the PBAC field conditions in
     * `demo.permission.json` to match it directly (`ManageableUser.getId()` — see
     * `OidcAuthenticationConfiguration.currentUserFromAuthentication` for the "authentik" profile's
     * version of that resolution). That assumption did not survive contact with a real Keycloak
     * token: against the local docker-compose realm (stock `keycloak-iam` module, not the
     * "authentik" profile), the access token for `trainee1@demo` carries **no `sub` claim at all**,
     * so [TraineeIdentity.resolve] falls back to the principal name — the email address — which
     * `CaseDefinitionId.key` then rejects outright (`@`/`.` aren't valid slug characters). Confirmed
     * by actually running this against the real stack, not by reading source.
     *
     * Now a deterministic hash, like [pluginConfigurationId] — safe regardless of what shape the
     * identity happens to have. PBAC therefore can't name a trainee's dossier by key; case *data* is
     * scoped by creator instead (`createdBy == ${currentUserEmail}` in `demo.permission.json`), and
     * case-definition *configuration* by [TraineeOwnershipChecks.isOwnCaseDefinition].
     */
    fun caseDefinitionKey(traineeIdentity: String): String = "t" + sha256Hex(traineeIdentity).take(15)

    /**
     * The trainee's Epistola tenant id, as created by [com.ritense.valtimo.epistola.training.SharedSecretEpistolaTenantProvisioner]
     * and stored (via the `${epistola.base-url}`-style templated `tenantId` property) on their
     * [pluginConfigurationId]. Derived from [caseDefinitionKey] rather than independently, so the
     * two never drift apart even though nothing requires them to match. Used by
     * [com.ritense.valtimo.epistola.training.security.TraineeOwnershipChecks.isOwnEpistolaTenant] to
     * scope the admin page's per-tenant data (e.g. pending jobs) down to the caller's own tenant.
     */
    fun epistolaTenantId(traineeIdentity: String): String = "trainee-" + caseDefinitionKey(traineeIdentity)

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * Deterministic per-trainee id for their Epistola [PluginConfigurationId], so re-provisioning
     * after a partial failure resolves to the same configuration instead of leaking duplicates.
     * Unlike [caseDefinitionKey] this has no PBAC placeholder constraint — plugin-configuration
     * endpoints have no PBAC hook at all (see [TrainingHttpSecurityConfigurer]) — so it's free to
     * be a hash rather than the raw identity.
     */
    fun pluginConfigurationId(traineeIdentity: String): PluginConfigurationId =
        PluginConfigurationId.existingId(
            UUID.nameUUIDFromBytes("$PLUGIN_CONFIGURATION_NAMESPACE:$traineeIdentity".toByteArray(StandardCharsets.UTF_8)),
        )
}