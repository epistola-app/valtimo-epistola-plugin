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
import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.domain.PluginConfigurationId;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VersionCheckIdentityProviderTest {

    @Test
    void configuredValtimoInstallationIdWins() {
        EpistolaProperties properties = new EpistolaProperties();
        properties.getVersionCheck().setValtimoInstallationId("valtimo-prod");

        String id = new VersionCheckIdentityProvider(mock(PluginService.class), properties)
                .valtimoInstallationId();

        assertThat(id).isEqualTo("valtimo-prod");
    }

    @Test
    void derivesSingleHashFromSortedPluginConfigurationIds() {
        PluginService pluginService = mock(PluginService.class);
        PluginConfiguration configB = mockConfiguration("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        PluginConfiguration configA = mockConfiguration("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                .thenReturn(List.of(configB, configA));

        String id = new VersionCheckIdentityProvider(pluginService, new EpistolaProperties())
                .valtimoInstallationId();

        assertThat(id).hasSize(64);
        assertThat(id).matches("[0-9a-f]{64}");
    }

    private static PluginConfiguration mockConfiguration(String uuid) {
        PluginConfiguration config = mock(PluginConfiguration.class);
        when(config.getId()).thenReturn(new PluginConfigurationId(UUID.fromString(uuid)));
        return config;
    }
}
