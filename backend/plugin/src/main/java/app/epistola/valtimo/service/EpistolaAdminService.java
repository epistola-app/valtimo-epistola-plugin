package app.epistola.valtimo.service;

import app.epistola.valtimo.web.rest.dto.ConnectionStatus;
import app.epistola.valtimo.web.rest.dto.PluginUsageEntry;
import app.epistola.valtimo.web.rest.dto.ProcessLinkExport;
import app.epistola.valtimo.web.rest.dto.VersionInfo;
import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.domain.PluginConfigurationId;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.plugin.service.PluginService;
import com.ritense.processdocument.domain.ProcessDefinitionCaseDefinition;
import com.ritense.processdocument.domain.ProcessDefinitionId;
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService;
import com.ritense.processlink.service.ProcessLinkService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.FlowElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Service providing administrative information about the Epistola plugin:
 * connection health, version info, and usage overview across process definitions.
 */
@Slf4j
@RequiredArgsConstructor
public class EpistolaAdminService {

    private static final Set<String> EPISTOLA_ACTION_KEYS = Set.of(
            "generate-document", "check-job-status", "download-document"
    );

    private final PluginService pluginService;
    private final EpistolaService epistolaService;
    private final ProcessLinkService processLinkService;
    private final RepositoryService repositoryService;
    private final ProcessDefinitionCaseDefinitionService processDefinitionCaseDefinitionService;

    /**
     * Check connectivity to Epistola for each plugin configuration.
     * Uses getCatalogs() as a lightweight health probe.
     */
    public List<ConnectionStatus> checkConnections() {
        List<ConnectionStatus> results = new ArrayList<>();

        for (var entry : loadPluginConfigurations()) {
            PluginConfiguration config = entry.config();
            EpistolaPlugin plugin = entry.plugin();

            long start = System.currentTimeMillis();
            try {
                epistolaService.getCatalogs(plugin.getBaseUrl(), plugin.getApiKey(), plugin.getTenantId());
                long latency = System.currentTimeMillis() - start;
                // TODO: fetch server version when Epistola API exposes a version/health endpoint
                results.add(new ConnectionStatus(
                        config.getId().toString(),
                        config.getTitle(),
                        plugin.getTenantId(),
                        true,
                        latency,
                        null,
                        null
                ));
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;
                log.debug("Connection check failed for configuration '{}': {}", config.getTitle(), e.getMessage());
                results.add(new ConnectionStatus(
                        config.getId().toString(),
                        config.getTitle(),
                        plugin.getTenantId(),
                        false,
                        latency,
                        e.getMessage(),
                        null
                ));
            }
        }

        return results;
    }

    /**
     * Get version information for the plugin (and Epistola server if reachable).
     */
    public VersionInfo getVersions() {
        String pluginVersion = getPluginVersion();
        return new VersionInfo(pluginVersion, null);
    }

    /**
     * Scan all deployed process definitions for Epistola process links.
     * Reports each usage with basic problem detection.
     */
    public List<PluginUsageEntry> getPluginUsage() {
        List<PluginUsageEntry> entries = new ArrayList<>();

        List<ProcessDefinition> processDefinitions = repositoryService.createProcessDefinitionQuery()
                .latestVersion()
                .list();

        for (ProcessDefinition processDef : processDefinitions) {
            List<PluginProcessLink> epistolaLinks = processLinkService.getProcessLinks(processDef.getId()).stream()
                    .filter(PluginProcessLink.class::isInstance)
                    .map(PluginProcessLink.class::cast)
                    .filter(link -> EPISTOLA_ACTION_KEYS.contains(link.getPluginActionDefinitionKey()))
                    .toList();

            if (epistolaLinks.isEmpty()) {
                continue;
            }

            // Resolve case definition for this process
            var caseInfo = resolveCaseDefinition(processDef.getId());

            for (PluginProcessLink link : epistolaLinks) {
                String activityName = resolveActivityName(processDef.getId(), link.getActivityId());
                String configTitle = resolveConfigurationTitle(link.getPluginConfigurationId());
                List<String> problems = detectProblems(link);

                entries.add(new PluginUsageEntry(
                        link.getId().toString(),
                        caseInfo != null ? caseInfo.key() : null,
                        caseInfo != null ? caseInfo.versionTag() : null,
                        processDef.getKey(),
                        processDef.getName() != null ? processDef.getName() : processDef.getKey(),
                        link.getActivityId(),
                        activityName,
                        link.getPluginActionDefinitionKey(),
                        link.getPluginConfigurationId().toString(),
                        configTitle,
                        problems
                ));
            }
        }

        return entries;
    }

