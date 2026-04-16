package app.epistola.valtimo.deploy;

import app.epistola.valtimo.service.EpistolaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpistolaCatalogSyncServiceTest {

    @Mock
    private EpistolaService epistolaService;

    private CatalogScanner scanner;
    private EpistolaCatalogSyncService syncService;

    private static final String CONFIG_ID = "config-1";
    private static final String BASE_URL = "https://api.epistola.app";
    private static final String API_KEY = "secret-key";
    private static final String TENANT_ID = "tenant-1";
    private static final String CATALOG_TYPE = "full";

    @BeforeEach
    void setUp() {
        scanner = new CatalogScanner(new ObjectMapper());
        syncService = new EpistolaCatalogSyncService(scanner, epistolaService);
    }

    @Nested
    class SyncChangedCatalogs {

        @Test
        void syncsWhenVersionDoesNotMatchPreviouslyDeployed() {
            when(epistolaService.importCatalog(eq(BASE_URL), eq(API_KEY), eq(TENANT_ID), any(byte[].class), eq(CATALOG_TYPE)))
                    .thenReturn(new EpistolaService.ImportCatalogResult("test-catalog", "Test Catalog", 1, 0, 0, 1));

            EpistolaCatalogSyncService.SyncResult result = syncService.syncCatalogs(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID, CATALOG_TYPE);

            assertThat(result.successCount()).isGreaterThanOrEqualTo(1);
            assertThat(result.failCount()).isZero();
            assertThat(result.totalCatalogs()).isGreaterThanOrEqualTo(1);
            verify(epistolaService).importCatalog(eq(BASE_URL), eq(API_KEY), eq(TENANT_ID), any(byte[].class), eq(CATALOG_TYPE));
        }
    }

    @Nested
    class SkipUnchangedCatalogs {

        @Test
        void skipsWhenVersionMatchesPreviouslyDeployed() {
            // First sync to populate deployed versions
            when(epistolaService.importCatalog(anyString(), anyString(), anyString(), any(byte[].class), anyString()))
                    .thenReturn(new EpistolaService.ImportCatalogResult("test-catalog", "Test Catalog", 1, 0, 0, 1));

            syncService.syncCatalogs(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID, CATALOG_TYPE);

            // Reset mock to track only second invocation
            org.mockito.Mockito.reset(epistolaService);

            // Second sync — version hasn't changed, should skip
            EpistolaCatalogSyncService.SyncResult result = syncService.syncCatalogs(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID, CATALOG_TYPE);

            assertThat(result.successCount()).isZero();
            assertThat(result.failCount()).isZero();
            verify(epistolaService, never()).importCatalog(anyString(), anyString(), anyString(), any(byte[].class), anyString());
        }
    }

    @Nested
    class BuildZipFromClasspathResources {

        @Test
        void zipContainsCatalogJsonAndResourceFiles() throws IOException {
            // Use the scanner to find our test catalog
            CatalogScanner.CatalogOnClasspath testCatalog = scanner.scan().stream()
                    .filter(c -> "test-catalog".equals(c.slug()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("test-catalog not found on classpath"));

            byte[] zipBytes = syncService.buildCatalogZip(testCatalog);

            assertThat(zipBytes).isNotEmpty();

            // Parse ZIP and verify entries
            Map<String, byte[]> entries = readZipEntries(zipBytes);

            assertThat(entries).containsKey("catalog.json");
            assertThat(entries).containsKey("resources/template/test-template.json");

            // Verify catalog.json content is valid JSON with expected slug
            ObjectMapper mapper = new ObjectMapper();
            var catalogNode = mapper.readTree(entries.get("catalog.json"));
            assertThat(catalogNode.path("catalog").path("slug").asText()).isEqualTo("test-catalog");

            // Verify template resource is valid JSON
            var templateNode = mapper.readTree(entries.get("resources/template/test-template.json"));
            assertThat(templateNode.path("resource").path("slug").asText()).isEqualTo("test-template");
        }
    }

    @Nested
    class HandlePartialFailures {

        @Test
        void tracksVersionOnlyForSuccessfulSyncs() {
            // The test classpath has at least one catalog. We simulate failure for it.
            when(epistolaService.importCatalog(anyString(), anyString(), anyString(), any(byte[].class), anyString()))
                    .thenThrow(new RuntimeException("API error"));

            EpistolaCatalogSyncService.SyncResult result = syncService.syncCatalogs(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID, CATALOG_TYPE);

            assertThat(result.failCount()).isGreaterThanOrEqualTo(1);
            assertThat(result.successCount()).isZero();
            assertThat(result.isFullySuccessful()).isFalse();

            // Since the sync failed, a retry should attempt the catalog again (not skip it)
            org.mockito.Mockito.reset(epistolaService);
            when(epistolaService.importCatalog(anyString(), anyString(), anyString(), any(byte[].class), anyString()))
                    .thenReturn(new EpistolaService.ImportCatalogResult("test-catalog", "Test Catalog", 1, 0, 0, 1));

            EpistolaCatalogSyncService.SyncResult retryResult = syncService.syncCatalogs(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID, CATALOG_TYPE);

            assertThat(retryResult.successCount()).isGreaterThanOrEqualTo(1);
            assertThat(retryResult.failCount()).isZero();
            assertThat(retryResult.isFullySuccessful()).isTrue();
        }
    }

    @Nested
    class TrackDeployedVersions {

        @Test
        void tracksVersionAfterSuccessfulSync() {
            when(epistolaService.importCatalog(anyString(), anyString(), anyString(), any(byte[].class), anyString()))
                    .thenReturn(new EpistolaService.ImportCatalogResult("test-catalog", "Test Catalog", 1, 0, 0, 1));

            EpistolaCatalogSyncService.SyncResult firstResult = syncService.syncCatalogs(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID, CATALOG_TYPE);
            assertThat(firstResult.successCount()).isGreaterThanOrEqualTo(1);

            // Second sync should skip all catalogs since versions match
            org.mockito.Mockito.reset(epistolaService);
            EpistolaCatalogSyncService.SyncResult secondResult = syncService.syncCatalogs(CONFIG_ID, BASE_URL, API_KEY, TENANT_ID, CATALOG_TYPE);

            assertThat(secondResult.successCount()).isZero();
            assertThat(secondResult.failCount()).isZero();
            verify(epistolaService, never()).importCatalog(anyString(), anyString(), anyString(), any(byte[].class), anyString());
        }

        @Test
        void tracksSeparateVersionsPerConfigId() {
            when(epistolaService.importCatalog(anyString(), anyString(), anyString(), any(byte[].class), anyString()))
                    .thenReturn(new EpistolaService.ImportCatalogResult("test-catalog", "Test Catalog", 1, 0, 0, 1));

            // Sync for config-1
            syncService.syncCatalogs("config-1", BASE_URL, API_KEY, TENANT_ID, CATALOG_TYPE);

            // Sync for config-2 should still import (separate version tracking)
            EpistolaCatalogSyncService.SyncResult config2Result = syncService.syncCatalogs("config-2", BASE_URL, API_KEY, TENANT_ID, CATALOG_TYPE);

            assertThat(config2Result.successCount()).isGreaterThanOrEqualTo(1);
        }
    }

    // ---- Helpers ----

    private Map<String, byte[]> readZipEntries(byte[] zipBytes) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
                zis.closeEntry();
            }
        }
        return entries;
    }
}
