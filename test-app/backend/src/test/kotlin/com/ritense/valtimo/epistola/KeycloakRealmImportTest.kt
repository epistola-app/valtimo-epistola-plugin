/*
 * Copyright 2025 Epistola.
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
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package com.ritense.valtimo.epistola

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the realm Keycloak imports on a *fresh* database.
 *
 * Keycloak's import is `IGNORE_EXISTING`, so a realm already in the volume is never re-read. An
 * import that Keycloak would refuse therefore keeps working on every machine that already has the
 * old realm, and only breaks where the volume is new — a new developer, or CI. That is how this
 * landed: a 395-character `ROLE_DEMO` description against `KEYCLOAK_ROLE.DESCRIPTION varchar(255)`
 * aborted Keycloak's start, on a stack where nothing waited for Keycloak, and the run reported
 * "Epistola never came up" instead.
 *
 * The lengths checked here are the columns Keycloak's own schema declares `varchar(255)`. Only the
 * fields this realm actually authors are checked: plenty of other values land in `TEXT` columns,
 * and a blanket rule would fail on those for no reason.
 */
class KeycloakRealmImportTest {
    /** `KEYCLOAK_ROLE`, `CLIENT`, `KEYCLOAK_GROUP` all declare these `varchar(255)`. */
    private val maxLength = 255

    private val realm: JsonNode = ObjectMapper().readTree(realmFile())

    @Test
    fun `every name and description fits the column Keycloak stores it in`() {
        val authored =
            buildList {
                addAll(realm.path("roles").path("realm").map { "realm role" to it })
                realm.path("roles").path("client").properties().forEach { (client, roles) ->
                    addAll(roles.map { "client role of $client" to it })
                }
                addAll(realm.path("clients").map { "client" to it })
                addAll(realm.path("groups").map { "group" to it })
            }

        assertThat(authored)
            .describedAs("expected the realm to declare roles and clients to check")
            .isNotEmpty

        authored.forEach { (kind, node) ->
            val name = node.path("name").asText(node.path("clientId").asText(""))
            listOf("name", "description", "clientId").forEach { field ->
                val value = node.path(field).asText("")
                assertThat(value.length)
                    .describedAs(
                        "%s '%s': %s is %d characters, and Keycloak stores it in a varchar(%d) — " +
                            "the realm import fails and Keycloak refuses to start",
                        kind,
                        name,
                        field,
                        value.length,
                        maxLength,
                    ).isLessThanOrEqualTo(maxLength)
            }
        }
    }

    @Test
    fun `the realm still declares the roles the test-app expects`() {
        // Guards the lookup above: a renamed or moved realm file would otherwise leave the
        // length check iterating over nothing and passing.
        val roles = realm.path("roles").path("realm").map { it.path("name").asText() }

        assertThat(roles).contains("ROLE_USER", "ROLE_ADMIN", "ROLE_DEMO")
    }

    /**
     * Found by walking up rather than by a fixed relative path: Gradle runs tests from the module
     * directory and IDEs often from the repository root.
     */
    private fun realmFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "docker/keycloak/valtimo-realm.json")
            if (candidate.isFile) {
                return candidate
            }
            directory = directory.parentFile
        }
        error("docker/keycloak/valtimo-realm.json not found above ${File("").absolutePath}")
    }
}