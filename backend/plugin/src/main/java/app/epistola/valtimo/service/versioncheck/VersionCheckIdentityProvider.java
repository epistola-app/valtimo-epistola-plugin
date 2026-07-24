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
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Collectors;

/**
 * Resolves a stable Valtimo-host identity for release checks.
 *
 * <p>Valtimo 13 does not expose a public installation-id service. To avoid
 * sending tenant/server information, the default identity is a one-way hash of
 * the sorted Epistola plugin configuration UUIDs stored in this Valtimo
 * database. Operators that already have a stable deployment id can configure it
 * explicitly with {@code epistola.version-check.valtimo-installation-id}.
 */
@Slf4j
public class VersionCheckIdentityProvider {

    private final PluginService pluginService;
    private final EpistolaProperties properties;

    public VersionCheckIdentityProvider(PluginService pluginService, EpistolaProperties properties) {
        this.pluginService = pluginService;
        this.properties = properties;
    }

    public String valtimoInstallationId() {
        String configured = properties.getVersionCheck().getValtimoInstallationId();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        try {
            String joinedIds = pluginService.findPluginConfigurations(EpistolaPlugin.class, props -> true)
                    .stream()
                    .map(PluginConfiguration::getId)
                    .map(Object::toString)
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.joining(","));
            if (joinedIds.isBlank()) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joinedIds.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.debug("Could not derive Valtimo installation id for version check: {}", e.getMessage());
            return null;
        }
    }
}
