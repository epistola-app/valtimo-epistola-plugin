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
import app.epistola.valtimo.service.EpistolaService;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EpistolaTemplateResource}. Each endpoint resolves the plugin configuration
 * and delegates to {@link EpistolaService} with the plugin's connection details — these tests pin
 * that delegation (correct base URL / api key / tenant + path/query params) and the 200 passthrough.
 */
class EpistolaTemplateResourceTest {

    private static final UUID CONFIG_ID = UUID.randomUUID();
    private static final String BASE_URL = "https://api.epistola.app";
    private static final String API_KEY = "api-key";
    private static final String TENANT_ID = "demo";
    private static final String CATALOG_ID = "cat-1";
    private static final String TEMPLATE_ID = "tpl-1";

    private PluginService pluginService;
    private EpistolaService epistolaService;
    private EpistolaTemplateResource resource;

    @BeforeEach
    void setUp() {
        pluginService = mock(PluginService.class);
        epistolaService = mock(EpistolaService.class);
        EpistolaPlugin plugin = mock(EpistolaPlugin.class);
        when(plugin.getBaseUrl()).thenReturn(BASE_URL);
        when(plugin.getApiKey()).thenReturn(API_KEY);
        when(plugin.getTenantId()).thenReturn(TENANT_ID);
        when(pluginService.createInstance(any(UUID.class))).thenReturn(plugin);
        resource = new EpistolaTemplateResource(pluginService, epistolaService);
    }

    @Test
    void getCatalogs_delegatesWithConnectionDetails() {
        List<CatalogInfo> catalogs = List.of();
        when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID)).thenReturn(catalogs);

        ResponseEntity<List<CatalogInfo>> response = resource.getCatalogs(CONFIG_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(catalogs);
        verify(epistolaService).getCatalogs(BASE_URL, API_KEY, TENANT_ID);
    }

    @Test
    void getTemplates_passesCatalogId() {
        List<TemplateInfo> templates = List.of();
        when(epistolaService.getTemplates(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID)).thenReturn(templates);

        ResponseEntity<List<TemplateInfo>> response = resource.getTemplates(CONFIG_ID, CATALOG_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(templates);
        verify(epistolaService).getTemplates(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID);
    }

    @Test
    void getTemplateDetails_passesCatalogAndTemplateId() {
        TemplateDetails details = mock(TemplateDetails.class);
        when(epistolaService.getTemplateDetails(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, TEMPLATE_ID))
                .thenReturn(details);

        ResponseEntity<TemplateDetails> response = resource.getTemplateDetails(CONFIG_ID, TEMPLATE_ID, CATALOG_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(details);
        verify(epistolaService).getTemplateDetails(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, TEMPLATE_ID);
    }

    @Test
    void getAttributes_passesCatalogId() {
        List<AttributeDefinition> attributes = List.of();
        when(epistolaService.getAttributes(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID)).thenReturn(attributes);

        ResponseEntity<List<AttributeDefinition>> response = resource.getAttributes(CONFIG_ID, CATALOG_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(attributes);
        verify(epistolaService).getAttributes(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID);
    }

    @Test
    void getEnvironments_delegatesWithConnectionDetails() {
        List<EnvironmentInfo> environments = List.of();
        when(epistolaService.getEnvironments(BASE_URL, API_KEY, TENANT_ID)).thenReturn(environments);

        ResponseEntity<List<EnvironmentInfo>> response = resource.getEnvironments(CONFIG_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(environments);
        verify(epistolaService).getEnvironments(BASE_URL, API_KEY, TENANT_ID);
    }

    @Test
    void getVariants_passesCatalogAndTemplateId() {
        List<VariantInfo> variants = List.of();
        when(epistolaService.getVariants(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, TEMPLATE_ID))
                .thenReturn(variants);

        ResponseEntity<List<VariantInfo>> response = resource.getVariants(CONFIG_ID, TEMPLATE_ID, CATALOG_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(variants);
        verify(epistolaService).getVariants(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, TEMPLATE_ID);
    }
}
