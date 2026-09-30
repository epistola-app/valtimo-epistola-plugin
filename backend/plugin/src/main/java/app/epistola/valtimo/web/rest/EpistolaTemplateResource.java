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
package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.domain.AttributeDefinition;
import app.epistola.valtimo.domain.CatalogInfo;
import app.epistola.valtimo.domain.EnvironmentInfo;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateInfo;
import app.epistola.valtimo.domain.VariantInfo;
import app.epistola.valtimo.service.EpistolaApiException;
import app.epistola.valtimo.service.EpistolaService;
import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.contract.annotation.SkipComponentScan;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * REST controller for Epistola template browsing operations.
 * Provides endpoints for fetching catalogs, templates, attributes, environments, and variants.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/plugin/epistola")
@SkipComponentScan
@RequiredArgsConstructor
public class EpistolaTemplateResource {

    private final PluginService pluginService;
    private final EpistolaService epistolaService;

    /**
     * The Epistola plugin configurations an author can choose from.
     *
     * <p>Needed wherever there is no process-link context to inherit one from — the letter
     * composer's settings live in a form, not on a service task, so the author picks the
     * connection themselves. Deliberately lighter than {@code /admin/health}: it lists what is
     * configured without contacting Epistola.
     *
     * @return id, title and tenant of every configured Epistola connection
     */
    @GetMapping("/configurations")
    public ResponseEntity<List<PluginConfigurationInfo>> getConfigurations() {
        List<PluginConfigurationInfo> configurations = pluginService
                .findPluginConfigurations(EpistolaPlugin.class, properties -> true)
                .stream()
                .map(PluginConfiguration.class::cast)
                .map(configuration -> {
                    String tenantId = null;
                    try {
                        tenantId = ((EpistolaPlugin) pluginService.createInstance(configuration)).getTenantId();
                    } catch (Exception e) {
                        // A configuration that cannot be instantiated (bad credentials, missing
                        // property) must still be listable, or an author cannot select and fix it.
                        log.debug("Could not read tenant of configuration {}: {}",
                                configuration.getId(), e.getMessage());
                    }
                    return new PluginConfigurationInfo(
                            configuration.getId().toString(),
                            configuration.getTitle(),
                            tenantId);
                })
                .toList();

        return ResponseEntity.ok(configurations);
    }

    /** One configured Epistola connection, as offered to an author. */
    public record PluginConfigurationInfo(String id, String title, String tenantId) {
    }

    /**
     * Get all available catalogs for a plugin configuration.
     *
     * @param configurationId The plugin configuration ID
     * @return List of available catalogs
     */
    @GetMapping("/configurations/{configurationId}/catalogs")
    public ResponseEntity<List<CatalogInfo>> getCatalogs(
            @PathVariable("configurationId") UUID configurationId
    ) {
        log.debug("Fetching catalogs for plugin configuration: {}", configurationId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        List<CatalogInfo> catalogs = epistolaService.getCatalogs(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId()
        );

        return ResponseEntity.ok(catalogs);
    }

    /**
     * Get all available templates for a plugin configuration.
     *
     * @param configurationId The plugin configuration ID
     * @param catalogId       The catalog ID
     * @return List of available templates
     */
    @GetMapping("/configurations/{configurationId}/templates")
    public ResponseEntity<List<TemplateInfo>> getTemplates(
            @PathVariable("configurationId") UUID configurationId,
            @RequestParam("catalogId") String catalogId
    ) {
        log.debug("Fetching templates for plugin configuration: {}, catalog: {}", configurationId, catalogId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        return okOrNotFound(() -> epistolaService.getTemplates(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId(),
                catalogId
        ));
    }

    /**
     * Get template details including its fields.
     *
     * @param configurationId The plugin configuration ID
     * @param catalogId       The catalog ID
     * @param templateId      The template ID
     * @return Template details with fields
     */
    @GetMapping("/configurations/{configurationId}/templates/{templateId}")
    public ResponseEntity<TemplateDetails> getTemplateDetails(
            @PathVariable("configurationId") UUID configurationId,
            @PathVariable("templateId") String templateId,
            @RequestParam("catalogId") String catalogId
    ) {
        log.debug("Fetching template details for plugin configuration: {}, catalog: {}, template: {}",
                configurationId, catalogId, templateId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        return okOrNotFound(() -> epistolaService.getTemplateDetails(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId(),
                catalogId,
                templateId
        ));
    }

    /**
     * Get all attribute definitions for a plugin configuration's tenant and catalog.
     * These define the keys that can be used for variant selection.
     *
     * @param configurationId The plugin configuration ID
     * @param catalogId       The catalog ID
     * @return List of attribute definitions
     */
    @GetMapping("/configurations/{configurationId}/attributes")
    public ResponseEntity<List<AttributeDefinition>> getAttributes(
            @PathVariable("configurationId") UUID configurationId,
            @RequestParam("catalogId") String catalogId
    ) {
        log.debug("Fetching attribute definitions for plugin configuration: {}, catalog: {}", configurationId, catalogId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        return okOrNotFound(() -> epistolaService.getAttributes(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId(),
                catalogId
        ));
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
     * @param catalogId       The catalog ID
     * @param templateId      The template ID
     * @return List of variants for the template
     */
    @GetMapping("/configurations/{configurationId}/templates/{templateId}/variants")
    public ResponseEntity<List<VariantInfo>> getVariants(
            @PathVariable("configurationId") UUID configurationId,
            @PathVariable("templateId") String templateId,
            @RequestParam("catalogId") String catalogId
    ) {
        log.debug("Fetching variants for plugin configuration: {}, catalog: {}, template: {}",
                configurationId, catalogId, templateId);

        EpistolaPlugin plugin = pluginService.createInstance(configurationId);
        return okOrNotFound(() -> epistolaService.getVariants(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId(),
                catalogId,
                templateId
        ));
    }

    /**
     * Answers with the lookup's result, or 404 when Epistola reports the catalog or template as
     * missing. That is a stale selection — a template looked up in a catalog it is not in — rather
     * than a server fault, so it must not surface as a 500. Every other failure propagates.
     */
    private static <T> ResponseEntity<T> okOrNotFound(Supplier<T> lookup) {
        try {
            return ResponseEntity.ok(lookup.get());
        } catch (EpistolaApiException e) {
            if (Integer.valueOf(HttpStatus.NOT_FOUND.value()).equals(e.getHttpStatus())) {
                log.debug("Epistola reported the requested resource as not found: {}", e.getMessage());
                return ResponseEntity.notFound().build();
            }
            throw e;
        }
    }
}
