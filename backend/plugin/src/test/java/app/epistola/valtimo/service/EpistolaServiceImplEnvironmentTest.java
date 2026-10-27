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
package app.epistola.valtimo.service;

import app.epistola.client.api.EnvironmentsApi;
import app.epistola.client.api.TemplatesApi;
import app.epistola.client.model.CatalogReleaseStatus;
import app.epistola.client.model.DeploymentDto;
import app.epistola.client.model.DeploymentListResponse;
import app.epistola.client.model.TemplateListResponse;
import app.epistola.client.model.PageMeta;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.CatalogInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * With an environment, the plugin offers what that environment serves and reads content from the
 * release it renders — so a configurator never offers a template generation there cannot produce.
 */
class EpistolaServiceImplEnvironmentTest {

    private static final String BASE_URL = "http://epistola";
    private static final String API_KEY = "key";
    private static final String TENANT = "demo";
    private static final String ENV = "productie";

    private EpistolaApiClientFactory factory;
    private EpistolaServiceImpl service;
    private EnvironmentsApi environmentsApi;

    @BeforeEach
    void setUp() {
        factory = mock(EpistolaApiClientFactory.class);
        service = new EpistolaServiceImpl(factory);
        CatalogFixtures.stubCatalogs(factory, TENANT,
                CatalogFixtures.catalog("brieven"),
                CatalogFixtures.catalog("huisstijl"),
                CatalogFixtures.catalog("exchange.epistola.app", "vng", "brieven", "3.0.0"));
        environmentsApi = mock(EnvironmentsApi.class);
        when(factory.createEnvironmentsApi(anyString(), anyString())).thenReturn(environmentsApi);
    }

    @Test
    void offersOnlyTheCatalogsDeployedToTheEnvironment() {
        // huisstijl is a dependency of brieven: the environment uses it without it being deployed.
        deployed(released("local", "valtimo", "brieven", "rel-1", "1.0.0"));

        List<CatalogInfo> catalogs = service.getCatalogs(BASE_URL, API_KEY, TENANT, ENV);

        assertThat(catalogs).extracting(CatalogInfo::id).containsExactly("local/valtimo/brieven");
    }

    @Test
    void offersOneEntryForACatalogWithSeveralReleasesDeployed() {
        deployed(
                released("local", "valtimo", "brieven", "rel-1", "1.0.0"),
                released("local", "valtimo", "brieven", "rel-2", "1.1.0"));

        assertThat(service.getCatalogs(BASE_URL, API_KEY, TENANT, ENV)).hasSize(1);
    }

    @Test
    void offersEveryCatalogWithoutAnEnvironment() {
        assertThat(service.getCatalogs(BASE_URL, API_KEY, TENANT, null)).hasSize(3);
    }

    @Test
    void readsTheHighestDeployedReleaseBySemanticVersion() {
        deployed(
                released("local", "valtimo", "brieven", "rel-9", "1.9.0"),
                released("local", "valtimo", "brieven", "rel-10", "1.10.0"));
        TemplatesApi templatesApi = templates();

        service.getTemplates(BASE_URL, API_KEY, TENANT, "local/valtimo/brieven", ENV);

        verify(templatesApi).listTemplates(eq(TENANT), eq("local"), eq("valtimo"), eq("brieven"), eq("rel-10"),
                any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void readsTheDeployedDraftOverAnyRelease() {
        // Generation without a version range renders an environment's deployed draft.
        deployed(
                released("local", "valtimo", "brieven", "rel-2", "2.0.0"),
                deployment("local", "valtimo", "brieven", "draft-1", CatalogReleaseStatus.draft, null));
        TemplatesApi templatesApi = templates();

        service.getTemplates(BASE_URL, API_KEY, TENANT, "local/valtimo/brieven", ENV);

        verify(templatesApi).listTemplates(eq(TENANT), eq("local"), eq("valtimo"), eq("brieven"), eq("draft-1"),
                any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void aKeySavedAgainstContract1_isLookedUpAmongWhatTheEnvironmentServes() {
        // Two namespaces have "brieven", but only one is deployed here: not ambiguous.
        deployed(released("exchange.epistola.app", "vng", "brieven", "rel-3", "3.0.0"));
        TemplatesApi templatesApi = templates();

        service.getTemplates(BASE_URL, API_KEY, TENANT, "brieven", ENV);

        verify(templatesApi).listTemplates(eq(TENANT), eq("exchange.epistola.app"), eq("vng"), eq("brieven"),
                eq("rel-3"), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void aCatalogNotDeployedToTheEnvironment_isNotFound() {
        deployed(released("local", "valtimo", "huisstijl", "rel-1", "1.0.0"));

        assertThatThrownBy(() -> service.getTemplates(BASE_URL, API_KEY, TENANT, "local/valtimo/brieven", ENV))
                .isInstanceOfSatisfying(EpistolaApiException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(404);
                    assertThat(e.getMessage()).contains("not deployed to environment 'productie'");
                });
    }

    @Test
    void anEnvironmentThatDoesNotExist_isNotFound() {
        when(environmentsApi.listEnvironmentDeployments(TENANT, ENV)).thenThrow(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> service.getCatalogs(BASE_URL, API_KEY, TENANT, ENV))
                .isInstanceOfSatisfying(EpistolaApiException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(404);
                    assertThat(e.getMessage()).contains("Environment not found");
                });
    }

    private void deployed(DeploymentDto... deployments) {
        when(environmentsApi.listEnvironmentDeployments(TENANT, ENV)).thenReturn(new DeploymentListResponse(List.of(deployments)));
    }

    private TemplatesApi templates() {
        TemplatesApi templatesApi = mock(TemplatesApi.class);
        when(factory.createTemplatesApi(anyString(), anyString())).thenReturn(templatesApi);
        when(templatesApi.listTemplates(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new TemplateListResponse(List.of(), new PageMeta(0, 100, 0, 1)));
        return templatesApi;
    }

    private static DeploymentDto released(String registry, String namespace, String key, String releaseId, String version) {
        return deployment(registry, namespace, key, releaseId, CatalogReleaseStatus.released, version);
    }

    private static DeploymentDto deployment(String registry, String namespace, String key, String releaseId,
                                            CatalogReleaseStatus status, String version) {
        return new DeploymentDto(registry, namespace, key, releaseId, status, version, null,
                OffsetDateTime.now(), null, 0L);
    }
}
