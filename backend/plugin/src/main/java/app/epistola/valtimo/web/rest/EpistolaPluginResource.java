package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.domain.EnvironmentInfo;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateInfo;
import app.epistola.valtimo.domain.VariantInfo;
import app.epistola.valtimo.service.EpistolaService;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.contract.annotation.SkipComponentScan;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for Epistola plugin operations.
 * Provides endpoints for fetching templates and template details.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/plugin/epistola")
@SkipComponentScan
@RequiredArgsConstructor
public class EpistolaPluginResource {

    private final PluginService pluginService;
    private final EpistolaService epistolaService;

    /**
     * Get all available templates for a plugin configuration.
     *
     * @param configurationId The plugin configuration ID
     * @return List of available templates
     */
    @GetMapping("/configurations/{configurationId}/templates")
    public ResponseEntity<List<TemplateInfo>> getTemplates(
            @PathVariable("configurationId") UUID configurationId
    ) {
        log.debug("Fetching templates for plugin configuration: {}", configurationId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        List<TemplateInfo> templates = epistolaService.getTemplates(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId()
        );

        return ResponseEntity.ok(templates);
    }

    /**
     * Get template details including its fields.
     *
     * @param configurationId The plugin configuration ID
     * @param templateId      The template ID
     * @return Template details with fields
     */
    @GetMapping("/configurations/{configurationId}/templates/{templateId}")
    public ResponseEntity<TemplateDetails> getTemplateDetails(
            @PathVariable("configurationId") UUID configurationId,
            @PathVariable("templateId") String templateId
    ) {
        log.debug("Fetching template details for plugin configuration: {}, template: {}",
                configurationId, templateId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        TemplateDetails templateDetails = epistolaService.getTemplateDetails(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId(),
                templateId
        );

        return ResponseEntity.ok(templateDetails);
    }

    /**
     * Get all available environments for a plugin configuration.
     *
     * @param configurationId The plugin configuration ID
     * @return List of available environments
     */
    @GetMapping("/configurations/{configurationId}/environments")
    public ResponseEntity<List<EnvironmentInfo>> getEnvironments(
            @PathVariable("configurationId") UUID configurationId
    ) {
        log.debug("Fetching environments for plugin configuration: {}", configurationId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        List<EnvironmentInfo> environments = epistolaService.getEnvironments(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId()
        );

        return ResponseEntity.ok(environments);
    }

    /**
     * Get all variants for a specific template.
     *
     * @param configurationId The plugin configuration ID
     * @param templateId      The template ID
     * @return List of variants for the template
     */
    @GetMapping("/configurations/{configurationId}/templates/{templateId}/variants")
    public ResponseEntity<List<VariantInfo>> getVariants(
            @PathVariable("configurationId") UUID configurationId,
            @PathVariable("templateId") String templateId
    ) {
        log.debug("Fetching variants for plugin configuration: {}, template: {}",
                configurationId, templateId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        List<VariantInfo> variants = epistolaService.getVariants(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId(),
                templateId
        );

        return ResponseEntity.ok(variants);
    }
}
