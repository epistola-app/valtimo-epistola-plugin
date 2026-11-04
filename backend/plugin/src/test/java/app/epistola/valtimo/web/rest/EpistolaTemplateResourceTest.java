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
import app.epistola.valtimo.domain.CatalogReleases;
import app.epistola.valtimo.domain.EnvironmentInfo;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateInfo;
import app.epistola.valtimo.domain.VariantInfo;
import app.epistola.valtimo.service.EpistolaApiException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private EpistolaPlugin plugin;
    private EpistolaService epistolaService;
    private EpistolaTemplateResource resource;

    @BeforeEach
    void setUp() {
        pluginService = mock(PluginService.class);
        epistolaService = mock(EpistolaService.class);
        plugin = mock(EpistolaPlugin.class);
        when(plugin.getBaseUrl()).thenReturn(BASE_URL);
        when(plugin.getApiKey()).thenReturn(API_KEY);
        when(plugin.getTenantId()).thenReturn(TENANT_ID);
        when(pluginService.createInstance(any(UUID.class))).thenReturn(plugin);
        resource = new EpistolaTemplateResource(pluginService, epistolaService);
    }

    @Test
    void getCatalogs_delegatesWithConnectionDetails() {
        List<CatalogInfo> catalogs = List.of();
        when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID, null)).thenReturn(catalogs);

        ResponseEntity<List<CatalogInfo>> response = resource.getCatalogs(CONFIG_ID, null, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(catalogs);
        verify(epistolaService).getCatalogs(BASE_URL, API_KEY, TENANT_ID, null);
    }

    @Test
    void getTemplates_passesCatalogId() {
        List<TemplateInfo> templates = List.of();
        when(epistolaService.getTemplates(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, null, null)).thenReturn(templates);

        ResponseEntity<List<TemplateInfo>> response = resource.getTemplates(CONFIG_ID, CATALOG_ID, null, false, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(templates);
        verify(epistolaService).getTemplates(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, null, null);
    }

    @Test
    void getTemplateDetails_passesCatalogAndTemplateId() {
        TemplateDetails details = mock(TemplateDetails.class);
        when(epistolaService.getTemplateDetails(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, TEMPLATE_ID, null, null))
                .thenReturn(details);

        ResponseEntity<TemplateDetails> response = resource.getTemplateDetails(CONFIG_ID, TEMPLATE_ID, CATALOG_ID, null, false, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(details);
        verify(epistolaService).getTemplateDetails(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, TEMPLATE_ID, null, null);
    }

    @Test
    void getAttributes_passesCatalogId() {
        List<AttributeDefinition> attributes = List.of();
        when(epistolaService.getAttributes(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, null, null)).thenReturn(attributes);

        ResponseEntity<List<AttributeDefinition>> response = resource.getAttributes(CONFIG_ID, CATALOG_ID, null, false, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(attributes);
        verify(epistolaService).getAttributes(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, null, null);
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
        when(epistolaService.getVariants(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, TEMPLATE_ID, null, null))
                .thenReturn(variants);

        ResponseEntity<List<VariantInfo>> response = resource.getVariants(CONFIG_ID, TEMPLATE_ID, CATALOG_ID, null, false, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(variants);
        verify(epistolaService).getVariants(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, TEMPLATE_ID, null, null);
    }

    @Test
    void getTemplateDetails_answersNotFoundWhenEpistolaReportsTheTemplateMissing() {
        // A stale selection — a template looked up in a catalog it is not in — is not a server fault.
        when(epistolaService.getTemplateDetails(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, TEMPLATE_ID, null, null))
                .thenThrow(upstream(404));

        ResponseEntity<TemplateDetails> response = resource.getTemplateDetails(CONFIG_ID, TEMPLATE_ID, CATALOG_ID, null, false, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getVariants_answersNotFoundWhenEpistolaReportsTheTemplateMissing() {
        when(epistolaService.getVariants(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, TEMPLATE_ID, null, null))
                .thenThrow(upstream(404));

        ResponseEntity<List<VariantInfo>> response = resource.getVariants(CONFIG_ID, TEMPLATE_ID, CATALOG_ID, null, false, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getTemplates_answersNotFoundWhenEpistolaReportsTheCatalogMissing() {
        when(epistolaService.getTemplates(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, null, null)).thenThrow(upstream(404));

        ResponseEntity<List<TemplateInfo>> response = resource.getTemplates(CONFIG_ID, CATALOG_ID, null, false, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getAttributes_answersNotFoundWhenEpistolaReportsTheCatalogMissing() {
        when(epistolaService.getAttributes(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, null, null)).thenThrow(upstream(404));

        ResponseEntity<List<AttributeDefinition>> response = resource.getAttributes(CONFIG_ID, CATALOG_ID, null, false, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getTemplateDetails_propagatesOtherFailures() {
        EpistolaApiException unavailable = upstream(503);
        when(epistolaService.getTemplateDetails(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, TEMPLATE_ID, null, null))
                .thenThrow(unavailable);

        assertThatThrownBy(() -> resource.getTemplateDetails(CONFIG_ID, TEMPLATE_ID, CATALOG_ID, null, false, null))
                .isSameAs(unavailable);
    }

    @Test
    void lookupsAreNarrowedToTheEnvironmentTheLinkNames() {
        when(plugin.getDefaultEnvironmentId()).thenReturn("acceptatie");

        resource.getCatalogs(CONFIG_ID, "productie", false);
        resource.getTemplates(CONFIG_ID, CATALOG_ID, "productie", false, null);

        verify(epistolaService).getCatalogs(BASE_URL, API_KEY, TENANT_ID, "productie");
        verify(epistolaService).getTemplates(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, "productie", null);
    }

    @Test
    void lookupsFallBackToTheConfigurationsDefaultEnvironment() {
        when(plugin.getDefaultEnvironmentId()).thenReturn("acceptatie");

        resource.getCatalogs(CONFIG_ID, null, false);
        resource.getVariants(CONFIG_ID, TEMPLATE_ID, CATALOG_ID, " ", false, null);

        verify(epistolaService).getCatalogs(BASE_URL, API_KEY, TENANT_ID, "acceptatie");
        verify(epistolaService).getVariants(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, TEMPLATE_ID, "acceptatie", null);
    }

    @Test
    void anEnvironmentOnlyKnownAtRunTimeNarrowsNothing() {
        // The link names its environment with an expression: no default stands in for it.
        when(plugin.getDefaultEnvironmentId()).thenReturn("acceptatie");

        resource.getCatalogs(CONFIG_ID, null, true);

        verify(epistolaService).getCatalogs(BASE_URL, API_KEY, TENANT_ID, null);
    }

    @Test
    void getCatalogs_answersNotFoundWhenTheEnvironmentDoesNotExist() {
        when(epistolaService.getCatalogs(BASE_URL, API_KEY, TENANT_ID, "weg")).thenThrow(upstream(404));

        assertThat(resource.getCatalogs(CONFIG_ID, "weg", false).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void lookupsReadFromTheReleaseTheLinksVersionRangeSelects() {
        resource.getTemplates(CONFIG_ID, CATALOG_ID, "prod", false, "^1.4.0");
        resource.getTemplateDetails(CONFIG_ID, TEMPLATE_ID, CATALOG_ID, "prod", false, "^1.4.0");
        resource.getAttributes(CONFIG_ID, CATALOG_ID, "prod", false, "^1.4.0");
        resource.getVariants(CONFIG_ID, TEMPLATE_ID, CATALOG_ID, "prod", false, "^1.4.0");

        verify(epistolaService).getTemplates(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, "prod", "^1.4.0");
        verify(epistolaService).getTemplateDetails(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, TEMPLATE_ID, "prod", "^1.4.0");
        verify(epistolaService).getAttributes(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, "prod", "^1.4.0");
        verify(epistolaService).getVariants(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, TEMPLATE_ID, "prod", "^1.4.0");
    }

    @Test
    void getReleases_answersWithTheVersionsAndTheOneTheRangeSelects() {
        CatalogReleases releases = new CatalogReleases(List.of("2.0.0", "1.9.0"), "1.9.0", false);
        when(plugin.getDefaultEnvironmentId()).thenReturn("prod");
        when(epistolaService.getReleases(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, "prod", "^1.0.0")).thenReturn(releases);

        ResponseEntity<CatalogReleases> response = resource.getReleases(CONFIG_ID, CATALOG_ID, null, false, "^1.0.0");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(releases);
    }

    @Test
    void aVersionRangeThatIsNotOne_isABadRequestRatherThanAServerFault() {
        when(epistolaService.getReleases(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, null, "latest")).thenThrow(upstream(400));
        when(epistolaService.getTemplates(BASE_URL, API_KEY, TENANT_ID, CATALOG_ID, null, "latest")).thenThrow(upstream(400));

        assertThat(resource.getReleases(CONFIG_ID, CATALOG_ID, null, false, "latest").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resource.getTemplates(CONFIG_ID, CATALOG_ID, null, false, "latest").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static EpistolaApiException upstream(int status) {
        return new EpistolaApiException("Epistola answered " + status, null, status, null, null);
    }

    @Test
    void getConfigurations_listsEveryConfiguredConnection() {
        var configuration = mock(com.ritense.plugin.domain.PluginConfiguration.class);
        var configurationId = mock(com.ritense.plugin.domain.PluginConfigurationId.class);
        when(configurationId.toString()).thenReturn(CONFIG_ID.toString());
        when(configuration.getId()).thenReturn(configurationId);
        when(configuration.getTitle()).thenReturn("Epistola productie");
        org.mockito.Mockito.doReturn(List.of(configuration))
                .when(pluginService).findPluginConfigurations(eq(EpistolaPlugin.class), any());
        plugin = mock(EpistolaPlugin.class);
        when(plugin.getTenantId()).thenReturn(TENANT_ID);
        when(pluginService.createInstance(configuration)).thenReturn(plugin);

        var response = resource.getConfigurations();

        assertThat(response.getBody()).singleElement().satisfies(info -> {
            assertThat(info.id()).isEqualTo(CONFIG_ID.toString());
            assertThat(info.title()).isEqualTo("Epistola productie");
            assertThat(info.tenantId()).isEqualTo(TENANT_ID);
        });
    }

    @Test
    void getConfigurations_stillListsAConnectionThatCannotBeInstantiated() {
        // Otherwise a broken configuration becomes invisible, and an author cannot select it to fix
        // whatever is wrong with it.
        var configuration = mock(com.ritense.plugin.domain.PluginConfiguration.class);
        var configurationId = mock(com.ritense.plugin.domain.PluginConfigurationId.class);
        when(configurationId.toString()).thenReturn(CONFIG_ID.toString());
        when(configuration.getId()).thenReturn(configurationId);
        when(configuration.getTitle()).thenReturn("Kapotte configuratie");
        org.mockito.Mockito.doReturn(List.of(configuration))
                .when(pluginService).findPluginConfigurations(eq(EpistolaPlugin.class), any());
        when(pluginService.createInstance(configuration)).thenThrow(new IllegalStateException("missing apiKey"));

        var response = resource.getConfigurations();

        assertThat(response.getBody()).singleElement().satisfies(info -> {
            assertThat(info.title()).isEqualTo("Kapotte configuratie");
            assertThat(info.tenantId()).isNull();
        });
    }
}
