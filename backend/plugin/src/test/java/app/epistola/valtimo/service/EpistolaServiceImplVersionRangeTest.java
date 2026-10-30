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

import app.epistola.client.api.CatalogsApi;
import app.epistola.client.api.EnvironmentsApi;
import app.epistola.client.api.GenerationApi;
import app.epistola.client.api.TemplatesApi;
import app.epistola.client.model.CatalogReleaseDto;
import app.epistola.client.model.CatalogReleaseListResponse;
import app.epistola.client.model.CatalogReleaseStatus;
import app.epistola.client.model.DeploymentDto;
import app.epistola.client.model.DeploymentListResponse;
import app.epistola.client.model.GenerateDocumentRequest;
import app.epistola.client.model.GenerationJobResponse;
import app.epistola.client.model.GenerationJobStatus;
import app.epistola.client.model.GenerationJobType;
import app.epistola.client.model.PageMeta;
import app.epistola.client.model.TemplateListResponse;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.CatalogReleases;
import app.epistola.valtimo.domain.FileFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A process link may pin a release of its catalog, or a range of them. Generation sends the range
 * and the server picks the release; the configurator reads the template from the release the same
 * range selects, so the fields it maps are the ones that release renders.
 */
class EpistolaServiceImplVersionRangeTest {

    private static final String BASE_URL = "http://epistola";
    private static final String API_KEY = "key";
    private static final String TENANT = "demo";
    private static final String ENV = "productie";
    private static final String CATALOG = "local/valtimo/brieven";

    private EpistolaApiClientFactory factory;
    private EpistolaServiceImpl service;
    private CatalogsApi catalogsApi;
    private EnvironmentsApi environmentsApi;
    private TemplatesApi templatesApi;

    @BeforeEach
    void setUp() {
        factory = mock(EpistolaApiClientFactory.class);
        service = new EpistolaServiceImpl(factory);
        catalogsApi = CatalogFixtures.stubCatalogs(factory, TENANT,
                CatalogFixtures.catalog("local", "valtimo", "brieven", "2.1.0"));
        environmentsApi = mock(EnvironmentsApi.class);
        when(factory.createEnvironmentsApi(anyString(), anyString())).thenReturn(environmentsApi);
        templatesApi = mock(TemplatesApi.class);
        when(factory.createTemplatesApi(anyString(), anyString())).thenReturn(templatesApi);
        when(templatesApi.listTemplates(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new TemplateListResponse(List.of(), new PageMeta(0, 100, 0, 1)));
    }

    @Test
    void readsTheHighestReleaseThatSatisfiesTheRange() {
        released("2.1.0", "1.10.0", "1.9.0", "1.2.0");

        service.getTemplates(BASE_URL, API_KEY, TENANT, CATALOG, null, "^1.4.0");

        assertReadFrom("1.10.0");
    }

    @Test
    void readsAPinnedRelease_evenWhenItIsNotTheHighest() {
        released("2.1.0", "1.9.0");

        service.getTemplates(BASE_URL, API_KEY, TENANT, CATALOG, null, "1.9.0");

        assertReadFrom("1.9.0");
    }

    @Test
    void aRangeNoReleaseSatisfies_isNotFound() {
        released("2.1.0", "1.9.0");

        assertThatThrownBy(() -> service.getTemplates(BASE_URL, API_KEY, TENANT, CATALOG, null, "^3.0.0"))
                .isInstanceOfSatisfying(EpistolaApiException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(404);
                    assertThat(e.getMessage()).isEqualTo("No release of catalog 'local/valtimo/brieven' satisfies '^3.0.0'");
                });
    }

    @Test
    void whatIsNotARange_isABadRequest() {
        assertThatThrownBy(() -> service.getTemplates(BASE_URL, API_KEY, TENANT, CATALOG, null, "latest"))
                .isInstanceOfSatisfying(EpistolaApiException.class, e -> assertThat(e.getHttpStatus()).isEqualTo(400));
    }

    @Test
    void inAnEnvironment_readsTheHighestDeployedReleaseThatSatisfiesTheRange_neverItsDraft() {
        // 1.9.0 is the tenant's too, but only what the environment serves can be rendered there.
        deployed(
                deployment("rel-1-2", CatalogReleaseStatus.released, "1.2.0"),
                deployment("rel-1-5", CatalogReleaseStatus.released, "1.5.0"),
                deployment("rel-2-1", CatalogReleaseStatus.released, "2.1.0"),
                deployment("draft-1", CatalogReleaseStatus.draft, null));

        service.getTemplates(BASE_URL, API_KEY, TENANT, CATALOG, ENV, "^1.0.0");

        assertReadFrom("rel-1-5");
    }

