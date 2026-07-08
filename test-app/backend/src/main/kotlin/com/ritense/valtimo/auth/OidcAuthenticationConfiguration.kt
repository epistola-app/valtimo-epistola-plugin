/*
 * Copyright 2015-2022 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ritense.valtimo.auth

import com.ritense.valtimo.contract.authentication.CurrentUserRepository
import com.ritense.valtimo.contract.authentication.ManageableUser
import com.ritense.valtimo.contract.authentication.NamedUser
import com.ritense.valtimo.contract.authentication.UserManagementService
import com.ritense.valtimo.contract.authentication.model.SearchByUserGroupsCriteria
import com.ritense.valtimo.contract.authentication.model.ValtimoUser
import com.ritense.valtimo.contract.authentication.model.ValtimoUserBuilder
import com.ritense.valtimo.contract.security.config.HttpSecurityConfigurer
import com.ritense.valtimo.contract.utils.SecurityUtils
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import com.ritense.valtimo.contract.authentication.model.Profile as ValtimoProfile

@Configuration
@Profile("authentik")
class OidcAuthenticationConfiguration {
    @Bean
    @Order(465)
    fun oidcOAuth2HttpSecurityConfigurer() = OidcOAuth2HttpSecurityConfigurer()

    @Bean
    @ConditionalOnMissingBean(CurrentUserRepository::class)
    fun oidcCurrentUserRepository() = OidcCurrentUserRepository()

    @Bean
    @ConditionalOnMissingBean(UserManagementService::class)
    fun oidcUserManagementService() = OidcUserManagementService()
}

class OidcOAuth2HttpSecurityConfigurer : HttpSecurityConfigurer {
    override fun configure(http: HttpSecurity) {
        http
            .oauth2ResourceServer { resourceServer ->
                resourceServer.jwt { jwt ->
                    jwt.jwtAuthenticationConverter { source -> source.toAuthenticationToken() }
                }
            }.oauth2Login {}
    }
}

class OidcCurrentUserRepository : CurrentUserRepository {
    override fun getCurrentUser(userId: String): ValtimoUser = currentUserFromAuthentication(SecurityUtils.getCurrentUserAuthentication())

    override fun changePassword(
        username: String,
        password: String,
    ): Unit = throw UnsupportedOperationException("Changing passwords is delegated to the OIDC provider")

    override fun updateProfile(
        username: String,
        profile: ValtimoProfile,
    ): Unit = throw UnsupportedOperationException("Updating profiles is delegated to the OIDC provider")

    override fun supports(authentication: Class<out Authentication>) = JwtAuthenticationToken::class.java.isAssignableFrom(authentication)
}

@Suppress("OVERRIDE_DEPRECATION")
class OidcUserManagementService : UserManagementService {
    override fun createUser(user: ManageableUser): ManageableUser = unsupportedUserManagement()

    override fun updateUser(user: ManageableUser): ManageableUser = unsupportedUserManagement()

    override fun deleteUser(userId: String) = unsupportedUserManagement<Unit>()

    override fun resendVerificationEmail(userId: String): Boolean = unsupportedUserManagement()

    override fun activateUser(userId: String) = unsupportedUserManagement<Unit>()

    override fun deactivateUser(userId: String) = unsupportedUserManagement<Unit>()

    override fun getAllUsers(pageable: Pageable): Page<ManageableUser> = PageImpl(emptyList(), pageable, 0)

    override fun getAllUsers(): MutableList<ManageableUser> = mutableListOf()

    override fun queryUsers(
        searchTerm: String,
        pageable: Pageable,
    ): Page<ManageableUser> = PageImpl(emptyList(), pageable, 0)

    override fun findByEmail(email: String) = java.util.Optional.empty<ManageableUser>()

    override fun findById(id: String): ManageableUser = unsupportedUserManagement()

    override fun findByRole(role: String): MutableList<ManageableUser> = mutableListOf()

    override fun findByRoles(criteria: SearchByUserGroupsCriteria): MutableList<ManageableUser> = mutableListOf()

    override fun findNamedUserByRolesWithoutAuthorization(roles: MutableSet<String>): MutableList<NamedUser> = mutableListOf()

    override fun getCurrentUser(): ManageableUser {
        val authentication = SecurityUtils.getCurrentUserAuthentication()
        if (authentication == null || authentication is AnonymousAuthenticationToken) {
            return systemUser()
        }

        return currentUserFromAuthentication(authentication)
    }

    override fun getCurrentUserId(): String = (SecurityUtils.getCurrentUserAuthentication()?.name ?: "system")

    override fun getCurrentUserTeams(): MutableList<String> = mutableListOf()

    private fun <T> unsupportedUserManagement(): T =
        throw UnsupportedOperationException("User management is delegated to Authentik in OIDC mode")
}

private fun Jwt.toAuthenticationToken(): AbstractAuthenticationToken {
    val authorities =
        extractRoles(claims)
            .map { SimpleGrantedAuthority(it) }
    val principal =
        getClaimAsString("email")
            ?: getClaimAsString("preferred_username")
            ?: subject

    return JwtAuthenticationToken(this, authorities, principal)
}

private fun currentUserFromAuthentication(authentication: Authentication?): ValtimoUser {
    val jwt = (authentication as? JwtAuthenticationToken)?.token
    val username =
        jwt?.getClaimAsString("preferred_username")
            ?: jwt?.getClaimAsString("email")
            ?: authentication?.name
            ?: "system"
    val email = jwt?.getClaimAsString("email") ?: username

    return ValtimoUserBuilder()
        .id(jwt?.subject ?: username)
        .username(username)
        .email(email)
        .firstName(jwt?.getClaimAsString("given_name") ?: "")
        .lastName(jwt?.getClaimAsString("family_name") ?: "")
        .roles(SecurityUtils.getCurrentUserRoles())
        .activated(true)
        .isEmailVerified(jwt?.getClaimAsBoolean("email_verified") ?: false)
        .build()
}

private fun systemUser(): ValtimoUser =
    ValtimoUserBuilder()
        .id("system")
        .username("system")
        .email("system")
        .firstName("System")
        .lastName("")
        .roles(emptyList())
        .activated(true)
        .build()

@Suppress("UNCHECKED_CAST")
private fun extractRoles(claims: Map<String, Any>): List<String> {
    val realmAccess = claims["realm_access"] as? Map<String, Any>
    val realmRoles = realmAccess?.get("roles") as? Collection<String> ?: emptyList()
    val resourceAccess = claims["resource_access"] as? Map<String, Any> ?: emptyMap()
    val clientRoles =
        resourceAccess.values
            .mapNotNull { it as? Map<String, Any> }
            .flatMap { it["roles"] as? Collection<String> ?: emptyList() }

    return (realmRoles + clientRoles).distinct()
}