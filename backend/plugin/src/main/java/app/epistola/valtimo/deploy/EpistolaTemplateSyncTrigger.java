package app.epistola.valtimo.deploy;

import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;

/**
 * Triggers template synchronization on application startup.
 * <p>
 * Scans all Epistola plugin configurations where {@code templateSyncEnabled = true},
 * and for each configuration, synchronizes template definitions from classpath
 * to the Epistola server.
 */
@Slf4j
public class EpistolaTemplateSyncTrigger {

    private final PluginService pluginService;
    private final EpistolaTemplateSyncService syncService;

    public EpistolaTemplateSyncTrigger(PluginService pluginService, EpistolaTemplateSyncService syncService) {
        this.pluginService = pluginService;
        this.syncService = syncService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Application ready — checking for Epistola template sync configurations");

        try {
            @SuppressWarnings("unchecked")
            List<PluginConfiguration> configurations = (List<PluginConfiguration>) pluginService.findPluginConfigurations(
                    EpistolaPlugin.class, props -> true);

            for (PluginConfiguration config : configurations) {
                try {
                    EpistolaPlugin plugin = (EpistolaPlugin) pluginService.createInstance(config);

                    if (!plugin.isTemplateSyncEnabled()) {
                        log.debug("Template sync disabled for plugin configuration '{}'", config.getTitle());
                        continue;
                    }

                    log.info("Starting template sync for plugin configuration '{}' (tenant={})",
                            config.getTitle(), plugin.getTenantId());

                    EpistolaTemplateSyncService.SyncResult result = syncService.syncTemplates(
                            config.getId().toString(),
                            plugin.getBaseUrl(),
                            plugin.getApiKey(),
                            plugin.getTenantId()
                    );

                    if (result.totalTemplates() == 0) {
                        log.info("No template definitions found on classpath for '{}'", config.getTitle());
                    } else if (result.isFullySuccessful()) {
                        log.info("Template sync completed for '{}': {}/{} templates synced successfully",
                                config.getTitle(), result.successCount(), result.totalTemplates());
                    } else {
                        log.warn("Template sync partially failed for '{}': {} succeeded, {} failed (out of {} total)",
                                config.getTitle(), result.successCount(), result.failCount(), result.totalTemplates());
                    }
                } catch (Exception e) {
                    log.error("Template sync failed for plugin configuration '{}': {}",
                            config.getTitle(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load Epistola plugin configurations for template sync: {}", e.getMessage(), e);
        }
    }
}
