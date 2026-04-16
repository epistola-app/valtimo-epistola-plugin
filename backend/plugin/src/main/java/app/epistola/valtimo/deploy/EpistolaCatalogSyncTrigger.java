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
        log.info("Application ready — checking for Epistola catalog sync configurations");

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
                            "full"
                    );

                    if (result.totalCatalogs() == 0) {
                        log.info("No catalogs found on classpath for '{}'", config.getTitle());
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
