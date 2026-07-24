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
package app.epistola.valtimo.service.admin;

import app.epistola.valtimo.deploy.EpistolaCatalogSyncService;
import app.epistola.valtimo.deployment.EpistolaProcessDefinitionValidator;
import app.epistola.valtimo.domain.CatalogInfo;
import app.epistola.valtimo.domain.GenerationJobDetail;
import app.epistola.valtimo.domain.GenerationJobStatus;
import app.epistola.valtimo.domain.TemplateInfo;
import app.epistola.valtimo.domain.VariantInfo;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.completion.EpistolaMessageCorrelationService;
import app.epistola.valtimo.service.versioncheck.VersionCheckService;

import app.epistola.valtimo.domain.EpistolaProcessVariables;
import app.epistola.valtimo.web.rest.dto.BpmnValidationReport;
import app.epistola.valtimo.web.rest.dto.CatalogRedeployResult;
import app.epistola.valtimo.web.rest.dto.ChangelogRelease;
import app.epistola.valtimo.web.rest.dto.ClasspathCatalog;
import app.epistola.valtimo.web.rest.dto.ConnectionStatus;
import app.epistola.valtimo.web.rest.dto.ContractCompatibilitySeverity;
import app.epistola.valtimo.web.rest.dto.PendingJob;
import app.epistola.valtimo.web.rest.dto.PluginUsageEntry;
import app.epistola.valtimo.web.rest.dto.ProcessLinkExport;
import app.epistola.valtimo.web.rest.dto.ReconcileResult;
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
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.runtime.Execution;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.FlowElement;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service providing administrative information about the Epistola plugin:
 * connection health, version info, and usage overview across process definitions.
 */
@Slf4j
@RequiredArgsConstructor
public class EpistolaAdminService {

    private static final Set<String> EPISTOLA_ACTION_KEYS = Set.of(
            "epistola-generate-document", "epistola-check-job-status", "epistola-download-document"
    );

    /** Catalog import type — mirrors the value used by the startup sync trigger. */
    private static final String CATALOG_SYNC_TYPE = "AUTHORED";
    private static final String CONTRACT_VERSION_RESOURCE = "epistola-contract-version.txt";
    private static final Pattern SEMVER_PREFIX = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?.*");

    private final PluginService pluginService;
    private final EpistolaService epistolaService;
    private final EpistolaMessageCorrelationService correlationService;
    private final ProcessLinkService processLinkService;
    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final ProcessDefinitionCaseDefinitionService processDefinitionCaseDefinitionService;
    private final EpistolaProcessDefinitionValidator processDefinitionValidator;
    private final EpistolaCatalogSyncService catalogSyncService;
    private final VersionCheckService versionCheckService;

