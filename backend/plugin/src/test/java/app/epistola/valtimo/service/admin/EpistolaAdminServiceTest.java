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
import app.epistola.valtimo.deploy.CatalogScanner;
import app.epistola.valtimo.deploy.EpistolaCatalogSyncService;
import app.epistola.valtimo.deployment.EpistolaProcessDefinitionValidator;
import app.epistola.valtimo.service.admin.EpistolaAdminService;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.completion.EpistolaMessageCorrelationService;

import app.epistola.valtimo.domain.CatalogInfo;
import app.epistola.valtimo.domain.GenerationJobDetail;
import app.epistola.valtimo.domain.GenerationJobStatus;
import app.epistola.valtimo.domain.TemplateInfo;
import app.epistola.valtimo.domain.VariantInfo;
import app.epistola.valtimo.web.rest.dto.BpmnValidationReport;
import app.epistola.valtimo.web.rest.dto.BpmnValidationViolation;
import app.epistola.valtimo.web.rest.dto.CatalogRedeployResult;
import app.epistola.valtimo.web.rest.dto.ClasspathCatalog;
import app.epistola.valtimo.web.rest.dto.ConnectionStatus;
import app.epistola.valtimo.web.rest.dto.ContractCompatibilitySeverity;
import app.epistola.valtimo.web.rest.dto.PendingJob;
import app.epistola.valtimo.web.rest.dto.PluginUsageEntry;
import app.epistola.valtimo.web.rest.dto.ProcessLinkExport;
import app.epistola.valtimo.web.rest.dto.ReconcileResult;
import app.epistola.valtimo.web.rest.dto.VersionInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.domain.PluginConfigurationId;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.plugin.service.PluginService;
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService;
import com.ritense.processlink.domain.ActivityTypeWithEventName;
import com.ritense.processlink.domain.ProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.repository.ProcessDefinitionQuery;
import org.operaton.bpm.engine.runtime.Execution;
import org.operaton.bpm.engine.runtime.ExecutionQuery;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.FlowElement;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpistolaAdminServiceTest {

    private static final String BASE_URL = "https://api.epistola.test";
    private static final String API_KEY = "test-key";
    private static final String TENANT_ID = "test-tenant";
    private static final String CONFIG_TITLE = "Test Configuration";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PluginService pluginService;
    private EpistolaService epistolaService;
    private EpistolaMessageCorrelationService correlationService;
    private ProcessLinkService processLinkService;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private ProcessDefinitionCaseDefinitionService processDefinitionCaseDefinitionService;
    private EpistolaProcessDefinitionValidator processDefinitionValidator;
    private EpistolaCatalogSyncService catalogSyncService;
    private EpistolaAdminService adminService;

    @BeforeEach
    void setUp() {
        pluginService = mock(PluginService.class);
        epistolaService = mock(EpistolaService.class);
        correlationService = mock(EpistolaMessageCorrelationService.class);
        processLinkService = mock(ProcessLinkService.class);
        repositoryService = mock(RepositoryService.class);
        runtimeService = mock(RuntimeService.class);
        processDefinitionCaseDefinitionService = mock(ProcessDefinitionCaseDefinitionService.class);
        processDefinitionValidator = mock(EpistolaProcessDefinitionValidator.class);
        catalogSyncService = mock(EpistolaCatalogSyncService.class);
        adminService = new EpistolaAdminService(
                pluginService, epistolaService, correlationService, processLinkService, repositoryService,
                runtimeService, processDefinitionCaseDefinitionService, processDefinitionValidator,
                catalogSyncService);
    }

    @Nested
    class CheckConnections {

        @Test
        void shouldReturnReachableWhenCatalogsCallSucceeds() {
            mockSinglePluginConfiguration();
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenReturn(List.of(new CatalogInfo("cat-1", "Test Catalog", "default")));

            List<ConnectionStatus> results = adminService.checkConnections();

            assertThat(results).hasSize(1);
            ConnectionStatus status = results.get(0);
            assertThat(status.reachable()).isTrue();
            assertThat(status.configurationTitle()).isEqualTo(CONFIG_TITLE);
            assertThat(status.tenantId()).isEqualTo(TENANT_ID);
            assertThat(status.errorMessage()).isNull();
            assertThat(status.latencyMs()).isGreaterThanOrEqualTo(0);
            assertThat(status.contractVersion()).isEqualTo("0.8.0");
            assertThat(status.serverContractVersion()).isNull();
            assertThat(status.contractCompatibilitySeverity())
                    .isEqualTo(ContractCompatibilitySeverity.UNKNOWN);
        }

        @Test
        void shouldReturnServerVersionMetadataWhenPingSucceeds() {
            mockSinglePluginConfiguration();
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenReturn(List.of());
            when(epistolaService.getSystemInfo(BASE_URL, API_KEY))
                    .thenReturn(new EpistolaService.SystemInfo("0.26.3", "0.8.1"));

            List<ConnectionStatus> results = adminService.checkConnections();

            ConnectionStatus status = results.get(0);
            assertThat(status.serverVersion()).isEqualTo("0.26.3");
            assertThat(status.contractVersion()).isEqualTo("0.8.0");
            assertThat(status.serverContractVersion()).isEqualTo("0.8.1");
            assertThat(status.contractCompatibilitySeverity()).isEqualTo(ContractCompatibilitySeverity.OK);
        }

        @Test
        void shouldWarnWhenServerContractMinorIsOlderThanPluginContractMinor() {
            mockSinglePluginConfiguration();
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenReturn(List.of());
            when(epistolaService.getSystemInfo(BASE_URL, API_KEY))
                    .thenReturn(new EpistolaService.SystemInfo("0.25.9", "0.7.9"));

            List<ConnectionStatus> results = adminService.checkConnections();

            assertThat(results.get(0).contractCompatibilitySeverity())
                    .isEqualTo(ContractCompatibilitySeverity.WARNING);
        }

        @Test
        void shouldReportErrorWhenContractMajorDiffers() {
            mockSinglePluginConfiguration();
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenReturn(List.of());
            when(epistolaService.getSystemInfo(BASE_URL, API_KEY))
                    .thenReturn(new EpistolaService.SystemInfo("1.0.0", "1.0.0"));

            List<ConnectionStatus> results = adminService.checkConnections();

            assertThat(results.get(0).contractCompatibilitySeverity())
                    .isEqualTo(ContractCompatibilitySeverity.ERROR);
        }

        @Test
        void shouldTreatNewerServerMinorAndPatchAsCompatible() {
            mockSinglePluginConfiguration();
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenReturn(List.of());
            when(epistolaService.getSystemInfo(BASE_URL, API_KEY))
                    .thenReturn(new EpistolaService.SystemInfo("0.28.0", "0.9.0-SNAPSHOT"));

            List<ConnectionStatus> results = adminService.checkConnections();

            assertThat(results.get(0).contractCompatibilitySeverity())
                    .isEqualTo(ContractCompatibilitySeverity.OK);
        }

        @Test
        void shouldKeepConnectionReachableWhenPingFails() {
            mockSinglePluginConfiguration();
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenReturn(List.of());
            when(epistolaService.getSystemInfo(BASE_URL, API_KEY))
                    .thenThrow(new RuntimeException("ping not found"));

            List<ConnectionStatus> results = adminService.checkConnections();

            ConnectionStatus status = results.get(0);
            assertThat(status.reachable()).isTrue();
            assertThat(status.errorMessage()).isNull();
            assertThat(status.serverVersion()).isNull();
            assertThat(status.serverContractVersion()).isNull();
            assertThat(status.contractCompatibilitySeverity())
                    .isEqualTo(ContractCompatibilitySeverity.UNKNOWN);
        }

        @Test
        void shouldReturnUnreachableWhenCatalogsCallFails() {
            mockSinglePluginConfiguration();
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenThrow(new RuntimeException("Connection refused"));

            List<ConnectionStatus> results = adminService.checkConnections();

            assertThat(results).hasSize(1);
            ConnectionStatus status = results.get(0);
            assertThat(status.reachable()).isFalse();
            assertThat(status.errorMessage()).isEqualTo("Connection refused");
            assertThat(status.contractVersion()).isEqualTo("0.8.0");
            assertThat(status.contractCompatibilitySeverity())
                    .isEqualTo(ContractCompatibilitySeverity.UNKNOWN);
        }

        @Test
        void shouldReturnEmptyWhenNoConfigurations() {
            when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                    .thenReturn(Collections.emptyList());

            List<ConnectionStatus> results = adminService.checkConnections();

            assertThat(results).isEmpty();
        }

        @Test
        void shouldHandleMultipleConfigurations() {
            PluginConfiguration config1 = mockPluginConfiguration("Config 1");
            EpistolaPlugin plugin1 = mockPluginInstance("tenant-1");
            PluginConfiguration config2 = mockPluginConfiguration("Config 2");
            EpistolaPlugin plugin2 = mockPluginInstance("tenant-2");

            when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                    .thenReturn(List.of(config1, config2));
            when(pluginService.createInstance(config1)).thenReturn(plugin1);
            when(pluginService.createInstance(config2)).thenReturn(plugin2);

            when(epistolaService.getCatalogs(BASE_URL, API_KEY, "tenant-1"))
                    .thenReturn(List.of());
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, "tenant-2"))
                    .thenThrow(new RuntimeException("timeout"));

            List<ConnectionStatus> results = adminService.checkConnections();

            assertThat(results).hasSize(2);
            assertThat(results.get(0).reachable()).isTrue();
            assertThat(results.get(0).tenantId()).isEqualTo("tenant-1");
            assertThat(results.get(1).reachable()).isFalse();
            assertThat(results.get(1).tenantId()).isEqualTo("tenant-2");
        }
    }

    @Nested
    class GetVersions {

        @Test
        void shouldReturnDevelopmentVersionWhenNoManifest() {
            VersionInfo info = adminService.getVersions();

            assertThat(info.pluginVersion()).isEqualTo("development");
            assertThat(info.epistolaServerVersion()).isNull();
        }
    }

    @Nested
    class GetChangelog {

        @Test
        void returnsBundledChangelogParsedIntoReleases() {
            // processResources copies the repo CHANGELOG.md to epistola/CHANGELOG.md,
            // which is on the test runtime classpath.
            var releases = adminService.getChangelog();

            assertThat(releases).isNotEmpty();
            // Newest first; the repo CHANGELOG starts with the Unreleased block.
            assertThat(releases.get(0).version()).isEqualTo("Unreleased");
            assertThat(releases).anySatisfy(r -> {
                assertThat(r.version()).isEqualTo("0.8.0");
                assertThat(r.date()).isEqualTo("2026-05-08");
                assertThat(r.sections()).isNotEmpty();
            });
        }
    }

    @Nested
    class GetPluginUsage {

        @Test
        void shouldReturnEmptyWhenNoProcessDefinitions() {
            mockProcessDefinitionQuery(Collections.emptyList());

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).isEmpty();
        }

        @Test
        void shouldReturnEmptyWhenNoEpistolaLinks() {
            ProcessDefinition processDef = mockProcessDefinition("my-process", "My Process");
            mockProcessDefinitionQuery(List.of(processDef));
            when(processLinkService.getProcessLinks(processDef.getId()))
                    .thenReturn(Collections.emptyList());

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).isEmpty();
        }

        @Test
        void shouldFindGenerateDocumentLink() {
            ProcessDefinition processDef = mockProcessDefinition("my-process", "My Process");
            mockProcessDefinitionQuery(List.of(processDef));

            PluginProcessLink link = mockProcessLink("Activity_1", "epistola-generate-document",
                    createActionProps("cat-1", "tmpl-1"));
            mockPluginInstanceForLink(link);

            when(processLinkService.getProcessLinks(processDef.getId()))
                    .thenReturn(List.of(link));

            // Mock BPMN model for activity name resolution
            mockBpmnModel(processDef.getId(), "Activity_1", "Generate Letter");

            // Mock configuration title resolution
            mockSinglePluginConfiguration();

            // Configured catalog + template resolve in Epistola — no dangling references
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenReturn(List.of(new CatalogInfo("cat-1", "Catalog", "default")));
            when(epistolaService.getTemplates(BASE_URL, API_KEY, TENANT_ID, "cat-1"))
                    .thenReturn(List.of(new TemplateInfo("tmpl-1", "Template", "desc", "cat-1", "Catalog")));

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).hasSize(1);
            PluginUsageEntry entry = entries.get(0);
            assertThat(entry.processLinkId()).isNotNull();
            assertThat(entry.processDefinitionKey()).isEqualTo("my-process");
            assertThat(entry.processDefinitionName()).isEqualTo("My Process");
            assertThat(entry.activityName()).isEqualTo("Generate Letter");
            assertThat(entry.actionKey()).isEqualTo("epistola-generate-document");
            assertThat(entry.problems()).isEmpty();
        }

        @Test
        void shouldDetectMissingTemplateId() {
            ProcessDefinition processDef = mockProcessDefinition("my-process", "My Process");
            mockProcessDefinitionQuery(List.of(processDef));

            PluginProcessLink link = mockProcessLink("Activity_1", "epistola-generate-document",
                    createActionProps("cat-1", null));
            mockPluginInstanceForLink(link);

            when(processLinkService.getProcessLinks(processDef.getId()))
                    .thenReturn(List.of(link));
            mockSinglePluginConfiguration();

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).problems()).contains("No template configured");
        }

        @Test
        void shouldDetectMissingCatalogId() {
            ProcessDefinition processDef = mockProcessDefinition("my-process", "My Process");
            mockProcessDefinitionQuery(List.of(processDef));

            PluginProcessLink link = mockProcessLink("Activity_1", "epistola-generate-document",
                    createActionProps(null, "tmpl-1"));
            mockPluginInstanceForLink(link);

            when(processLinkService.getProcessLinks(processDef.getId()))
                    .thenReturn(List.of(link));
            mockSinglePluginConfiguration();

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).problems()).contains("No catalog configured");
        }

        @Test
        void shouldDetectInvalidPluginConfiguration() {
            ProcessDefinition processDef = mockProcessDefinition("my-process", "My Process");
            mockProcessDefinitionQuery(List.of(processDef));

            PluginProcessLink link = mockProcessLink("Activity_1", "epistola-generate-document",
                    createActionProps("cat-1", "tmpl-1"));

            // Plugin instance creation fails
            when(pluginService.createInstance(link.getPluginConfigurationId()))
                    .thenThrow(new RuntimeException("Config deleted"));
            when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                    .thenReturn(Collections.emptyList());

            when(processLinkService.getProcessLinks(processDef.getId()))
                    .thenReturn(List.of(link));

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).problems()).anyMatch(p -> p.contains("Plugin configuration not found"));
        }

        @Test
        void shouldIgnoreNonEpistolaLinks() {
            ProcessDefinition processDef = mockProcessDefinition("my-process", "My Process");
            mockProcessDefinitionQuery(List.of(processDef));

            // A non-PluginProcessLink
            ProcessLink otherLink = mock(ProcessLink.class);
            when(processLinkService.getProcessLinks(processDef.getId()))
                    .thenReturn(List.of(otherLink));

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).isEmpty();
        }

        @Test
        void shouldNotReportProblemsForCheckJobStatus() {
            ProcessDefinition processDef = mockProcessDefinition("my-process", "My Process");
            mockProcessDefinitionQuery(List.of(processDef));

            // check-job-status has no templateId/catalogId — should not flag problems
            ObjectNode props = objectMapper.createObjectNode();
            props.put("requestIdVariable", "epistolaRequestId");
            PluginProcessLink link = mockProcessLink("Activity_2", "epistola-check-job-status", props);
            mockPluginInstanceForLink(link);

            when(processLinkService.getProcessLinks(processDef.getId()))
                    .thenReturn(List.of(link));
            mockSinglePluginConfiguration();

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).actionKey()).isEqualTo("epistola-check-job-status");
            assertThat(entries.get(0).problems()).isEmpty();
        }

        @Test
        void shouldFallBackToKeyWhenProcessNameIsNull() {
            ProcessDefinition processDef = mockProcessDefinition("my-process", null);
            mockProcessDefinitionQuery(List.of(processDef));

            PluginProcessLink link = mockProcessLink("Activity_1", "epistola-generate-document",
                    createActionProps("cat-1", "tmpl-1"));
            mockPluginInstanceForLink(link);

            when(processLinkService.getProcessLinks(processDef.getId()))
                    .thenReturn(List.of(link));
            mockSinglePluginConfiguration();
            // Reference checks are irrelevant here; treat Epistola as unreachable so
            // they are skipped (graceful degradation — no false problems).
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenThrow(new RuntimeException("unreachable"));

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).processDefinitionName()).isEqualTo("my-process");
        }

        @Test
        void shouldFallBackToActivityIdWhenBpmnModelUnavailable() {
            ProcessDefinition processDef = mockProcessDefinition("my-process", "My Process");
            mockProcessDefinitionQuery(List.of(processDef));

            PluginProcessLink link = mockProcessLink("Activity_1", "epistola-generate-document",
                    createActionProps("cat-1", "tmpl-1"));
            mockPluginInstanceForLink(link);

            when(processLinkService.getProcessLinks(processDef.getId()))
                    .thenReturn(List.of(link));
            // No BPMN model available
            when(repositoryService.getBpmnModelInstance(processDef.getId())).thenReturn(null);
            mockSinglePluginConfiguration();
            // Reference checks are irrelevant here; treat Epistola as unreachable so
            // they are skipped (graceful degradation — no false problems).
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenThrow(new RuntimeException("unreachable"));

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).activityName()).isEqualTo("Activity_1");
        }

        @Test
        void shouldDetectMissingCatalog() {
            singleGenerateDocLink(createActionProps("cat-x", "tmpl-1"));
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenReturn(List.of(new CatalogInfo("cat-1", "Catalog", "default")));

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).problems())
                    .contains("Catalog 'cat-x' does not exist in Epistola");
            // Missing catalog short-circuits — no point probing templates.
            verify(epistolaService, never())
                    .getTemplates(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        void shouldDetectMissingTemplate() {
            singleGenerateDocLink(createActionProps("cat-1", "tmpl-x"));
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenReturn(List.of(new CatalogInfo("cat-1", "Catalog", "default")));
            when(epistolaService.getTemplates(BASE_URL, API_KEY, TENANT_ID, "cat-1"))
                    .thenReturn(List.of(new TemplateInfo("tmpl-1", "Template", "desc", "cat-1", "Catalog")));

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries.get(0).problems())
                    .contains("Template 'tmpl-x' does not exist in catalog 'cat-1'");
        }

        @Test
        void shouldDetectMissingVariant() {
            singleGenerateDocLink(createActionProps("cat-1", "tmpl-1", "nl"));
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenReturn(List.of(new CatalogInfo("cat-1", "Catalog", "default")));
            when(epistolaService.getTemplates(BASE_URL, API_KEY, TENANT_ID, "cat-1"))
                    .thenReturn(List.of(new TemplateInfo("tmpl-1", "Template", "desc", "cat-1", "Catalog")));
            when(epistolaService.getVariants(BASE_URL, API_KEY, TENANT_ID, "cat-1", "tmpl-1"))
                    .thenReturn(List.of(new VariantInfo("formal", "tmpl-1", "Formal", Map.of())));

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries.get(0).problems())
                    .contains("Variant 'nl' does not exist for template 'tmpl-1'");
        }

        @Test
        void shouldSkipVariantCheckWhenVariantIsExpression() {
            singleGenerateDocLink(createActionProps("cat-1", "tmpl-1", "$pv.lang"));
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenReturn(List.of(new CatalogInfo("cat-1", "Catalog", "default")));
            when(epistolaService.getTemplates(BASE_URL, API_KEY, TENANT_ID, "cat-1"))
                    .thenReturn(List.of(new TemplateInfo("tmpl-1", "Template", "desc", "cat-1", "Catalog")));

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries.get(0).problems()).isEmpty();
            // A JSONata expression resolves at runtime — never statically verified.
            verify(epistolaService, never())
                    .getVariants(anyString(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        void shouldNotFlagWhenEpistolaUnreachable() {
            singleGenerateDocLink(createActionProps("cat-1", "tmpl-1", "nl"));
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenThrow(new RuntimeException("Connection refused"));

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            // No false "does not exist" — reachability is the /health tab's job.
            assertThat(entries.get(0).problems()).isEmpty();
            verify(epistolaService, never())
                    .getTemplates(anyString(), anyString(), anyString(), anyString());
            verify(epistolaService, never())
                    .getVariants(anyString(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        void shouldReportNoProblemsWhenAllReferencesPresent() {
            singleGenerateDocLink(createActionProps("cat-1", "tmpl-1", "nl"));
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenReturn(List.of(new CatalogInfo("cat-1", "Catalog", "default")));
            when(epistolaService.getTemplates(BASE_URL, API_KEY, TENANT_ID, "cat-1"))
                    .thenReturn(List.of(new TemplateInfo("tmpl-1", "Template", "desc", "cat-1", "Catalog")));
            when(epistolaService.getVariants(BASE_URL, API_KEY, TENANT_ID, "cat-1", "tmpl-1"))
                    .thenReturn(List.of(new VariantInfo("nl", "tmpl-1", "Dutch", Map.of())));

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries.get(0).problems()).isEmpty();
        }

        @Test
        void shouldOnlyFetchCatalogsOncePerScanAcrossLinks() {
            ProcessDefinition pd1 = mockProcessDefinition("p1", "P1");
            ProcessDefinition pd2 = mockProcessDefinition("p2", "P2");
            mockProcessDefinitionQuery(List.of(pd1, pd2));

            PluginProcessLink link1 = mockProcessLink("A1", "epistola-generate-document",
                    createActionProps("cat-x", "tmpl-1"));
            PluginProcessLink link2 = mockProcessLink("A2", "epistola-generate-document",
                    createActionProps("cat-x", "tmpl-1"));
            mockPluginInstanceForLink(link1);
            mockPluginInstanceForLink(link2);
            when(processLinkService.getProcessLinks(pd1.getId())).thenReturn(List.of(link1));
            when(processLinkService.getProcessLinks(pd2.getId())).thenReturn(List.of(link2));
            mockSinglePluginConfiguration();
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenReturn(List.of(new CatalogInfo("cat-1", "Catalog", "default")));

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).hasSize(2);
            assertThat(entries).allSatisfy(e ->
                    assertThat(e.problems()).contains("Catalog 'cat-x' does not exist in Epistola"));
            // Per-invocation cache: one call serves both links on the same config.
            verify(epistolaService, times(1)).getCatalogs(BASE_URL, API_KEY, TENANT_ID);
        }

        @Test
        void shouldCacheNegativeFetchAcrossLinks() {
            ProcessDefinition pd1 = mockProcessDefinition("p1", "P1");
            ProcessDefinition pd2 = mockProcessDefinition("p2", "P2");
            mockProcessDefinitionQuery(List.of(pd1, pd2));

            PluginProcessLink link1 = mockProcessLink("A1", "epistola-generate-document",
                    createActionProps("cat-1", "tmpl-1"));
            PluginProcessLink link2 = mockProcessLink("A2", "epistola-generate-document",
                    createActionProps("cat-1", "tmpl-1"));
            mockPluginInstanceForLink(link1);
            mockPluginInstanceForLink(link2);
            when(processLinkService.getProcessLinks(pd1.getId())).thenReturn(List.of(link1));
            when(processLinkService.getProcessLinks(pd2.getId())).thenReturn(List.of(link2));
            mockSinglePluginConfiguration();
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenThrow(new RuntimeException("Connection refused"));

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).hasSize(2);
            assertThat(entries).allSatisfy(e -> assertThat(e.problems()).isEmpty());
            // Failed fetch is memoized (Optional.empty sentinel) — not re-probed per link.
            verify(epistolaService, times(1)).getCatalogs(BASE_URL, API_KEY, TENANT_ID);
        }
    }

    @Nested
    class CatalogRedeploy {

        @Test
        void listsClasspathCatalogsWithLiveEpistolaExistence() {
            PluginConfiguration config = mockPluginConfiguration(CONFIG_TITLE);
            EpistolaPlugin plugin = mockPluginInstance(TENANT_ID);
            when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                    .thenReturn(List.of(config));
            when(pluginService.createInstance(config)).thenReturn(plugin);
            String configId = config.getId().toString();

            when(catalogSyncService.listClasspathCatalogs()).thenReturn(List.of(
                    new CatalogScanner.CatalogOnClasspath("demo", "1.2.0", "config/epistola/catalogs/demo"),
                    new CatalogScanner.CatalogOnClasspath("other", "2.0.0", "config/epistola/catalogs/other")));
            // Epistola only has "demo".
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID)).thenReturn(List.of(
                    new CatalogInfo("demo", "Demo", "default")));

            List<ClasspathCatalog> result = adminService.listClasspathCatalogs(configId);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).slug()).isEqualTo("demo");
            assertThat(result.get(0).status()).isEqualTo(ClasspathCatalog.IN_EPISTOLA);
            assertThat(result.get(1).slug()).isEqualTo("other");
            assertThat(result.get(1).status()).isEqualTo(ClasspathCatalog.NOT_IN_EPISTOLA);
        }

        @Test
        void marksCatalogsUnknownWhenEpistolaUnreachable() {
            PluginConfiguration config = mockPluginConfiguration(CONFIG_TITLE);
            EpistolaPlugin plugin = mockPluginInstance(TENANT_ID);
            when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                    .thenReturn(List.of(config));
            when(pluginService.createInstance(config)).thenReturn(plugin);
            String configId = config.getId().toString();

            when(catalogSyncService.listClasspathCatalogs()).thenReturn(List.of(
                    new CatalogScanner.CatalogOnClasspath("demo", "1.2.0", "config/epistola/catalogs/demo")));
            when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID))
                    .thenThrow(new RuntimeException("Connection refused"));

            List<ClasspathCatalog> result = adminService.listClasspathCatalogs(configId);

            assertThat(result).hasSize(1);
            // Never falsely "not in Epistola" when we simply couldn't reach it.
            assertThat(result.get(0).status()).isEqualTo(ClasspathCatalog.UNKNOWN);
        }

        @Test
        void redeployDelegatesToSyncServiceWithConfigCredentials() {
            PluginConfiguration config = mockPluginConfiguration(CONFIG_TITLE);
            EpistolaPlugin plugin = mockPluginInstance(TENANT_ID);
            when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                    .thenReturn(List.of(config));
            when(pluginService.createInstance(config)).thenReturn(plugin);
            String configId = config.getId().toString();

            when(catalogSyncService.redeployCatalog(eq(configId), eq(BASE_URL), eq(API_KEY),
                    eq(TENANT_ID), anyString(), eq("demo")))
                    .thenReturn(new EpistolaCatalogSyncService.RedeployOutcome(
                            "demo", "1.2.0", true, "demo", 3, 1, 0, 4, null, null));

            CatalogRedeployResult result = adminService.redeployCatalog(configId, "demo");

            assertThat(result.success()).isTrue();
            assertThat(result.slug()).isEqualTo("demo");
            assertThat(result.installed()).isEqualTo(3);
            assertThat(result.total()).isEqualTo(4);
        }

        @Test
        void redeployRejectsUnknownConfiguration() {
            when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                    .thenReturn(Collections.emptyList());

            assertThatThrownBy(() -> adminService.redeployCatalog("nope", "demo"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No Epistola plugin configuration found with id=nope");
        }
    }

    @Nested
    class ExportProcessLink {

        @Test
        void shouldExportProcessLinkInAutoDeployFormat() {
            UUID linkId = UUID.randomUUID();
            ObjectNode actionProps = createActionProps("cat-1", "tmpl-1");
            actionProps.put("outputFormat", "PDF");
            actionProps.put("filename", "test.pdf");

            PluginProcessLink link = mockProcessLink("Activity_1", "epistola-generate-document", actionProps);
            lenient().when(link.getId()).thenReturn(linkId);

            when(processLinkService.getProcessLink(linkId, PluginProcessLink.class)).thenReturn(link);

            ProcessLinkExport export = adminService.exportProcessLink(linkId);

            assertThat(export.activityId()).isEqualTo("Activity_1");
            assertThat(export.activityType()).isEqualTo("bpmn:ServiceTask:start");
            assertThat(export.processLinkType()).isEqualTo("plugin");
            assertThat(export.pluginConfigurationId()).isEqualTo("config-id-mock");
            assertThat(export.pluginActionDefinitionKey()).isEqualTo("epistola-generate-document");
            assertThat(export.actionProperties().get("catalogId").asText()).isEqualTo("cat-1");
            assertThat(export.actionProperties().get("templateId").asText()).isEqualTo("tmpl-1");
            assertThat(export.actionProperties().get("outputFormat").asText()).isEqualTo("PDF");
        }

        @Test
        void shouldRejectNonEpistolaProcessLink() {
            UUID linkId = UUID.randomUUID();
            ObjectNode actionProps = objectMapper.createObjectNode();

            PluginProcessLink link = mockProcessLink("Activity_1", "some-other-action", actionProps);
            lenient().when(link.getId()).thenReturn(linkId);

            when(processLinkService.getProcessLink(linkId, PluginProcessLink.class)).thenReturn(link);

            assertThatThrownBy(() -> adminService.exportProcessLink(linkId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not an Epistola action");
        }
    }

    @Nested
    class GetPendingJobs {

        @Test
        void shouldReturnEmptyWhenNoWaitingExecutions() {
            ExecutionQuery query = mock(ExecutionQuery.class);
            when(runtimeService.createExecutionQuery()).thenReturn(query);
            when(query.messageEventSubscriptionName("EpistolaDocumentGenerated")).thenReturn(query);
            when(query.list()).thenReturn(Collections.emptyList());

            List<PendingJob> jobs = adminService.getPendingJobs();

            assertThat(jobs).isEmpty();
        }

        @Test
        void shouldReturnPendingJobWithResolvedNames() {
            Execution execution = mockExecution("exec-1", "pi-1", "my-process");

            ExecutionQuery query = mock(ExecutionQuery.class);
            when(runtimeService.createExecutionQuery()).thenReturn(query);
            when(query.messageEventSubscriptionName("EpistolaDocumentGenerated")).thenReturn(query);
            when(query.list()).thenReturn(List.of(execution));

            when(runtimeService.getVariable("exec-1", "epistolaWaitFor"))
                    .thenReturn("epistola:job:test-tenant/req-123");
            when(runtimeService.getActiveActivityIds("exec-1"))
                    .thenReturn(List.of("waitForDocument"));

            ProcessDefinition processDef = mockProcessDefinition("my-process", "My Process");
            ProcessDefinitionQuery pdQuery = mock(ProcessDefinitionQuery.class);
            when(repositoryService.createProcessDefinitionQuery()).thenReturn(pdQuery);
            when(pdQuery.processDefinitionKey("my-process")).thenReturn(pdQuery);
            when(pdQuery.latestVersion()).thenReturn(pdQuery);
            when(pdQuery.singleResult()).thenReturn(processDef);

            mockBpmnModel("my-process:1:abc123", "waitForDocument", "Wait for document");
            mockSinglePluginConfiguration();

            List<PendingJob> jobs = adminService.getPendingJobs();

            assertThat(jobs).hasSize(1);
            PendingJob job = jobs.get(0);
            assertThat(job.executionId()).isEqualTo("exec-1");
            assertThat(job.processInstanceId()).isEqualTo("pi-1");
            assertThat(job.processDefinitionKey()).isEqualTo("my-process");
            assertThat(job.processDefinitionName()).isEqualTo("My Process");
            assertThat(job.activityId()).isEqualTo("waitForDocument");
            assertThat(job.activityName()).isEqualTo("Wait for document");
            assertThat(job.tenantId()).isEqualTo("test-tenant");
            assertThat(job.requestId()).isEqualTo("req-123");
            assertThat(job.configurationTitle()).isEqualTo(CONFIG_TITLE);
            assertThat(job.status()).isEqualTo(PendingJob.STATUS_WAITING);
        }

        @Test
        void shouldSurfaceExecutionsWithoutJobPathAsUnwired() {
            // A wait with the subscription but no epistolaWaitFor token can never be correlated — it is
            // stuck. It must still be surfaced (previously it was silently skipped), best-effort tenant
            // resolved from the standalone epistolaTenantId variable, with status UNWIRED and no requestId.
            Execution execution = mockExecution("exec-1", "pi-1", "my-process");

            ExecutionQuery query = mock(ExecutionQuery.class);
            when(runtimeService.createExecutionQuery()).thenReturn(query);
            when(query.messageEventSubscriptionName("EpistolaDocumentGenerated")).thenReturn(query);
            when(query.list()).thenReturn(List.of(execution));

            when(runtimeService.getVariable("exec-1", "epistolaWaitFor")).thenReturn(null);
            when(runtimeService.getVariable("exec-1", "epistolaTenantId")).thenReturn("test-tenant");
            when(runtimeService.getActiveActivityIds("exec-1")).thenReturn(List.of("waitForDocument"));

            ProcessDefinition processDef = mockProcessDefinition("my-process", "My Process");
            ProcessDefinitionQuery pdQuery = mock(ProcessDefinitionQuery.class);
            when(repositoryService.createProcessDefinitionQuery()).thenReturn(pdQuery);
            when(pdQuery.processDefinitionKey("my-process")).thenReturn(pdQuery);
            when(pdQuery.latestVersion()).thenReturn(pdQuery);
            when(pdQuery.singleResult()).thenReturn(processDef);

            mockBpmnModel("my-process:1:abc123", "waitForDocument", "Wait for document");
            mockSinglePluginConfiguration();

            List<PendingJob> jobs = adminService.getPendingJobs();

            assertThat(jobs).hasSize(1);
            PendingJob job = jobs.get(0);
            assertThat(job.executionId()).isEqualTo("exec-1");
            assertThat(job.status()).isEqualTo(PendingJob.STATUS_UNWIRED);
            assertThat(job.requestId()).isNull();
            assertThat(job.tenantId()).isEqualTo("test-tenant");
            assertThat(job.activityName()).isEqualTo("Wait for document");
        }

        private Execution mockExecution(String executionId, String processInstanceId,
                                        String processDefinitionKey) {
            Execution execution = mock(Execution.class);
            lenient().when(execution.getId()).thenReturn(executionId);
            lenient().when(execution.getProcessInstanceId()).thenReturn(processInstanceId);
            lenient().when(execution.getProcessDefinitionKey()).thenReturn(processDefinitionKey);
            return execution;
        }
    }

    @Nested
    class Reconcile {

        @Test
        void shouldCorrelateWhenJobIsTerminal() {
            mockSinglePluginConfiguration();
            mockExecutionWithSubscription("exec-1", "pi-1", TENANT_ID + "/req-1");
            when(epistolaService.getJobStatus(BASE_URL, API_KEY, TENANT_ID, "req-1"))
                    .thenReturn(GenerationJobDetail.builder()
                            .requestId("req-1")
                            .status(GenerationJobStatus.FAILED)
                            .errorMessage("validation failed")
                            .build());
            when(correlationService.correlateCompletion(TENANT_ID, "req-1", "FAILED", null, "validation failed"))
                    .thenReturn(1);

            ReconcileResult result = adminService.reconcile("exec-1");

            assertThat(result.correlated()).isTrue();
            assertThat(result.correlatedCount()).isEqualTo(1);
            assertThat(result.epistolaStatus()).isEqualTo("FAILED");
            assertThat(result.tenantId()).isEqualTo(TENANT_ID);
            assertThat(result.requestId()).isEqualTo("req-1");
        }

        @Test
        void shouldNotCorrelateWhenJobIsStillPending() {
            mockSinglePluginConfiguration();
            mockExecutionWithSubscription("exec-1", "pi-1", TENANT_ID + "/req-2");
            when(epistolaService.getJobStatus(BASE_URL, API_KEY, TENANT_ID, "req-2"))
                    .thenReturn(GenerationJobDetail.builder()
                            .requestId("req-2")
                            .status(GenerationJobStatus.IN_PROGRESS)
                            .build());

            ReconcileResult result = adminService.reconcile("exec-1");

            assertThat(result.correlated()).isFalse();
            assertThat(result.correlatedCount()).isNull();
            assertThat(result.epistolaStatus()).isEqualTo("IN_PROGRESS");
        }

        @Test
        void shouldRejectMissingExecution() {
            ExecutionQuery query = mock(ExecutionQuery.class);
            when(runtimeService.createExecutionQuery()).thenReturn(query);
            when(query.executionId("missing")).thenReturn(query);
            when(query.messageEventSubscriptionName("EpistolaDocumentGenerated")).thenReturn(query);
            when(query.singleResult()).thenReturn(null);

            assertThatThrownBy(() -> adminService.reconcile("missing"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found or not waiting");
        }

        @Test
        void shouldRejectMissingJobPathVariable() {
            Execution execution = mock(Execution.class);
            lenient().when(execution.getId()).thenReturn("exec-1");
            lenient().when(execution.getProcessInstanceId()).thenReturn("pi-1");

            ExecutionQuery query = mock(ExecutionQuery.class);
            when(runtimeService.createExecutionQuery()).thenReturn(query);
            when(query.executionId("exec-1")).thenReturn(query);
            when(query.messageEventSubscriptionName("EpistolaDocumentGenerated")).thenReturn(query);
            when(query.singleResult()).thenReturn(execution);
            when(runtimeService.getVariable("exec-1", "epistolaWaitFor")).thenReturn(null);

            assertThatThrownBy(() -> adminService.reconcile("exec-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("epistolaWaitFor");
        }

        @Test
        void shouldRejectUnknownTenant() {
            // Plugin config exists but for a different tenant.
            PluginConfiguration config = mockPluginConfiguration(CONFIG_TITLE);
            EpistolaPlugin plugin = mockPluginInstance("other-tenant");
            when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                    .thenReturn(List.of(config));
            when(pluginService.createInstance(config)).thenReturn(plugin);
            mockExecutionWithSubscription("exec-1", "pi-1", TENANT_ID + "/req-3");

            assertThatThrownBy(() -> adminService.reconcile("exec-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No Epistola plugin configuration");
        }

        @Test
        void shouldRejectBlankExecutionId() {
            assertThatThrownBy(() -> adminService.reconcile(""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> adminService.reconcile(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        private void mockExecutionWithSubscription(String executionId, String processInstanceId,
                                                   String tenantSlashRequest) {
            Execution execution = mock(Execution.class);
            lenient().when(execution.getId()).thenReturn(executionId);
            lenient().when(execution.getProcessInstanceId()).thenReturn(processInstanceId);

            ExecutionQuery query = mock(ExecutionQuery.class);
            when(runtimeService.createExecutionQuery()).thenReturn(query);
            when(query.executionId(executionId)).thenReturn(query);
            when(query.messageEventSubscriptionName("EpistolaDocumentGenerated")).thenReturn(query);
            when(query.singleResult()).thenReturn(execution);

            when(runtimeService.getVariable(executionId, "epistolaWaitFor"))
                    .thenReturn("epistola:job:" + tenantSlashRequest);
        }
    }

    @Nested
    class GetValidationReport {

        @Test
        void composesReportFromValidatorState() {
            Instant lastChecked = Instant.parse("2026-06-08T10:15:30Z");
            BpmnValidationViolation violation = new BpmnValidationViolation(
                    "permit-confirmation", "Permit Confirmation", "generate-confirmation",
                    BpmnValidationViolation.CODE_ASYNC_BEFORE_ON_CATCH_EVENT, "boom");
            when(processDefinitionValidator.getLastCheckedAt()).thenReturn(lastChecked);
            when(processDefinitionValidator.getRefreshIntervalMs()).thenReturn(600_000L);
            when(processDefinitionValidator.getViolations()).thenReturn(List.of(violation));

            BpmnValidationReport report = adminService.getValidationReport();

            assertThat(report.lastCheckedAt()).isEqualTo(lastChecked);
            assertThat(report.refreshIntervalMs()).isEqualTo(600_000L);
            assertThat(report.violations()).containsExactly(violation);
        }

        @Test
        void nullLastCheckedBeforeFirstScan() {
            when(processDefinitionValidator.getLastCheckedAt()).thenReturn(null);
            when(processDefinitionValidator.getRefreshIntervalMs()).thenReturn(600_000L);
            when(processDefinitionValidator.getViolations()).thenReturn(List.of());

            BpmnValidationReport report = adminService.getValidationReport();

            assertThat(report.lastCheckedAt()).isNull();
            assertThat(report.violations()).isEmpty();
        }
    }

    // --- Helpers ---

    private void mockSinglePluginConfiguration() {
        PluginConfiguration config = mockPluginConfiguration(CONFIG_TITLE);
        EpistolaPlugin plugin = mockPluginInstance(TENANT_ID);

        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                .thenReturn(List.of(config));
        when(pluginService.createInstance(config)).thenReturn(plugin);
    }

    private PluginConfiguration mockPluginConfiguration(String title) {
        PluginConfiguration config = mock(PluginConfiguration.class);
        PluginConfigurationId configId = mock(PluginConfigurationId.class);
        lenient().when(config.getId()).thenReturn(configId);
        lenient().when(config.getTitle()).thenReturn(title);
        lenient().when(configId.toString()).thenReturn("config-" + title.hashCode());
        return config;
    }

    private EpistolaPlugin mockPluginInstance(String tenantId) {
        EpistolaPlugin plugin = mock(EpistolaPlugin.class);
        lenient().when(plugin.getBaseUrl()).thenReturn(BASE_URL);
        lenient().when(plugin.getApiKey()).thenReturn(API_KEY);
        lenient().when(plugin.getTenantId()).thenReturn(tenantId);
        return plugin;
    }

    private ProcessDefinition mockProcessDefinition(String key, String name) {
        ProcessDefinition processDef = mock(ProcessDefinition.class);
        lenient().when(processDef.getKey()).thenReturn(key);
        lenient().when(processDef.getName()).thenReturn(name);
        lenient().when(processDef.getId()).thenReturn(key + ":1:abc123");
        return processDef;
    }

    private void mockProcessDefinitionQuery(List<ProcessDefinition> definitions) {
        ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
        when(query.latestVersion()).thenReturn(query);
        when(query.list()).thenReturn(definitions);
    }

    private PluginProcessLink mockProcessLink(String activityId, String actionKey, ObjectNode actionProps) {
        PluginProcessLink link = mock(PluginProcessLink.class);
        PluginConfigurationId configId = mock(PluginConfigurationId.class);
        lenient().when(link.getId()).thenReturn(UUID.randomUUID());
        lenient().when(link.getActivityId()).thenReturn(activityId);
        lenient().when(link.getPluginActionDefinitionKey()).thenReturn(actionKey);
        lenient().when(link.getActionProperties()).thenReturn(actionProps);
        lenient().when(link.getPluginConfigurationId()).thenReturn(configId);
        lenient().when(link.getActivityType()).thenReturn(ActivityTypeWithEventName.SERVICE_TASK_START);
        lenient().when(link.getProcessLinkType()).thenReturn("plugin");
        lenient().when(configId.toString()).thenReturn("config-id-mock");
        return link;
    }

    private void mockPluginInstanceForLink(PluginProcessLink link) {
        EpistolaPlugin plugin = mockPluginInstance(TENANT_ID);
        lenient().when(pluginService.createInstance(link.getPluginConfigurationId())).thenReturn(plugin);
    }

    /** One latest process definition with a single epistola-generate-document link. */
    private void singleGenerateDocLink(ObjectNode props) {
        ProcessDefinition processDef = mockProcessDefinition("my-process", "My Process");
        mockProcessDefinitionQuery(List.of(processDef));
        PluginProcessLink link = mockProcessLink("Activity_1", "epistola-generate-document", props);
        mockPluginInstanceForLink(link);
        when(processLinkService.getProcessLinks(processDef.getId())).thenReturn(List.of(link));
        mockSinglePluginConfiguration();
    }

    private ObjectNode createActionProps(String catalogId, String templateId) {
        return createActionProps(catalogId, templateId, null);
    }

    private ObjectNode createActionProps(String catalogId, String templateId, String variantId) {
        ObjectNode props = objectMapper.createObjectNode();
        if (catalogId != null) {
            props.put("catalogId", catalogId);
        }
        if (templateId != null) {
            props.put("templateId", templateId);
        }
        if (variantId != null) {
            props.put("variantId", variantId);
        }
        return props;
    }

    private void mockBpmnModel(String processDefinitionId, String activityId, String activityName) {
        BpmnModelInstance model = mock(BpmnModelInstance.class);
        FlowElement element = mock(FlowElement.class);
        when(repositoryService.getBpmnModelInstance(processDefinitionId)).thenReturn(model);
        when(model.getModelElementById(activityId)).thenReturn(element);
        when(element.getName()).thenReturn(activityName);
    }
}
