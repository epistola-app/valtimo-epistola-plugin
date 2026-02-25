package app.epistola.valtimo.deploy;

import app.epistola.client.model.ImportTemplateResultDto;
import app.epistola.client.model.ImportTemplatesRequest;
import app.epistola.client.model.ImportTemplatesResponse;
import app.epistola.client.model.VariantSelectionAttribute;
import app.epistola.valtimo.domain.*;
import app.epistola.valtimo.service.EpistolaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EpistolaTemplateSyncServiceTest {

    private static final String CONFIG_ID = "config-1";
    private static final String BASE_URL = "http://localhost:4010/api";
    private static final String API_KEY = "test-key";
    private static final String TENANT_ID = "test-tenant";

    private ObjectMapper objectMapper;
    private CapturingEpistolaService capturingService;
    private EpistolaTemplateSyncService syncService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new KotlinModule.Builder().build());
        capturingService = new CapturingEpistolaService();
        // Use a fixed scanner that returns test definitions
        syncService = new EpistolaTemplateSyncService(
                new FixedScanner(List.of()),
                capturingService,
                objectMapper
        );
    }

    private EpistolaTemplateSyncService createSyncService(List<TemplateDefinition> definitions) {
        return new EpistolaTemplateSyncService(
                new FixedScanner(definitions),
                capturingService,
                objectMapper
        );
    }

    private TemplateDefinition createDefinition(String slug, String version) {
        ObjectNode dataModel = JsonNodeFactory.instance.objectNode();
        dataModel.put("type", "object");

        // Build a valid TemplateDocumentDto-compatible template model with all required fields
        ObjectNode templateModel = JsonNodeFactory.instance.objectNode();
        templateModel.put("modelVersion", 1);
        templateModel.put("root", "n-root");

        ObjectNode rootNode = JsonNodeFactory.instance.objectNode();
        rootNode.put("id", "n-root");
        rootNode.put("type", "container");
        rootNode.set("slots", JsonNodeFactory.instance.arrayNode().add("s-root"));

        ObjectNode nodes = JsonNodeFactory.instance.objectNode();
        nodes.set("n-root", rootNode);
        templateModel.set("nodes", nodes);

        ObjectNode rootSlot = JsonNodeFactory.instance.objectNode();
        rootSlot.put("id", "s-root");
        rootSlot.put("nodeId", "n-root");
        rootSlot.put("name", "children");
        rootSlot.set("children", JsonNodeFactory.instance.arrayNode());

        ObjectNode slots = JsonNodeFactory.instance.objectNode();
        slots.set("s-root", rootSlot);
        templateModel.set("slots", slots);

        ObjectNode themeRef = JsonNodeFactory.instance.objectNode();
        themeRef.put("type", "inherit");
        templateModel.set("themeRef", themeRef);

        return new TemplateDefinition(
                slug,
                slug + " Template",
                version,
                dataModel,
                List.of(),
                templateModel,
                List.of(),
                List.of("production")
        );
    }

    @Nested
    class WhenNoTemplatesOnClasspath {

        @Test
        void syncTemplates_returnsZeroCounts() {
            syncService = createSyncService(List.of());
            EpistolaTemplateSyncService.SyncResult result =
                    syncService.syncTemplates(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID);

            assertEquals(0, result.totalTemplates());
            assertEquals(0, result.successCount());
            assertEquals(0, result.failCount());
            assertTrue(result.isFullySuccessful());
            assertNull(capturingService.lastImportRequest, "Should not call import API");
        }
    }

    @Nested
    class WhenAllTemplatesAreNew {

        @Test
        void syncTemplates_sendsAllTemplatesAndTracksVersions() {
            TemplateDefinition def1 = createDefinition("template-a", "1.0.0");
            TemplateDefinition def2 = createDefinition("template-b", "2.0.0");
            syncService = createSyncService(List.of(def1, def2));

            // Configure API to return success for both
            capturingService.importResponse = new ImportTemplatesResponse(List.of(
                    new ImportTemplateResultDto("template-a", ImportTemplateResultDto.Status.CREATED, "1.0.0", List.of("production"), null),
                    new ImportTemplateResultDto("template-b", ImportTemplateResultDto.Status.CREATED, "2.0.0", List.of("production"), null)
            ));

            EpistolaTemplateSyncService.SyncResult result =
                    syncService.syncTemplates(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID);

            assertEquals(2, result.totalTemplates());
            assertEquals(2, result.successCount());
            assertEquals(0, result.failCount());
            assertTrue(result.isFullySuccessful());

            // Verify the import request was sent
            assertNotNull(capturingService.lastImportRequest);
            assertEquals(2, capturingService.lastImportRequest.getTemplates().size());
        }

        @Test
        void syncTemplates_passesCorrectCredentials() {
            syncService = createSyncService(List.of(createDefinition("test", "1.0.0")));
            capturingService.importResponse = new ImportTemplatesResponse(List.of(
                    new ImportTemplateResultDto("test", ImportTemplateResultDto.Status.CREATED, "1.0.0", List.of(), null)
            ));

            syncService.syncTemplates(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID);

            assertEquals(BASE_URL, capturingService.lastBaseUrl);
            assertEquals(API_KEY, capturingService.lastApiKey);
            assertEquals(TENANT_ID, capturingService.lastTenantId);
        }
    }

    @Nested
    class WhenVersionsHaveNotChanged {

        @Test
        void syncTemplates_skipsImportApiCall() {
            TemplateDefinition def = createDefinition("template-a", "1.0.0");
            syncService = createSyncService(List.of(def));

            // First sync — deploys the template
            capturingService.importResponse = new ImportTemplatesResponse(List.of(
                    new ImportTemplateResultDto("template-a", ImportTemplateResultDto.Status.CREATED, "1.0.0", List.of("production"), null)
            ));
            syncService.syncTemplates(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID);

            // Reset capture
            capturingService.lastImportRequest = null;

            // Second sync — same version, should skip
            EpistolaTemplateSyncService.SyncResult result =
                    syncService.syncTemplates(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID);

            assertEquals(1, result.totalTemplates());
            assertEquals(0, result.successCount());
            assertEquals(0, result.failCount());
            assertNull(capturingService.lastImportRequest, "Should not call import API when no changes");
        }
    }

    @Nested
    class WhenVersionChanged {

        @Test
        void syncTemplates_onlySendsChangedTemplateWhenVersionBumped() {
            // Use a mutable scanner so we can swap definitions between syncs
            MutableScanner mutableScanner = new MutableScanner();
            TemplateDefinition defA = createDefinition("template-a", "1.0.0");
            TemplateDefinition defB = createDefinition("template-b", "1.0.0");
            mutableScanner.definitions = List.of(defA, defB);

            syncService = new EpistolaTemplateSyncService(mutableScanner, capturingService, objectMapper);

            // First sync — both templates are new
            capturingService.importResponse = new ImportTemplatesResponse(List.of(
                    new ImportTemplateResultDto("template-a", ImportTemplateResultDto.Status.CREATED, "1.0.0", List.of(), null),
                    new ImportTemplateResultDto("template-b", ImportTemplateResultDto.Status.CREATED, "1.0.0", List.of(), null)
            ));
            syncService.syncTemplates(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID);

            // Now bump only template-a to 2.0.0
            TemplateDefinition defAv2 = createDefinition("template-a", "2.0.0");
            mutableScanner.definitions = List.of(defAv2, defB);

            capturingService.lastImportRequest = null;
            capturingService.importResponse = new ImportTemplatesResponse(List.of(
                    new ImportTemplateResultDto("template-a", ImportTemplateResultDto.Status.UPDATED, "2.0.0", List.of(), null)
            ));

            EpistolaTemplateSyncService.SyncResult result =
                    syncService.syncTemplates(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID);

            // Only template-a should be sent
            assertNotNull(capturingService.lastImportRequest);
            assertEquals(1, capturingService.lastImportRequest.getTemplates().size());
            assertEquals("template-a", capturingService.lastImportRequest.getTemplates().get(0).getSlug());
            assertEquals(1, result.successCount());
        }

        @Test
        void syncTemplates_updatesVersionTrackingAfterSuccess() {
            TemplateDefinition def = createDefinition("template-a", "1.0.0");
            syncService = createSyncService(List.of(def));

            // First sync
            capturingService.importResponse = new ImportTemplatesResponse(List.of(
                    new ImportTemplateResultDto("template-a", ImportTemplateResultDto.Status.CREATED, "1.0.0", List.of(), null)
            ));
            EpistolaTemplateSyncService.SyncResult result1 =
                    syncService.syncTemplates(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID);
            assertEquals(1, result1.successCount());

            // Same version again — should be skipped
            capturingService.lastImportRequest = null;
            EpistolaTemplateSyncService.SyncResult result2 =
                    syncService.syncTemplates(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID);
            assertEquals(0, result2.successCount());
            assertNull(capturingService.lastImportRequest);
        }
    }

    @Nested
    class WhenImportPartiallyFails {

        @Test
        void syncTemplates_trackSuccessfulButNotFailedVersions() {
            TemplateDefinition defA = createDefinition("template-a", "1.0.0");
            TemplateDefinition defB = createDefinition("template-b", "1.0.0");
            syncService = createSyncService(List.of(defA, defB));

            // template-a succeeds, template-b fails
            capturingService.importResponse = new ImportTemplatesResponse(List.of(
                    new ImportTemplateResultDto("template-a", ImportTemplateResultDto.Status.CREATED, "1.0.0", List.of("production"), null),
                    new ImportTemplateResultDto("template-b", ImportTemplateResultDto.Status.FAILED, "1.0.0", null, "Server error")
            ));

            EpistolaTemplateSyncService.SyncResult result =
                    syncService.syncTemplates(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID);

            assertEquals(2, result.totalTemplates());
            assertEquals(1, result.successCount());
            assertEquals(1, result.failCount());
            assertFalse(result.isFullySuccessful());

            // On retry, only template-b should be sent (template-a version is tracked as deployed)
            capturingService.lastImportRequest = null;
            capturingService.importResponse = new ImportTemplatesResponse(List.of(
                    new ImportTemplateResultDto("template-b", ImportTemplateResultDto.Status.CREATED, "1.0.0", List.of("production"), null)
            ));

            EpistolaTemplateSyncService.SyncResult retryResult =
                    syncService.syncTemplates(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID);

            assertNotNull(capturingService.lastImportRequest);
            assertEquals(1, capturingService.lastImportRequest.getTemplates().size());
            assertEquals("template-b", capturingService.lastImportRequest.getTemplates().get(0).getSlug());
            assertEquals(1, retryResult.successCount());
        }
    }

    @Nested
    class VersionTrackingPerConfig {

        @Test
        void syncTemplates_tracksVersionsSeparatelyPerConfigId() {
            TemplateDefinition def = createDefinition("template-a", "1.0.0");
            syncService = createSyncService(List.of(def));

            capturingService.importResponse = new ImportTemplatesResponse(List.of(
                    new ImportTemplateResultDto("template-a", ImportTemplateResultDto.Status.CREATED, "1.0.0", List.of(), null)
            ));

            // Sync for config-1
            syncService.syncTemplates("config-1", BASE_URL, API_KEY, TENANT_ID);

            // Sync for config-2 — should still send (different config, no deployed versions)
            capturingService.lastImportRequest = null;
            EpistolaTemplateSyncService.SyncResult result =
                    syncService.syncTemplates("config-2", BASE_URL, API_KEY, TENANT_ID);

            assertNotNull(capturingService.lastImportRequest, "Should import for different config ID");
            assertEquals(1, result.successCount());
        }
    }

    // --- Test helpers ---

    /**
     * Scanner that returns a fixed list of definitions (no classpath scanning).
     */
    private static class FixedScanner extends TemplateDefinitionScanner {
        private final List<TemplateDefinition> definitions;

        FixedScanner(List<TemplateDefinition> definitions) {
            super(new ObjectMapper());
            this.definitions = definitions;
        }

        @Override
        public List<TemplateDefinition> scanTemplateDefinitions() {
            return definitions;
        }
    }

    /**
     * Scanner with mutable definitions list for testing version changes within same service instance.
     */
    private static class MutableScanner extends TemplateDefinitionScanner {
        List<TemplateDefinition> definitions = List.of();

        MutableScanner() {
            super(new ObjectMapper());
        }

        @Override
        public List<TemplateDefinition> scanTemplateDefinitions() {
            return definitions;
        }
    }

    /**
     * Minimal EpistolaService implementation that captures import calls.
     */
    private static class CapturingEpistolaService implements EpistolaService {
        ImportTemplatesRequest lastImportRequest;
        String lastBaseUrl;
        String lastApiKey;
        String lastTenantId;
        ImportTemplatesResponse importResponse;

        @Override
        public ImportTemplatesResponse importTemplates(String baseUrl, String apiKey, String tenantId, ImportTemplatesRequest request) {
            this.lastBaseUrl = baseUrl;
            this.lastApiKey = apiKey;
            this.lastTenantId = tenantId;
            this.lastImportRequest = request;
            return importResponse;
        }

        @Override
        public List<TemplateInfo> getTemplates(String baseUrl, String apiKey, String tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TemplateDetails getTemplateDetails(String baseUrl, String apiKey, String tenantId, String templateId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<EnvironmentInfo> getEnvironments(String baseUrl, String apiKey, String tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<VariantInfo> getVariants(String baseUrl, String apiKey, String tenantId, String templateId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GenerationJobResult submitGenerationJob(String baseUrl, String apiKey, String tenantId,
                String templateId, String variantId, List<VariantSelectionAttribute> variantAttributes,
                String environmentId, Map<String, Object> data, FileFormat format,
                String filename, String correlationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GenerationJobDetail getJobStatus(String baseUrl, String apiKey, String tenantId, String requestId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] downloadDocument(String baseUrl, String apiKey, String tenantId, String documentId) {
            throw new UnsupportedOperationException();
        }
    }
}
