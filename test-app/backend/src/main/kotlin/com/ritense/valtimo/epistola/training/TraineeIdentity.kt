// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training

import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * The stable identity a trainee's dossier/plugin-config keys are derived from.
 *
 * Deliberately the JWT `sub` claim, not `Authentication.getName()`: both auth paths this app
 * supports build a `JwtAuthenticationToken` whose principal *name* is `email ?: preferred_username
 * ?: subject` (see `OidcAuthenticationConfiguration.toAuthenticationToken`), but
 * `ManageableUser.getId()` — what `${currentUserId}` resolves to in `trainee.permission.json` —
 * is `jwt.subject ?: username`, subject-first. Using `.name` here would silently break every PBAC
 * condition that relies on the match, and would fail outright for principals whose name isn't a
 * valid `CaseDefinitionId.key` (an email contains `@`/`.`, which Valtimo rejects — caught by
 * actually running [TraineeDossierProvisioningE2ETest] against real Testcontainers, not just from
 * reading the validation source).
 */
object TraineeIdentity {
    fun resolve(authentication: Authentication): String = (authentication as? JwtAuthenticationToken)?.token?.subject ?: authentication.name
}