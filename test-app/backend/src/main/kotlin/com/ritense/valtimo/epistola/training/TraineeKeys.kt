// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training

import com.ritense.plugin.domain.PluginConfigurationId
import java.nio.charset.StandardCharsets
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
     */
    const val TRAINEE_AUTHORITY = "ROLE_DEMO"
    const val ADMIN_AUTHORITY = "ROLE_ADMIN"

    /** The single, app-wide Epistola plugin configuration every demo case type is wired to. */
    val TEMPLATE_PLUGIN_CONFIGURATION_ID: PluginConfigurationId =
        PluginConfigurationId.existingId("e6525773-1863-4e92-92a1-9ed79508a819")

    private const val PLUGIN_CONFIGURATION_NAMESPACE = "epistola-training-plugin-configuration"

    /**
     * The case-/document-definition key for a trainee's cloned dossier — deliberately the
     * trainee's raw identity, unmodified.
     *
     * Valtimo's PBAC field conditions can only compare against a fixed placeholder such as
     * `${currentUserId}` (see `trainee.permission.json`), never a derived value, so this key has
     * to be byte-for-byte what that placeholder resolves to (`ManageableUser.getId()`, which in
     * every OIDC-based auth path in this app is the JWT `sub` claim — see
     * `OidcAuthenticationConfiguration.currentUserFromAuthentication`). Any transformation here
     * (hashing, prefixing) would silently break every PBAC condition that relies on it.
     *
     * Not verified against a live boot: that `${currentUserId}` really does resolve to the same
     * value as [traineeIdentity] for whichever auth profile is active. Confirm this before
     * relying on the PBAC grants in `trainee.permission.json`.
     */
    fun caseDefinitionKey(traineeIdentity: String): String = traineeIdentity

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