    /**
     * Latest BPMN race-safety validation report: the violation snapshot (empty when
     * everything's well-formed — the desired steady state) plus the last-checked
     * timestamp and scan cadence so the admin UI can convey freshness. Cheap to call —
     * backed by an in-memory snapshot the validator updates on its scheduled tick.
     */
    public BpmnValidationReport getValidationReport() {
        return new BpmnValidationReport(
                processDefinitionValidator.getLastCheckedAt(),
                processDefinitionValidator.getRefreshIntervalMs(),
                processDefinitionValidator.getViolations()
        );
    }

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
                EpistolaService.SystemInfo systemInfo = fetchSystemInfo(plugin);
                String contractVersion = getContractVersion();
                ContractCompatibilitySeverity compatibilitySeverity = classifyContractCompatibility(
                        contractVersion,
                        systemInfo != null ? systemInfo.contractVersion() : null
                );
                long latency = System.currentTimeMillis() - start;
                results.add(new ConnectionStatus(
                        config.getId().toString(),
                        config.getTitle(),
                        plugin.getTenantId(),
                        true,
                        latency,
                        null,
                        systemInfo != null ? systemInfo.serverVersion() : null,
                        contractVersion,
                        systemInfo != null ? systemInfo.contractVersion() : null,
                        compatibilitySeverity
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
                        null,
                        getContractVersion(),
                        null,
                        ContractCompatibilitySeverity.UNKNOWN
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
        return new VersionInfo(pluginVersion, null, versionCheckService.status(pluginVersion));
    }

    private EpistolaService.SystemInfo fetchSystemInfo(EpistolaPlugin plugin) {
        try {
            return epistolaService.getSystemInfo(plugin.getBaseUrl(), plugin.getApiKey());
        } catch (Exception e) {
            log.debug("Could not fetch Epistola system metadata for tenant {}: {}",
                    plugin.getTenantId(), e.getMessage());
            return null;
        }
    }

    private ContractCompatibilitySeverity classifyContractCompatibility(
            String contractVersion,
            String serverContractVersion
    ) {
        Optional<SemanticVersion> local = SemanticVersion.parse(contractVersion);
        Optional<SemanticVersion> server = SemanticVersion.parse(serverContractVersion);
        if (local.isEmpty() || server.isEmpty()) {
            return ContractCompatibilitySeverity.UNKNOWN;
        }
        if (local.get().major() != server.get().major()) {
            return ContractCompatibilitySeverity.ERROR;
        }
        if (server.get().minor() < local.get().minor()) {
            return ContractCompatibilitySeverity.WARNING;
        }
        return ContractCompatibilitySeverity.OK;
    }

    /**
     * The plugin's CHANGELOG, parsed into structured releases for the admin UI.
     * The raw markdown is bundled into the jar at build time (classpath
     * {@code epistola/CHANGELOG.md}). Returns an empty list rather than failing
     * if it is absent or unparseable — the admin page treats it as non-critical.
     */
    public List<ChangelogRelease> getChangelog() {
        return ChangelogParser.parse(readBundledChangelog());
    }

    private String readBundledChangelog() {
        try (var in = getClass().getClassLoader().getResourceAsStream("epistola/CHANGELOG.md")) {
            if (in == null) {
                log.debug("epistola/CHANGELOG.md not found on classpath");
                return null;
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to read bundled CHANGELOG: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Scan all deployed process definitions for Epistola process links.
     * Reports each usage with basic problem detection.
     */
    public List<PluginUsageEntry> getPluginUsage() {
        List<PluginUsageEntry> entries = new ArrayList<>();
        EpistolaReferenceCache refCache = new EpistolaReferenceCache();

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
                List<String> problems = detectProblems(link, refCache);

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

    /**
     * Find all process instances currently waiting for an Epistola document generation result.
     */
    public List<PendingJob> getPendingJobs() {
        List<Execution> waitingExecutions = runtimeService.createExecutionQuery()
                .messageEventSubscriptionName(EpistolaProcessVariables.MESSAGE_NAME)
                .list();

        if (waitingExecutions.isEmpty()) {
            return List.of();
        }

        Map<String, String> configTitlesByTenant = buildTenantConfigTitleMap();
        List<PendingJob> jobs = new ArrayList<>();

        for (Execution execution : waitingExecutions) {
            try {
                String jobPath = (String) runtimeService.getVariable(
                        execution.getId(), EpistolaProcessVariables.WAIT_FOR);

                String tenantId;
                String requestId;
                String status;
                if (jobPath != null) {
                    String[] parts = EpistolaMessageCorrelationService.parseJobPath(jobPath);
                    tenantId = parts[0];
                    requestId = parts[1];
                    status = PendingJob.STATUS_WAITING;
                } else {
                    // No correlation token pinned: the collector can never wake this execution, so the
                    // process is stuck (e.g. an ambiguous merged catch event, or auto-wiring disabled).
                    // Previously these were skipped and thus invisible in admin — exactly the casualty an
                    // operator most needs to see. Surface it as UNWIRED. tenantId is best-effort from the
                    // standalone epistolaTenantId variable generate-document writes at instance scope
                    // (present even without the token), so the row still lands on the right config card.
                    Object tenant = runtimeService.getVariable(
                            execution.getId(), EpistolaProcessVariables.TENANT_ID);
                    tenantId = tenant instanceof String s ? s : null;
                    requestId = null;
                    status = PendingJob.STATUS_UNWIRED;
                }

                // Resolve activity ID from active activities on this execution
                List<String> activeActivities = runtimeService.getActiveActivityIds(execution.getId());
                String activityId = activeActivities.isEmpty() ? null : activeActivities.get(0);

                // Resolve process definition from process instance
                String processDefinitionKey = execution.getProcessDefinitionKey();
                ProcessDefinition processDef = resolveProcessDefinition(processDefinitionKey);
                String processDefName = processDef != null
                        ? (processDef.getName() != null ? processDef.getName() : processDef.getKey())
                        : processDefinitionKey;
                String activityName = activityId != null && processDef != null
                        ? resolveActivityName(processDef.getId(), activityId)
                        : activityId;

                String configTitle = tenantId != null
                        ? configTitlesByTenant.getOrDefault(tenantId, tenantId)
                        : null;

                jobs.add(new PendingJob(
                        execution.getId(),
                        execution.getProcessInstanceId(),
                        processDefinitionKey,
                        processDefName,
                        activityId,
                        activityName,
                        tenantId,
                        requestId,
                        configTitle,
                        status
                ));
            } catch (Exception e) {
                log.warn("Failed to read pending job for execution {}: {}",
                        execution.getId(), e.getMessage());
            }
        }

        return jobs;
    }

    /**
     * Manual recovery for a stuck Epistola catch event.
     *
     * <p>Looks up the execution by id, validates that it still has an active
     * {@code EpistolaDocumentGenerated} subscription, reads its {@code epistolaWaitFor} jobPath,
     * fetches the current job status from Epistola, and runs message correlation
     * if the job is in a terminal state. This is the way out when the natural
     * collector→correlate path missed the original message — typically a narrow
     * transactional race between the result-collector poll and the BPMN engine
     * commit; see {@link app.epistola.valtimo.deployment.EpistolaProcessDefinitionValidator}
     * for the structural prevention.
     *
     * @param executionId the catch-event execution id (matches the {@code executionId}
     *                    field of the {@link PendingJob} rows shown in the admin UI).
     * @throws IllegalArgumentException if the execution doesn't exist, isn't waiting
     *         for an Epistola message, has no jobPath variable, or no plugin configuration
     *         is registered for its tenant.
     * @return a {@link ReconcileResult}; {@link ReconcileResult#correlated()} is
     *         {@code false} when Epistola reports a non-terminal status — the
     *         caller should map that to HTTP 409 so the UI can surface "still pending".
     */
    public ReconcileResult reconcile(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must not be blank");
        }

        Execution execution = runtimeService.createExecutionQuery()
                .executionId(executionId)
                .messageEventSubscriptionName(EpistolaProcessVariables.MESSAGE_NAME)
                .singleResult();
        if (execution == null) {
            throw new IllegalArgumentException(
                    "Execution " + executionId + " not found or not waiting for an Epistola message");
        }

        String jobPath = (String) runtimeService.getVariable(
                execution.getId(), EpistolaProcessVariables.WAIT_FOR);
        if (jobPath == null) {
            // Execution exists and has the subscription but lost the variable somehow;
            // we can't reconstruct (tenantId, requestId) without it.
            throw new IllegalArgumentException(
                    "Execution " + executionId + " has no " + EpistolaProcessVariables.WAIT_FOR + " variable");
        }

        String[] parts = EpistolaMessageCorrelationService.parseJobPath(jobPath);
        String tenantId = parts[0];
        String requestId = parts[1];

        EpistolaPlugin plugin = findPluginForTenant(tenantId);
        GenerationJobDetail detail = epistolaService.getJobStatus(
                plugin.getBaseUrl(), plugin.getApiKey(), tenantId, requestId);
        GenerationJobStatus status = detail.getStatus();

        if (!isTerminal(status)) {
            log.info("Reconcile: tenantId={}, requestId={} still {} on Epistola — nothing to correlate",
                    tenantId, requestId, status);
            return new ReconcileResult(
                    execution.getId(),
                    execution.getProcessInstanceId(),
                    tenantId,
                    requestId,
                    status.name(),
                    null,
                    false
            );
        }

        int count = correlationService.correlateCompletion(
                tenantId,
                requestId,
                status.name(),
                detail.getDocumentId(),
                detail.getErrorMessage()
        );
        log.info("Reconcile: correlated executionId={} (tenantId={}, requestId={}, status={}): {} instance(s)",
                execution.getId(), tenantId, requestId, status, count);

        return new ReconcileResult(
                execution.getId(),
                execution.getProcessInstanceId(),
                tenantId,
                requestId,
                status.name(),
                count,
                true
        );
    }

    private static boolean isTerminal(GenerationJobStatus status) {
        return status == GenerationJobStatus.COMPLETED
                || status == GenerationJobStatus.FAILED
                || status == GenerationJobStatus.CANCELLED;
    }

    private EpistolaPlugin findPluginForTenant(String tenantId) {
        for (PluginConfigEntry entry : loadPluginConfigurations()) {
            if (tenantId.equals(entry.plugin().getTenantId())) {
                return entry.plugin();
            }
        }
        throw new IllegalArgumentException(
                "No Epistola plugin configuration found for tenantId=" + tenantId);
    }

    private PluginConfigEntry findConfigEntry(String configurationId) {
        for (PluginConfigEntry entry : loadPluginConfigurations()) {
            if (entry.config().getId().toString().equals(configurationId)) {
                return entry;
            }
        }
        throw new IllegalArgumentException(
                "No Epistola plugin configuration found with id=" + configurationId);
    }

    /**
     * List the classpath catalogs available to manually (re)deploy for a plugin
     * configuration, each annotated with whether it currently exists in that
     * configuration's Epistola installation. Existence is resolved live by querying
     * Epistola (it exposes no catalog version, so this is presence, not a version
     * match). If Epistola is unreachable the status is {@code UNKNOWN} — never
     * falsely reported as missing.
     */
    public List<ClasspathCatalog> listClasspathCatalogs(String configurationId) {
        EpistolaPlugin plugin = findConfigEntry(configurationId).plugin();
        Optional<Set<String>> epistolaCatalogIds = fetchEpistolaCatalogIds(plugin);

        return catalogSyncService.listClasspathCatalogs().stream()
                .map(c -> new ClasspathCatalog(
                        c.slug(),
                        c.version(),
                        epistolaCatalogIds
                                .map(ids -> ids.contains(c.slug())
                                        ? ClasspathCatalog.IN_EPISTOLA
                                        : ClasspathCatalog.NOT_IN_EPISTOLA)
                                .orElse(ClasspathCatalog.UNKNOWN)))
                .toList();
    }

    private Optional<Set<String>> fetchEpistolaCatalogIds(EpistolaPlugin plugin) {
        try {
            return Optional.of(epistolaService
                    .getCatalogs(plugin.getBaseUrl(), plugin.getApiKey(), plugin.getTenantId())
                    .stream().map(CatalogInfo::id).collect(Collectors.toSet()));
        } catch (Exception e) {
            log.debug("Could not list Epistola catalogs for existence check: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Force-redeploy a single classpath catalog to the configuration's Epistola
     * installation. Explicit operator action — runs regardless of the
     * {@code templateSyncEnabled} plugin property (which only gates the automatic
     * startup sync) and regardless of whether the catalog version changed.
     */
    public CatalogRedeployResult redeployCatalog(String configurationId, String slug) {
        EpistolaPlugin plugin = findConfigEntry(configurationId).plugin();
        EpistolaCatalogSyncService.RedeployOutcome outcome = catalogSyncService.redeployCatalog(
                configurationId,
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId(),
                CATALOG_SYNC_TYPE,
                slug);
        return new CatalogRedeployResult(
                outcome.slug(),
                outcome.version(),
                outcome.success(),
                outcome.catalogKey(),
                outcome.installed(),
                outcome.updated(),
                outcome.failed(),
                outcome.total(),
                outcome.errorMessage(),
                outcome.httpStatus());
    }

    private Map<String, String> buildTenantConfigTitleMap() {
        Map<String, String> result = new HashMap<>();
        for (PluginConfigEntry entry : loadPluginConfigurations()) {
            result.put(entry.plugin().getTenantId(), entry.config().getTitle());
        }
        return result;
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

    private ProcessDefinition resolveProcessDefinition(String processDefinitionKey) {
        try {
            return repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(processDefinitionKey)
                    .latestVersion()
                    .singleResult();
        } catch (Exception e) {
            log.debug("Could not resolve process definition for key '{}': {}", processDefinitionKey, e.getMessage());
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

    /**
     * Mirrors the frontend {@code isExpression()} rule in preview-utils.ts: a value
     * is a JSONata expression (not a literal) if it contains any of {@code $ & ( { ? [}.
     */
    private static final Pattern JSONATA_MARKER = Pattern.compile("[$&({?\\[]");

    private List<String> detectProblems(PluginProcessLink link, EpistolaReferenceCache refCache) {
        List<String> problems = new ArrayList<>();

        EpistolaPlugin plugin;
        try {
            plugin = (EpistolaPlugin) pluginService.createInstance(link.getPluginConfigurationId());
        } catch (Exception e) {
            problems.add("Plugin configuration not found or invalid: " + e.getMessage());
            return problems;
        }

        var props = link.getActionProperties();
        if ("epistola-generate-document".equals(link.getPluginActionDefinitionKey())) {
            String catalogId = textOrNull(props, "catalogId");
            String templateId = textOrNull(props, "templateId");
            String variantId = textOrNull(props, "variantId");

            boolean catalogConfigured = catalogId != null && !catalogId.isBlank();
            boolean templateConfigured = templateId != null && !templateId.isBlank();

            if (!templateConfigured) {
                problems.add("No template configured");
            }
            if (!catalogConfigured) {
                problems.add("No catalog configured");
            }

            // Best-effort: verify the configured references still exist in the connected
            // Epistola installation (e.g. a classpath catalog whose startup auto-deploy
            // failed). Ordered catalog -> template -> variant, each gated on the previous
            // existing so a missing catalog doesn't also report a misleading "template not
            // found". When Epistola is unreachable the cache yields an empty Optional and
            // the checks are skipped — never a false "does not exist" (reachability is the
            // /health tab's job). variantId is only checked when it is a plain literal;
            // a JSONata expression resolves at runtime and cannot be verified statically.
            if (catalogConfigured && templateConfigured) {
                String configId = link.getPluginConfigurationId().toString();

                Optional<Set<String>> catalogIds = refCache.catalogIds(configId, plugin);
                if (catalogIds.isPresent()) {
                    if (!catalogIds.get().contains(catalogId)) {
                        problems.add("Catalog '" + catalogId + "' does not exist in Epistola");
                    } else {
                        Optional<Set<String>> templateIds =
                                refCache.templateIds(configId, catalogId, plugin);
                        if (templateIds.isPresent()) {
                            if (!templateIds.get().contains(templateId)) {
                                problems.add("Template '" + templateId
                                        + "' does not exist in catalog '" + catalogId + "'");
                            } else if (isLiteralVariantId(variantId)) {
                                Optional<Set<String>> variantIds =
                                        refCache.variantIds(configId, catalogId, templateId, plugin);
                                if (variantIds.isPresent() && !variantIds.get().contains(variantId)) {
                                    problems.add("Variant '" + variantId
                                            + "' does not exist for template '" + templateId + "'");
                                }
                            }
                        }
                    }
                }
            }
        }

        return problems;
    }

    private static String textOrNull(JsonNode props, String field) {
        return props != null && props.hasNonNull(field) ? props.get(field).asText() : null;
    }

    /**
     * A {@code variantId} is a verifiable literal only when present, non-blank and free
     * of JSONata markers. Absent/blank means default-variant mode (not a problem); a
     * JSONata expression is resolved at runtime and silently skipped.
     */
    private static boolean isLiteralVariantId(String variantId) {
        return variantId != null
                && !variantId.isBlank()
                && !JSONATA_MARKER.matcher(variantId).find();
    }

    /**
     * Per-{@link #getPluginUsage()}-invocation memo of which catalog/template/variant
     * ids exist in the connected Epistola installation. Created fresh per call so an
     * admin refresh sees current state. A failed fetch is memoized as
     * {@link Optional#empty()} so an unreachable config is not re-probed for every
     * link (a plain nullable Set would not stick — {@code computeIfAbsent} does not
     * store {@code null}). Non-static so it closes over {@code epistolaService}/{@code log}.
     */
    private final class EpistolaReferenceCache {

        private final Map<String, Optional<Set<String>>> catalogIdsByConfig = new HashMap<>();
        private final Map<String, Optional<Set<String>>> templateIdsByConfigCatalog = new HashMap<>();
        private final Map<String, Optional<Set<String>>> variantIdsByConfigCatalogTemplate = new HashMap<>();

        /** Present = the existing-id set; empty = lookup failed (caller must skip the check). */
        Optional<Set<String>> catalogIds(String configId, EpistolaPlugin plugin) {
            return catalogIdsByConfig.computeIfAbsent(configId, k -> {
                try {
                    return Optional.of(epistolaService
                            .getCatalogs(plugin.getBaseUrl(), plugin.getApiKey(), plugin.getTenantId())
                            .stream().map(CatalogInfo::id).collect(Collectors.toSet()));
                } catch (Exception e) {
                    log.debug("Catalog existence check skipped for config {}: {}",
                            configId, e.getMessage());
                    return Optional.empty();
                }
            });
        }

        Optional<Set<String>> templateIds(String configId, String catalogId, EpistolaPlugin plugin) {
            return templateIdsByConfigCatalog.computeIfAbsent(configId + '|' + catalogId, k -> {
                try {
                    return Optional.of(epistolaService
                            .getTemplates(plugin.getBaseUrl(), plugin.getApiKey(),
                                    plugin.getTenantId(), catalogId)
                            .stream().map(TemplateInfo::id).collect(Collectors.toSet()));
                } catch (Exception e) {
                    log.debug("Template existence check skipped for config {} catalog {}: {}",
                            configId, catalogId, e.getMessage());
                    return Optional.empty();
                }
            });
        }

        Optional<Set<String>> variantIds(String configId, String catalogId,
                                          String templateId, EpistolaPlugin plugin) {
            return variantIdsByConfigCatalogTemplate.computeIfAbsent(
                    configId + '|' + catalogId + '|' + templateId, k -> {
                        try {
                            return Optional.of(epistolaService
                                    .getVariants(plugin.getBaseUrl(), plugin.getApiKey(),
                                            plugin.getTenantId(), catalogId, templateId)
                                    .stream().map(VariantInfo::id).collect(Collectors.toSet()));
                        } catch (Exception e) {
                            log.debug("Variant existence check skipped for config {} "
                                    + "catalog {} template {}: {}",
                                    configId, catalogId, templateId, e.getMessage());
                            return Optional.empty();
                        }
                    });
        }
    }

    private String getPluginVersion() {
        // Implementation-Version is set on the jar manifest by the build (see
        // backend/plugin/build.gradle.kts). It is only meaningful for versioned
        // (release) builds; plain local builds leave Gradle's "unspecified"
        // default, which we surface as "development".
        String version = EpistolaAdminService.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank() || "unspecified".equals(version)) {
            return "development";
        }
        return version;
    }

    private String getContractVersion() {
        try (var in = getClass().getClassLoader().getResourceAsStream(CONTRACT_VERSION_RESOURCE)) {
            if (in == null) {
                return null;
            }
            String version = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return version.isBlank() ? null : version;
        } catch (Exception e) {
            log.debug("Failed to read {}: {}", CONTRACT_VERSION_RESOURCE, e.getMessage());
            return null;
        }
    }

    private record SemanticVersion(int major, int minor) {
        static Optional<SemanticVersion> parse(String version) {
            if (version == null || version.isBlank()) {
                return Optional.empty();
            }
            var matcher = SEMVER_PREFIX.matcher(version.trim());
            if (!matcher.matches()) {
                return Optional.empty();
            }
            return Optional.of(new SemanticVersion(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2))
            ));
        }
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
