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
package app.epistola.valtimo.service.versioncheck;

import app.epistola.valtimo.config.EpistolaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VersionCheckIdentityProviderTest {

    @Test
    void configuredValtimoInstallationIdWins() {
        EpistolaProperties properties = new EpistolaProperties();
        properties.getVersionCheck().setValtimoInstallationId("valtimo-prod");

        String id = new VersionCheckIdentityProvider(mock(JdbcTemplate.class), properties)
                .valtimoInstallationId();

        assertThat(id).isEqualTo("valtimo-prod");
    }

    @Test
    void derivesSingleHashFromFirstLiquibaseChangelogTimestamp() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
                "select min(dateexecuted) from databasechangelog",
                Timestamp.class
        )).thenReturn(Timestamp.from(Instant.parse("2026-01-02T03:04:05Z")));

        String id = new VersionCheckIdentityProvider(jdbcTemplate, new EpistolaProperties())
                .valtimoInstallationId();

        assertThat(id).hasSize(64);
        assertThat(id).matches("[0-9a-f]{64}");
    }

    @Test
    void returnsNullWhenDatabaseInitializationTimestampCannotBeResolved() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
                "select min(dateexecuted) from databasechangelog",
                Timestamp.class
        )).thenThrow(new IllegalStateException("database unavailable"));

        String id = new VersionCheckIdentityProvider(jdbcTemplate, new EpistolaProperties())
                .valtimoInstallationId();

        assertThat(id).isNull();
    }
}
