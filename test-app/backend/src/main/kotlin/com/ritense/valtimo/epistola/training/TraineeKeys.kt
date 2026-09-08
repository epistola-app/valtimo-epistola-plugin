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
     * **Trainees are also granted real [ADMIN_AUTHORITY].** Valtimo's own admin Angular
     * routes/menu (`test-app/frontend/src/environments/environment.ts`'s "Admin" menu group, and
     * the `@valtimo/case-management`/`@valtimo/plugin-management` route guards behind it) are
     * hard-gated to `ROLE_ADMIN` client-side — confirmed by driving a real login as `trainee1@demo`
     * in a headless browser: with `ROLE_DEMO` alone, the side-nav had no Admin section at all, and
     * navigating straight to `/case-management` or `/plugins` bounced back before ever reaching the
     * backend. There is no finer-grained frontend role model to widen instead, so short of building
     * a bespoke trainee-only UI, `ROLE_ADMIN` has to be granted for real. The backend compensates:
     * every operation a trainee must not be allowed now has to be blocked explicitly rather than
     * simply never being reachable — see [com.ritense.valtimo.epistola.training.security.TraineeAdminSurfaceGuardFilter]
     * for the hard-block list, and [com.ritense.valtimo.epistola.training.security.TraineeOwnershipChecks]
     * for the per-resource scoping that still applies on top of that. Every check in this package
     * keys off [TRAINEE_AUTHORITY] presence alone, never off [ADMIN_AUTHORITY]'s absence.
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
     * identity happens to have. The cost: the `${currentUserId}`-based PBAC conditions on
     * `JsonSchemaDocument`/`OperatonTask` in `demo.permission.json` no longer match anything (the
     * stored key is a hash, not the raw placeholder value), so trainee-vs-trainee scoping of case
     * *data* (as opposed to case-definition *configuration*, which goes through
     * [TraineeOwnershipChecks.isOwnCaseDefinition] and never relied on this) is currently
     * unenforced. Needs the same interceptor-based treatment the case-definition management
     * surface already has, not a placeholder-matching fix.
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