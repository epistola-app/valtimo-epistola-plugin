package app.epistola.valtimo.service.completion;

import app.epistola.client.collect.ResultCollector;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.config.EpistolaProperties;
import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kotlin.Unit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * Replaces {@link PollingCompletionEventConsumer}: a single Spring bean that runs one
 * {@link ResultCollector} per Epistola plugin configuration, streams completed
 * generation results from {@code POST /tenants/{tenantId}/generation/collect},
 * and correlates each result back to the waiting BPMN execution.
 * <p>
 * Reactive lifecycle (mirrors today's polling consumer pattern):
 * <ol>
 *     <li>{@link PostConstruct} performs an initial reconcile — every active plugin
 *         configuration gets a collector started on a virtual thread.</li>
 *     <li>{@link Scheduled} (every {@code epistola.result-collector.reconcile-interval-ms})
 *         walks {@link PluginService#findPluginConfigurations(Class, java.util.function.Function)}
 *         again, starts collectors for new configurations, stops collectors for removed
 *         configurations, and restarts collectors whose URL/API-key/tenant changed.</li>
 *     <li>{@link PreDestroy} stops everything.</li>
 * </ol>
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
        reconcile();
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
     */
    @Scheduled(fixedDelayString = "${epistola.result-collector.reconcile-interval-ms:60000}")
    public void reconcile() {
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
