package app.epistola.valtimo.deploy;

import app.epistola.valtimo.service.EpistolaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Synchronizes catalog resources from classpath to Epistola via ZIP import.
 * <p>
 * For each catalog found by the {@link CatalogScanner}, compares the version against
 * previously deployed versions. If the version has changed (or this is the first deploy),
 * builds a ZIP archive from the classpath resources and POSTs it to Epistola's catalog
 * import endpoint.
 * <p>
 * Deployed versions are tracked in memory per plugin configuration. On restart,
 * all catalogs are re-imported (idempotent — the import API handles create-or-update).
 */
@Slf4j
public class EpistolaCatalogSyncService {

    private final CatalogScanner scanner;
    private final EpistolaService epistolaService;

    /**
     * Tracks deployed versions per plugin configuration ID.
     * Key: plugin configuration ID, Value: map of catalog slug to version.
     */
    private final Map<String, Map<String, String>> deployedVersions = new HashMap<>();

    public EpistolaCatalogSyncService(CatalogScanner scanner, EpistolaService epistolaService) {
        this.scanner = scanner;
        this.epistolaService = epistolaService;
    }

    /**
     * Perform catalog synchronization for a specific plugin configuration.
     *
     * @param configId    The plugin configuration ID (for version tracking)
     * @param baseUrl     The Epistola API base URL
     * @param apiKey      The API key for authentication
     * @param tenantId    The tenant ID in Epistola
     * @param catalogType The catalog type to pass to the import endpoint (e.g. "full", "templates-only")
     * @return Sync result summary
     */
    public SyncResult syncCatalogs(String configId, String baseUrl, String apiKey, String tenantId, String catalogType) {
        List<CatalogScanner.CatalogOnClasspath> allCatalogs = scanner.scan();

        if (allCatalogs.isEmpty()) {
            log.info("No catalogs found on classpath, nothing to sync");
            return new SyncResult(0, 0, 0);
        }

        // Determine which catalogs have changed
        Map<String, String> previousVersions = deployedVersions.getOrDefault(configId, Map.of());
        List<CatalogScanner.CatalogOnClasspath> changedCatalogs = allCatalogs.stream()
                .filter(cat -> {
                    String previousVersion = previousVersions.get(cat.slug());
                    return !cat.version().equals(previousVersion);
                })
                .toList();

        if (changedCatalogs.isEmpty()) {
            log.info("All {} catalogs are up-to-date, nothing to sync", allCatalogs.size());
            return new SyncResult(allCatalogs.size(), 0, 0);
        }

        log.info("Syncing {} changed catalogs (out of {} total) to tenant '{}'",
                changedCatalogs.size(), allCatalogs.size(), tenantId);

        Map<String, String> updatedVersions = new HashMap<>(previousVersions);
        int successCount = 0;
        int failCount = 0;

        for (CatalogScanner.CatalogOnClasspath catalog : changedCatalogs) {
            try {
                byte[] zipBytes = buildCatalogZip(catalog);

                EpistolaService.ImportCatalogResult result = epistolaService.importCatalog(
                        baseUrl, apiKey, tenantId, zipBytes, catalogType);

                updatedVersions.put(catalog.slug(), catalog.version());
                successCount++;
                log.info("Catalog '{}' v{} imported: key={}, installed={}, updated={}, failed={}, total={}",
                        catalog.slug(), catalog.version(),
                        result.catalogKey(), result.installed(), result.updated(),
                        result.failed(), result.total());
            } catch (Exception e) {
                failCount++;
                log.error("Failed to sync catalog '{}' v{}: {}",
                        catalog.slug(), catalog.version(), e.getMessage(), e);
            }
        }

        deployedVersions.put(configId, updatedVersions);

        return new SyncResult(allCatalogs.size(), successCount, failCount);
    }

    /**
     * Build a ZIP archive from a catalog's classpath resources.
     * <p>
     * Includes:
     * <ul>
     *   <li>{@code catalog.json} — the catalog manifest</li>
     *   <li>{@code resources/**&#47;*.json} — template and other resource files</li>
     * </ul>
     *
     * @param catalog The catalog metadata from the scanner
     * @return ZIP bytes ready for upload
     * @throws IOException if reading classpath resources fails
     */
    byte[] buildCatalogZip(CatalogScanner.CatalogOnClasspath catalog) throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // 1. Add catalog.json
            String catalogJsonPath = "classpath:" + catalog.basePath() + "/catalog.json";
            Resource catalogJson = resolver.getResource(catalogJsonPath);
            if (catalogJson.exists()) {
                addToZip(zos, "catalog.json", catalogJson);
            } else {
                throw new IOException("catalog.json not found at " + catalogJsonPath);
            }

            // 2. Add all resources/**/*.json files
            String resourcePattern = "classpath*:" + catalog.basePath() + "/resources/**/*.json";
            Resource[] resourceFiles;
            try {
                resourceFiles = resolver.getResources(resourcePattern);
            } catch (IOException e) {
                log.debug("No resource files found for catalog '{}': {}", catalog.slug(), e.getMessage());
                resourceFiles = new Resource[0];
            }

            String resourcePrefix = catalog.basePath() + "/resources/";
            for (Resource resource : resourceFiles) {
                String resourceUrl = resource.getURL().toString();
                int prefixIdx = resourceUrl.indexOf(resourcePrefix);
                if (prefixIdx >= 0) {
                    String relativePath = "resources/" + resourceUrl.substring(prefixIdx + resourcePrefix.length());
                    addToZip(zos, relativePath, resource);
                } else {
                    log.warn("Could not determine relative path for resource: {}", resource.getDescription());
                }
            }

            // 3. Scan for binary asset files (images, fonts, etc.) under resources/
            String assetPattern = "classpath*:" + catalog.basePath() + "/resources/**/*";
            Resource[] allResourceFiles;
            try {
                allResourceFiles = resolver.getResources(assetPattern);
            } catch (IOException e) {
                log.debug("No asset files found for catalog '{}': {}", catalog.slug(), e.getMessage());
                allResourceFiles = new Resource[0];
            }

            for (Resource resource : allResourceFiles) {
                // Skip JSON files (already added above) and directories
                String filename = resource.getFilename();
                if (filename == null || filename.endsWith(".json")) {
                    continue;
                }

                String resourceUrl = resource.getURL().toString();
                int prefixIdx = resourceUrl.indexOf(resourcePrefix);
                if (prefixIdx >= 0) {
                    String relativePath = "resources/" + resourceUrl.substring(prefixIdx + resourcePrefix.length());
                    addToZip(zos, relativePath, resource);
                }
            }
        }

        byte[] zipBytes = baos.toByteArray();
        log.debug("Built ZIP for catalog '{}': {} bytes", catalog.slug(), zipBytes.length);
        return zipBytes;
    }

    private void addToZip(ZipOutputStream zos, String entryName, Resource resource) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        try (InputStream is = resource.getInputStream()) {
            is.transferTo(zos);
        }
        zos.closeEntry();
    }

    /**
     * Result of a catalog sync operation.
     */
    public record SyncResult(
            int totalCatalogs,
            int successCount,
            int failCount
    ) {
        public boolean isFullySuccessful() {
            return failCount == 0;
        }
    }
}