    /**
     * Export a single process link in Valtimo's .process-link.json auto-deploy format.
     */
    public ProcessLinkExport exportProcessLink(UUID processLinkId) {
        PluginProcessLink link = processLinkService.getProcessLink(processLinkId, PluginProcessLink.class);
        if (!EPISTOLA_ACTION_KEYS.contains(link.getPluginActionDefinitionKey())) {
            throw new IllegalArgumentException(
                    "Process link " + processLinkId + " is not an Epistola action");
        }
        return new ProcessLinkExport(
                link.getActivityId(),
                link.getActivityType().getValue(),
                link.getProcessLinkType(),
                link.getPluginConfigurationId() != null ? link.getPluginConfigurationId().toString() : null,
                link.getPluginActionDefinitionKey(),
                link.getActionProperties()
        );
    }

    private record CaseInfo(String key, String versionTag) {}

    private CaseInfo resolveCaseDefinition(String processDefinitionId) {
        try {
            ProcessDefinitionCaseDefinition link = processDefinitionCaseDefinitionService
                    .findByProcessDefinitionId(ProcessDefinitionId.of(processDefinitionId));
            if (link != null) {
                var caseDefId = link.getId().getCaseDefinitionId();
                return new CaseInfo(
                        caseDefId.getKey(),
                        caseDefId.getVersionTag() != null ? caseDefId.getVersionTag().toString() : null
                );
            }
        } catch (Exception e) {
            log.debug("Could not resolve case definition for process '{}': {}", processDefinitionId, e.getMessage());
        }
        return null;
    }

    private String resolveActivityName(String processDefinitionId, String activityId) {
        try {
            BpmnModelInstance model = repositoryService.getBpmnModelInstance(processDefinitionId);
            if (model != null) {
                FlowElement element = model.getModelElementById(activityId);
                if (element != null && element.getName() != null) {
                    return element.getName();
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve activity name for '{}': {}", activityId, e.getMessage());
        }
        return activityId;
    }

    private String resolveConfigurationTitle(PluginConfigurationId configurationId) {
        try {
            List<?> configurations = pluginService.findPluginConfigurations(
                    EpistolaPlugin.class, props -> true);
            for (Object config : configurations) {
                PluginConfiguration pluginConfig = (PluginConfiguration) config;
                if (pluginConfig.getId().equals(configurationId)) {
                    return pluginConfig.getTitle();
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve configuration title for '{}': {}", configurationId, e.getMessage());
        }
        return configurationId.toString();
    }

    private List<String> detectProblems(PluginProcessLink link) {
        List<String> problems = new ArrayList<>();

        try {
            pluginService.createInstance(link.getPluginConfigurationId());
        } catch (Exception e) {
            problems.add("Plugin configuration not found or invalid: " + e.getMessage());
            return problems;
        }

        var props = link.getActionProperties();
        if ("generate-document".equals(link.getPluginActionDefinitionKey())) {
            if (!props.has("templateId") || props.get("templateId").asText().isBlank()) {
                problems.add("No template configured");
            }
            if (!props.has("catalogId") || props.get("catalogId").asText().isBlank()) {
                problems.add("No catalog configured");
            }
        }

        return problems;
    }

    private String getPluginVersion() {
        String version = EpistolaAdminService.class.getPackage().getImplementationVersion();
        return version != null ? version : "development";
    }

    private record PluginConfigEntry(PluginConfiguration config, EpistolaPlugin plugin) {}

    private List<PluginConfigEntry> loadPluginConfigurations() {
        List<PluginConfigEntry> entries = new ArrayList<>();
        try {
            List<?> configurations = pluginService.findPluginConfigurations(
                    EpistolaPlugin.class, props -> true);
            for (Object config : configurations) {
                try {
                    PluginConfiguration pluginConfig = (PluginConfiguration) config;
                    EpistolaPlugin plugin = (EpistolaPlugin) pluginService.createInstance(pluginConfig);
                    entries.add(new PluginConfigEntry(pluginConfig, plugin));
                } catch (Exception e) {
                    log.warn("Failed to create plugin instance from configuration: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to load Epistola plugin configurations: {}", e.getMessage());
        }
        return entries;
    }
}