    @Test
    void inAnEnvironment_aRangeNoDeployedReleaseSatisfies_isNotFound() {
        deployed(deployment("rel-2-1", CatalogReleaseStatus.released, "2.1.0"));

        assertThatThrownBy(() -> service.getTemplates(BASE_URL, API_KEY, TENANT, CATALOG, ENV, "1.9.0"))
                .isInstanceOfSatisfying(EpistolaApiException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(404);
                    assertThat(e.getMessage()).isEqualTo("No release of catalog 'local/valtimo/brieven' deployed "
                            + "to environment 'productie' satisfies '1.9.0'");
                });
    }

    @Test
    void releases_listsTheReleasedVersionsHighestFirst_withTheOneTheRangeSelects() {
        released("1.9.0", "2.1.0", "1.10.0");

        CatalogReleases releases = service.getReleases(BASE_URL, API_KEY, TENANT, CATALOG, null, " ~1.9.0 ");

        assertThat(releases).isEqualTo(new CatalogReleases(List.of("2.1.0", "1.10.0", "1.9.0"), "1.9.0", false));
    }

    @Test
    void releases_selectNothing_whenNoReleaseSatisfiesTheRange() {
        released("2.1.0");

        assertThat(service.getReleases(BASE_URL, API_KEY, TENANT, CATALOG, null, "^1.0.0").selected()).isNull();
    }

    @Test
    void releases_inAnEnvironmentServingADraft_sayThatADraftRendersWithoutARange() {
        deployed(
                deployment("rel-2-1", CatalogReleaseStatus.released, "2.1.0"),
                deployment("draft-1", CatalogReleaseStatus.draft, null));

        assertThat(service.getReleases(BASE_URL, API_KEY, TENANT, CATALOG, ENV, null))
                .isEqualTo(new CatalogReleases(List.of("2.1.0"), null, true));
        assertThat(service.getReleases(BASE_URL, API_KEY, TENANT, CATALOG, ENV, "2.1.0"))
                .isEqualTo(new CatalogReleases(List.of("2.1.0"), "2.1.0", false));
    }

    @Test
    void generationSendsTheRange_forTheServerToResolve() {
        GenerationApi generationApi = mock(GenerationApi.class);
        when(factory.createGenerationApi(anyString(), anyString())).thenReturn(generationApi);
        when(generationApi.generateDocument(eq(TENANT), any())).thenReturn(new GenerationJobResponse(
                UUID.randomUUID(), GenerationJobStatus.pending, GenerationJobType.single, 1,
                OffsetDateTime.now()));

        service.submitGenerationJob(BASE_URL, API_KEY, TENANT, CATALOG, "besluit", null, null, ENV, " ^1.4.0 ",
                Map.of(), FileFormat.PDF, "besluit.pdf", null, null);

        ArgumentCaptor<GenerateDocumentRequest> request = ArgumentCaptor.forClass(GenerateDocumentRequest.class);
        verify(generationApi).generateDocument(eq(TENANT), request.capture());
        assertThat(request.getValue().getVersionRange()).isEqualTo("^1.4.0");
        assertThat(request.getValue().getEnvironmentKey()).isEqualTo(ENV);
    }

    private void assertReadFrom(String release) {
        verify(templatesApi).listTemplates(eq(TENANT), eq("local"), eq("valtimo"), eq("brieven"), eq(release),
                any(), anyInt(), anyInt(), any(), any());
    }

    private void released(String... versions) {
        List<CatalogReleaseDto> releases = java.util.Arrays.stream(versions)
                .map(version -> new CatalogReleaseDto(version, "rel-" + version, CatalogReleaseStatus.released,
                        List.of(), List.of(), List.of(), null, null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                        null, null, null, null, List.of()))
                .toList();
        when(catalogsApi.listCatalogReleases(eq(TENANT), eq("local"), eq("valtimo"), eq("brieven"),
                eq(CatalogReleaseStatus.released), isNull(), anyInt(), anyInt()))
                .thenReturn(new CatalogReleaseListResponse(releases, new PageMeta(0, 100, releases.size(), 1)));
    }

    private void deployed(DeploymentDto... deployments) {
        when(environmentsApi.listEnvironmentDeployments(TENANT, ENV)).thenReturn(new DeploymentListResponse(List.of(deployments)));
    }

    private static DeploymentDto deployment(String releaseId, CatalogReleaseStatus status, String version) {
        return new DeploymentDto("local", "valtimo", "brieven", releaseId, status, version, null,
                OffsetDateTime.now(), null, 0L);
    }
}
