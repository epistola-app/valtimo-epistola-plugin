package app.epistola.valtimo.deploy;

import app.epistola.client.model.DataExampleDto;
import app.epistola.client.model.ImportTemplateDto;
import app.epistola.client.model.ImportTemplateResultDto;
import app.epistola.client.model.ImportTemplatesRequest;
import app.epistola.client.model.ImportTemplatesResponse;
import app.epistola.client.model.ImportVariantDto;
import app.epistola.client.model.TemplateDocumentDto;
import app.epistola.valtimo.service.EpistolaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Synchronizes template definitions from classpath to Epistola.
 * <p>
 * Compares template versions from classpath definition files against previously
 * deployed versions. Only changed templates are sent to the import API.
 * <p>
 * Deployed versions are tracked in memory per plugin configuration. On restart,
 * all templates are re-imported (idempotent — the import API handles create-or-update).
 */
@Slf4j
public class EpistolaTemplateSyncService {

    private final TemplateDefinitionScanner scanner;
    private final EpistolaService epistolaService;
    private final ObjectMapper objectMapper;

    /**
     * Tracks deployed versions per plugin configuration ID.
     * Key: plugin configuration ID, Value: map of slug → version
     */
    private final Map<String, Map<String, String>> deployedVersions = new HashMap<>();

    public EpistolaTemplateSyncService(
            TemplateDefinitionScanner scanner,
            EpistolaService epistolaService,
            ObjectMapper objectMapper
    ) {
        this.scanner = scanner;
        this.epistolaService = epistolaService;
        this.objectMapper = objectMapper;
    }

    /**
     * Perform template synchronization for a specific plugin configuration.
     *
     * @param configId  The plugin configuration ID (for version tracking)
     * @param baseUrl   The Epistola API base URL
     * @param apiKey    The API key for authentication
     * @param tenantId  The tenant ID in Epistola
     * @return Sync result summary
     */
    public SyncResult syncTemplates(String configId, String baseUrl, String apiKey, String tenantId) {
        List<TemplateDefinition> allDefinitions = scanner.scanTemplateDefinitions();

        if (allDefinitions.isEmpty()) {
            log.info("No template definitions found on classpath, nothing to sync");
            return new SyncResult(0, 0, 0, List.of());
        }

        // Determine which templates have changed
        Map<String, String> previousVersions = deployedVersions.getOrDefault(configId, Map.of());
        List<TemplateDefinition> changedTemplates = allDefinitions.stream()
                .filter(def -> {
                    String previousVersion = previousVersions.get(def.slug());
                    return !def.version().equals(previousVersion);
                })
                .toList();

        if (changedTemplates.isEmpty()) {
            log.info("All {} templates are up-to-date, nothing to sync", allDefinitions.size());
            return new SyncResult(allDefinitions.size(), 0, 0, List.of());
        }

        log.info("Syncing {} changed templates (out of {} total) to tenant '{}'",
                changedTemplates.size(), allDefinitions.size(), tenantId);

        // Build import request
        List<ImportTemplateDto> importDtos = changedTemplates.stream()
                .map(this::toImportDto)
                .toList();

        ImportTemplatesRequest request = new ImportTemplatesRequest(importDtos);

        // Call import API
        ImportTemplatesResponse response = epistolaService.importTemplates(baseUrl, apiKey, tenantId, request);

        // Track deployed versions for successful imports
        Map<String, String> updatedVersions = new HashMap<>(previousVersions);
        int successCount = 0;
        int failCount = 0;

        for (ImportTemplateResultDto result : response.getResults()) {
            if (result.getStatus() == ImportTemplateResultDto.Status.FAILED) {
                log.error("Template import failed for '{}': {}", result.getSlug(), result.getErrorMessage());
                failCount++;
            } else {
                updatedVersions.put(result.getSlug(), result.getVersion());
                successCount++;
                log.info("Template '{}' imported: status={}, publishedTo={}",
                        result.getSlug(), result.getStatus(), result.getPublishedTo());
            }
        }

        deployedVersions.put(configId, updatedVersions);

        return new SyncResult(allDefinitions.size(), successCount, failCount, response.getResults());
    }

    private ImportTemplateDto toImportDto(TemplateDefinition def) {
        // Convert templateModel JsonNode to TemplateDocumentDto
        TemplateDocumentDto templateModel = objectMapper.convertValue(def.templateModel(), TemplateDocumentDto.class);

        // Convert data examples
        List<DataExampleDto> dataExamples = def.dataExamples().stream()
                .map(ex -> new DataExampleDto(
                        ex.id(),
                        ex.name(),
                        objectMapper.convertValue(ex.data(), Object.class)
                ))
                .toList();

        // Convert variants
        List<ImportVariantDto> variants = def.variants().stream()
                .map(v -> new ImportVariantDto(
                        v.id(),
                        v.title(),
                        v.attributes(),
                        v.templateModel() != null
                                ? objectMapper.convertValue(v.templateModel(), TemplateDocumentDto.class)
                                : null
                ))
                .toList();

        // Convert dataModel
        Object dataModel = def.dataModel() != null
                ? objectMapper.convertValue(def.dataModel(), Object.class)
                : null;

        return new ImportTemplateDto(
                def.slug(),
                def.name(),
                def.version(),
                templateModel,
                dataModel,
                dataExamples.isEmpty() ? null : dataExamples,
                variants.isEmpty() ? null : variants,
                def.publishTo().isEmpty() ? null : def.publishTo()
        );
    }

    /**
     * Result of a template sync operation.
     */
    public record SyncResult(
            int totalTemplates,
            int successCount,
            int failCount,
            List<ImportTemplateResultDto> details
    ) {
        public boolean isFullySuccessful() {
            return failCount == 0;
        }
    }
}
