package app.epistola.valtimo.service.completion;

import app.epistola.client.collect.ResultCollector;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.config.EpistolaProperties;
import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.events.PluginConfigurationDeletedEvent;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.contract.event.PluginsDeployedEvent;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kotlin.Unit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A single Spring bean that runs one {@link ResultCollector} per Epistola plugin
 * configuration, streams completed generation results from
 * {@code POST /tenants/{tenantId}/generation/collect}, and correlates each result
 * back to the waiting BPMN execution.
 * <p>
 * Reconciliation has three triggers:
 * <ol>
 *     <li>{@link PostConstruct} — initial reconcile on bean startup.</li>
 *     <li>{@link Scheduled} — every {@code epistola.result-collector.reconcile-interval-ms}
 *         (60s default), as a safety net for missed events.</li>
 *     <li>{@link EventListener}s on {@link PluginsDeployedEvent} (fired by Valtimo
 *         after every plugin create/update) and {@link PluginConfigurationDeletedEvent}
 *         (fired after delete) — lets us react to UI-driven config changes without
 *         waiting for the scheduled tick.</li>
 * </ol>
 * Each tick walks {@link PluginService#findPluginConfigurations(Class, java.util.function.Function)},
 * starts collectors for new configurations, stops collectors for removed ones, and
 * restarts collectors whose URL/API-key/tenant changed. {@link PreDestroy} stops
 * everything.
 * <p>
 * Each result is delivered to {@link EpistolaMessageCorrelationService#correlateCompletion}
 * with the same {@code epistola:job:{tenantId}/{requestId}} job path encoding the
 * plugin already uses, so the existing BPMN message correlation continues to work
 * without changes to deployed processes.
 */
@Slf4j
@RequiredArgsConstructor
public class EpistolaResultCollectorRunner {

    private final PluginService pluginService;
    private final EpistolaApiClientFactory apiClientFactory;
    private final EpistolaMessageCorrelationService correlationService;
    private final EpistolaProperties properties;

    private final Map<String, ManagedCollector> collectors = new ConcurrentHashMap<>();

    @PostConstruct
    public void start() {
        if (!properties.getResultCollector().isEnabled()) {
            log.info("Epistola result collector disabled (epistola.result-collector.enabled=false)");
            return;
        }
        // Initial reconcile is intentionally NOT called here. At @PostConstruct time
        // the Epistola plugin bean (epistolaPluginFactory) is still being created in
        // the same context-refresh pass, so pluginService.createInstance(cfg) throws
        // BeanCurrentlyInCreationException for every config. The first reconcile is
        // covered by two later triggers — the PluginsDeployedEvent that Valtimo emits
        // once plugins are deployed, and the @Scheduled tick which fires immediately
        // after the scheduler is up. Either rescue is fast enough for a clean boot.
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping all Epistola result collectors");
        collectors.values().forEach(ManagedCollector::stop);
        collectors.clear();
    }

    /**
     * Periodically reconcile the running collectors with the current set of plugin
     * configurations. Picks up newly-added configurations and tears down ones that
     * have been removed or had their connection details changed.
     * <p>
     * {@code synchronized} because the {@link Scheduled} tick and the
     * {@link EventListener}-driven calls below can run on different threads.
     * The body is fast enough (one DB query + a small map walk) that serializing
     * is cheaper than reasoning about partial overlaps.
     */
    @Scheduled(fixedDelayString = "${epistola.result-collector.reconcile-interval-ms:60000}")
    public synchronized void reconcile() {
        if (!properties.getResultCollector().isEnabled()) {
            return;
        }

        Map<String, EpistolaPlugin> active = loadActivePlugins();
        Set<String> toStop = new HashSet<>(collectors.keySet());
        toStop.removeAll(active.keySet());
        toStop.forEach(this::stopCollector);

        active.forEach((id, plugin) -> {
            ManagedCollector existing = collectors.get(id);
            if (existing == null) {
                startCollector(id, plugin);
            } else if (existing.signatureChanged(plugin)) {
                log.info("Plugin configuration {} changed (URL/key/tenant), restarting collector", id);
                stopCollector(id);
                startCollector(id, plugin);
            }
        });
    }

    /**
     * Reconcile immediately when Valtimo signals that any plugin configuration was
     * created or updated. The event is global to all plugin types, but {@link #reconcile()}
     * filters to {@link EpistolaPlugin}, so unrelated plugin changes are a cheap no-op
     * (one DB query, no map mutations).
     */
    @EventListener
    public void onPluginsDeployed(PluginsDeployedEvent event) {
        log.debug("PluginsDeployedEvent received; reconciling Epistola collectors");
        reconcile();
    }

    /**
     * Reconcile immediately when Valtimo signals a plugin configuration delete.
     * Same global-to-all-plugins caveat as {@link #onPluginsDeployed} — but a
     * deleted Epistola config will be missing from the next {@code findPluginConfigurations}
     * call, so its collector gets stopped right away instead of waiting for the
     * scheduled tick.
     */
    @EventListener
    public void onPluginConfigurationDeleted(PluginConfigurationDeletedEvent event) {
        log.debug("PluginConfigurationDeletedEvent received; reconciling Epistola collectors");
        reconcile();
    }

    /**
     * Compute a routing key that is guaranteed to land on a partition assigned to this node's
     * collector for the given Epistola connection. Returns {@code null} if no collector is
     * running yet, or if the first poll has not yet populated a partition assignment — callers
     * should treat this as "let the server pick" and submit without a routing key.
     */
    public String routingKeyFor(String baseUrl, String apiKey, String tenantId, String key) {
        for (ManagedCollector managed : collectors.values()) {
            if (managed.matches(baseUrl, apiKey, tenantId)) {
                return managed.collector.routingKeyToMe(key);
            }
        }
        return null;
    }

    /**
     * Hint that a result is expected soon on the collector matching this
     * connection — typically called right after a successful submit. If the
     * collector has backed off into idle mode (poll interval larger than
     * `kickIntervalMs`), the next poll happens within `kickIntervalMs` instead
     * of waiting out the full backoff.
     * <p>
     * No-op if no collector is running for this connection (e.g. the
     * configuration was deleted but a still-in-flight action raced the delete),
     * or if the collector is already polling fast enough.
     * <p>
     * Safe to call from any thread, any number of times — extra calls collapse
     * harmlessly inside the contract collector's wake mechanism.
     */
    public void kickFor(String baseUrl, String apiKey, String tenantId) {
        for (ManagedCollector managed : collectors.values()) {
            if (managed.matches(baseUrl, apiKey, tenantId)) {
                managed.collector.kick();
                return;
            }
        }
    }

    private Map<String, EpistolaPlugin> loadActivePlugins() {
        Map<String, EpistolaPlugin> result = new HashMap<>();
        try {
            List<PluginConfiguration> configurations = pluginService
                    .findPluginConfigurations(EpistolaPlugin.class, props -> true);
            for (PluginConfiguration cfg : configurations) {
                try {
                    EpistolaPlugin plugin = (EpistolaPlugin) pluginService.createInstance(cfg);
                    String id = cfg.getId().getId().toString();
                    result.put(id, plugin);
                } catch (Exception e) {
                    log.warn("Failed to instantiate Epistola plugin for configuration {}: {}",
                            safeId(cfg), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to load Epistola plugin configurations: {}", e.getMessage());
        }
        return result;
    }

    private void startCollector(String configurationId, EpistolaPlugin plugin) {
        EpistolaProperties.ResultCollector cfg = properties.getResultCollector();

        ResultCollector collector = ResultCollector.Companion.builder()
                .restClient(apiClientFactory.createRestClient(plugin.getBaseUrl(), plugin.getApiKey()))
                .tenantId(plugin.getTenantId())
                .batchSize(cfg.getBatchSize())
                .minInterval(Duration.ofMillis(cfg.getMinIntervalMs()))
                .maxInterval(Duration.ofMillis(cfg.getMaxIntervalMs()))
                .kickInterval(Duration.ofMillis(cfg.getKickIntervalMs()))
                .backoffMultiplier(cfg.getBackoffMultiplier())
                .registerShutdownHook(false)
                .handler(result -> {
                    handleResult(plugin.getTenantId(), result);
                    return Unit.INSTANCE;
                })
                .errorHandler(err -> {
                    log.warn("Collector error for tenantId={}: {}", plugin.getTenantId(), err.getMessage());
                    return Unit.INSTANCE;
                })
                .build();

        Thread thread = Thread.ofVirtual()
                .name("epistola-collector-" + plugin.getTenantId() + "-" + shortId(configurationId))
                .start(collector::start);

        ManagedCollector managed = new ManagedCollector(
                collector, thread, plugin.getBaseUrl(), plugin.getApiKey(), plugin.getTenantId());
        collectors.put(configurationId, managed);

        log.info("Started result collector for plugin configuration {} (tenantId={}, baseUrl={})",
                configurationId, plugin.getTenantId(), plugin.getBaseUrl());
    }

    private void stopCollector(String configurationId) {
        ManagedCollector managed = collectors.remove(configurationId);
        if (managed != null) {
            managed.stop();
            log.info("Stopped result collector for plugin configuration {}", configurationId);
        }
    }

    void handleResult(String tenantId, ResultCollector.GenerationResult result) {
        try {
            int correlated = correlationService.correlateCompletion(
                    tenantId,
                    result.getRequestId(),
                    result.getStatus(),
                    result.getDocumentId(),
                    result.getError()
            );
            if (correlated == 0) {
                log.debug("No waiting execution for tenantId={}, requestId={} (status={}); acking anyway",
                        tenantId, result.getRequestId(), result.getStatus());
            }
        } catch (Exception e) {
            // Swallow — re-throwing would block this sequence in the collector and
            // re-deliver the same result on every poll. The result has already been
            // produced server-side; redelivery cannot fix a missing waiting execution.
            log.warn("Failed to correlate result for tenantId={}, requestId={}: {}",
                    tenantId, result.getRequestId(), e.getMessage());
        }
    }

    private static String safeId(PluginConfiguration cfg) {
        try {
            return cfg.getId().getId().toString();
        } catch (Exception e) {
            return "<unknown>";
        }
    }

    private static String shortId(String id) {
        try {
            return UUID.fromString(id).toString().substring(0, 8);
        } catch (IllegalArgumentException e) {
            return id.length() > 8 ? id.substring(0, 8) : id;
        }
    }

    /** A running collector + the configuration signature it was started with. */
    private static final class ManagedCollector {
        final ResultCollector collector;
        final Thread thread;
        final String baseUrl;
        final String apiKey;
        final String tenantId;

        ManagedCollector(ResultCollector collector, Thread thread,
                         String baseUrl, String apiKey, String tenantId) {
            this.collector = collector;
            this.thread = thread;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.tenantId = tenantId;
        }

        boolean signatureChanged(EpistolaPlugin plugin) {
            return !baseUrl.equals(plugin.getBaseUrl())
                    || !apiKey.equals(plugin.getApiKey())
                    || !tenantId.equals(plugin.getTenantId());
        }

        boolean matches(String baseUrl, String apiKey, String tenantId) {
            return this.baseUrl.equals(baseUrl)
                    && this.apiKey.equals(apiKey)
                    && this.tenantId.equals(tenantId);
        }

        void stop() {
            collector.stop();
            thread.interrupt();
        }
    }
}
