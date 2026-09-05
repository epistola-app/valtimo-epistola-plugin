// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training

import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * The stable identity a trainee's dossier/plugin-config keys are derived from.
 *
 * Prefers the JWT `sub` claim over `Authentication.getName()`, falling back to `.name` when
 * `sub` is absent — confirmed to happen for real against this repo's own docker-compose Keycloak
 * realm, not a defensive guess (see [TraineeKeys.caseDefinitionKey]'s KDoc). All that's actually
 * required of the returned value is that it's *stable* across logins for the same person, since
 * [TraineeKeys.caseDefinitionKey] hashes it rather than using it verbatim — `subject` is preferred
 * simply because it's the more canonical, rename-proof identifier when present (an email can
 * change; a subject claim generally doesn't), not because anything downstream still needs it to
 * match byte-for-byte against some other value.
 *
 * **This class used to matter for a different, now-abandoned reason**: an earlier design needed
 * this value to match `${currentUserId}` verbatim, so PBAC conditions in `demo.permission.json`
 * could compare a resource's key against it directly. That assumption didn't survive contact with
 * a real Keycloak token — `sub` was missing entirely — so [TraineeKeys.caseDefinitionKey] now
 * always hashes instead of matching verbatim, and those PBAC conditions are a documented,
 * currently-non-functional gap (see that KDoc, and [TraineeOwnershipInterceptor]'s KDoc for the
 * interceptor-based data-plane scoping that replaced relying on them).
 */
object TraineeIdentity {
    fun resolve(authentication: Authentication): String = (authentication as? JwtAuthenticationToken)?.token?.subject ?: authentication.name
}