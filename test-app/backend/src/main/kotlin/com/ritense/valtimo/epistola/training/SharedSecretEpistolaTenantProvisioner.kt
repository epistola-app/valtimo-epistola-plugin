// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training

import app.epistola.client.EpistolaClient
import app.epistola.client.api.TenantsApi
import app.epistola.client.model.CreateTenantRequest
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClientResponseException

/**
 * Uses epistola-suite's demo-profile shared secret to idempotently create a personal Epistola
 * tenant per trainee and hand back that same secret as the plugin's API key — no per-trainee
 * Epistola API key is minted.
 *
 * epistola-suite's `demo` profile can be configured with `epistola.demo.shared-secret`
 * (`DemoSharedSecretAuthenticationFilter`): a single bearer credential that authenticates as an
 * all-tenant superuser (`globalRoles` = every `TenantRole`, `platformRoles` = every
 * `PlatformRole`), including tenants that do not exist yet. That is exactly the credential
 * `DemoLoginMembershipResolver` documents as the reason a per-tenant API key is unnecessary in
 * epistola-suite's own demo flow ("visitors get a tenant each ... there is no per-tenant API key
 * to mint and track ahead of time. One credential that works everywhere is the point.") — this
 * class is the same idea from the Valtimo side: create the tenant with the shared secret (which
 * carries `PlatformRole.TENANT_MANAGER`, unlike an ordinary Epistola API key — see
 * `EpistolaTenantProvisioner`'s KDoc for why a normal API key can't do this), then use the same
 * secret as the trainee's `PluginConfiguration` API key going forward.
 *
 * **This credential has unlimited authority over every tenant on the target Epistola instance.**
 * Only point this at an epistola-suite instance running the `demo` profile with the shared secret
 * configured — never at a production instance. epistola-suite itself refuses to boot with this
 * secret configured outside the `demo` profile (`DemoSharedSecretSafetyValidator`), which is the
 * other half of this being safe to use here.
 */
class SharedSecretEpistolaTenantProvisioner(
    private val baseUrl: String,
    private val sharedSecret: String,
) : EpistolaTenantProvisioner {
    private val tenantsApi: TenantsApi by lazy {
        TenantsApi(EpistolaClient.builder(baseUrl, sharedSecret).build())
    }

    override fun ensureTenant(traineeIdentity: String): EpistolaTenantCredentials {
        val tenantId = tenantIdFor(traineeIdentity)
        try {
            tenantsApi.createTenant(CreateTenantRequest(id = tenantId, name = "Trainee $traineeIdentity"))
        } catch (e: RestClientResponseException) {
            // Two requests from the same trainee racing to provision — the loser's tenant already
            // exists, which is exactly what it wanted. Anything else is a real failure.
            if (e.statusCode != HttpStatus.CONFLICT) throw e
        }
        return EpistolaTenantCredentials(tenantId = tenantId, apiKey = sharedSecret)
    }

    /** Epistola tenant ids follow the same slug rules Valtimo's own `tenantId` plugin property does. */
    private fun tenantIdFor(traineeIdentity: String): String = "trainee-$traineeIdentity".take(MAX_TENANT_ID_LENGTH)

    companion object {
        private const val MAX_TENANT_ID_LENGTH = 63
    }
}