package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.CatalogInfo;
import app.epistola.valtimo.web.rest.dto.ConnectionStatus;
import app.epistola.valtimo.web.rest.dto.PluginUsageEntry;
import app.epistola.valtimo.web.rest.dto.VersionInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.domain.PluginConfigurationId;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.plugin.service.PluginService;
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService;
import com.ritense.processlink.domain.ProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.repository.ProcessDefinitionQuery;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.FlowElement;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EpistolaAdminServiceTest {

    private static final String BASE_URL = "https://api.epistola.test";
    private static final String API_KEY = "test-key";
    private static final String TENANT_ID = "test-tenant";
    private static final String CONFIG_TITLE = "Test Configuration";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PluginService pluginService;
    private EpistolaService epistolaService;
    private ProcessLinkService processLinkService;
    private RepositoryService repositoryService;
    private ProcessDefinitionCaseDefinitionService processDefinitionCaseDefinitionService;
    private EpistolaAdminService adminService;

    @BeforeEach
    void setUp() {
        pluginService = mock(PluginService.class);
        epistolaService = mock(EpistolaService.class);
        processLinkService = mock(ProcessLinkService.class);
        repositoryService = mock(RepositoryService.class);
        processDefinitionCaseDefinitionService = mock(ProcessDefinitionCaseDefinitionService.class);
        adminService = new EpistolaAdminService(
                pluginService, epistolaService, processLinkService, repositoryService,
                processDefinitionCaseDefinitionService);
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

            PluginProcessLink link = mockProcessLink("Activity_1", "generate-document",
                    createActionProps("cat-1", "tmpl-1"));
            mockPluginInstanceForLink(link);

            when(processLinkService.getProcessLinks(processDef.getId()))
                    .thenReturn(List.of(link));

            // Mock BPMN model for activity name resolution
            mockBpmnModel(processDef.getId(), "Activity_1", "Generate Letter");

            // Mock configuration title resolution
            mockSinglePluginConfiguration();

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).hasSize(1);
            PluginUsageEntry entry = entries.get(0);
            assertThat(entry.processDefinitionKey()).isEqualTo("my-process");
            assertThat(entry.processDefinitionName()).isEqualTo("My Process");
            assertThat(entry.activityName()).isEqualTo("Generate Letter");
            assertThat(entry.actionKey()).isEqualTo("generate-document");
            assertThat(entry.problems()).isEmpty();
        }

        @Test
        void shouldDetectMissingTemplateId() {
            ProcessDefinition processDef = mockProcessDefinition("my-process", "My Process");
            mockProcessDefinitionQuery(List.of(processDef));

            PluginProcessLink link = mockProcessLink("Activity_1", "generate-document",
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

            PluginProcessLink link = mockProcessLink("Activity_1", "generate-document",
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

            PluginProcessLink link = mockProcessLink("Activity_1", "generate-document",
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
            PluginProcessLink link = mockProcessLink("Activity_2", "check-job-status", props);
            mockPluginInstanceForLink(link);

            when(processLinkService.getProcessLinks(processDef.getId()))
                    .thenReturn(List.of(link));
            mockSinglePluginConfiguration();

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).actionKey()).isEqualTo("check-job-status");
            assertThat(entries.get(0).problems()).isEmpty();
        }

        @Test
        void shouldFallBackToKeyWhenProcessNameIsNull() {
            ProcessDefinition processDef = mockProcessDefinition("my-process", null);
            mockProcessDefinitionQuery(List.of(processDef));

            PluginProcessLink link = mockProcessLink("Activity_1", "generate-document",
                    createActionProps("cat-1", "tmpl-1"));
            mockPluginInstanceForLink(link);

            when(processLinkService.getProcessLinks(processDef.getId()))
                    .thenReturn(List.of(link));
            mockSinglePluginConfiguration();

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).processDefinitionName()).isEqualTo("my-process");
        }

        @Test
        void shouldFallBackToActivityIdWhenBpmnModelUnavailable() {
            ProcessDefinition processDef = mockProcessDefinition("my-process", "My Process");
            mockProcessDefinitionQuery(List.of(processDef));

            PluginProcessLink link = mockProcessLink("Activity_1", "generate-document",
                    createActionProps("cat-1", "tmpl-1"));
            mockPluginInstanceForLink(link);

            when(processLinkService.getProcessLinks(processDef.getId()))
                    .thenReturn(List.of(link));
            // No BPMN model available
            when(repositoryService.getBpmnModelInstance(processDef.getId())).thenReturn(null);
            mockSinglePluginConfiguration();

            List<PluginUsageEntry> entries = adminService.getPluginUsage();

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).activityName()).isEqualTo("Activity_1");
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
        lenient().when(link.getActivityId()).thenReturn(activityId);
        lenient().when(link.getPluginActionDefinitionKey()).thenReturn(actionKey);
        lenient().when(link.getActionProperties()).thenReturn(actionProps);
        lenient().when(link.getPluginConfigurationId()).thenReturn(configId);
        lenient().when(configId.toString()).thenReturn("config-id-mock");
        return link;
    }

    private void mockPluginInstanceForLink(PluginProcessLink link) {
        EpistolaPlugin plugin = mockPluginInstance(TENANT_ID);
        lenient().when(pluginService.createInstance(link.getPluginConfigurationId())).thenReturn(plugin);
    }

    private ObjectNode createActionProps(String catalogId, String templateId) {
        ObjectNode props = objectMapper.createObjectNode();
        if (catalogId != null) {
            props.put("catalogId", catalogId);
        }
        if (templateId != null) {
            props.put("templateId", templateId);
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
