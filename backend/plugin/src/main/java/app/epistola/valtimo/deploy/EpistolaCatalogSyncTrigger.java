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
package app.epistola.valtimo.deploy;

import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;

/**
 * Triggers catalog synchronization on application startup.
 * <p>
 * Scans all Epistola plugin configurations where {@code templateSyncEnabled = true},
 * and for each configuration, synchronizes catalog resources from classpath
 * to the Epistola server.
 */
@Slf4j
public class EpistolaCatalogSyncTrigger {

    private final PluginService pluginService;
    private final EpistolaCatalogSyncService syncService;

    public EpistolaCatalogSyncTrigger(PluginService pluginService, EpistolaCatalogSyncService syncService) {
        this.pluginService = pluginService;
        this.syncService = syncService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.debug("Application ready — checking for Epistola catalog sync configurations");

        try {
            @SuppressWarnings("unchecked")
            List<PluginConfiguration> configurations = (List<PluginConfiguration>) pluginService.findPluginConfigurations(
                    EpistolaPlugin.class, props -> true);

            for (PluginConfiguration config : configurations) {
                try {
                    EpistolaPlugin plugin = (EpistolaPlugin) pluginService.createInstance(config);

                    if (!plugin.isTemplateSyncEnabled()) {
                        log.debug("Catalog sync disabled for plugin configuration '{}'", config.getTitle());
                        continue;
                    }

                    log.info("Starting catalog sync for plugin configuration '{}' (tenant={})",
                            config.getTitle(), plugin.getTenantId());

                    EpistolaCatalogSyncService.SyncResult result = syncService.syncCatalogs(
                            config.getId().toString(),
                            plugin.getBaseUrl(),
                            plugin.getApiKey(),
                            plugin.getTenantId(),
                            "AUTHORED"
                    );

                    if (result.totalCatalogs() == 0) {
                        log.debug("No catalogs found on classpath for '{}'", config.getTitle());
                    } else if (result.isFullySuccessful()) {
                        log.info("Catalog sync completed for '{}': {}/{} catalogs synced successfully",
                                config.getTitle(), result.successCount(), result.totalCatalogs());
                    } else {
                        log.warn("Catalog sync partially failed for '{}': {} succeeded, {} failed (out of {} total)",
                                config.getTitle(), result.successCount(), result.failCount(), result.totalCatalogs());
                    }
                } catch (Exception e) {
                    log.error("Catalog sync failed for plugin configuration '{}': {}",
                            config.getTitle(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load Epistola plugin configurations for catalog sync: {}", e.getMessage(), e);
        }
    }
}
