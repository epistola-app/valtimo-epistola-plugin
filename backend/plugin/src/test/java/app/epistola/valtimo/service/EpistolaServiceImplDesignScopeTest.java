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
import app.epistola.client.api.TemplatesApi;
import app.epistola.client.model.CatalogReleaseDto;
import app.epistola.client.model.CatalogReleaseListResponse;
import app.epistola.client.model.CatalogReleaseStatus;
import app.epistola.client.model.DeploymentDto;
import app.epistola.client.model.DeploymentListResponse;
import app.epistola.client.model.PageMeta;
import app.epistola.client.model.TemplateListResponse;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.CatalogInfo;
import app.epistola.valtimo.domain.DesignScope;
import app.epistola.valtimo.domain.DesignScope.Problem;
import app.epistola.valtimo.domain.DesignScope.Read;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

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
 * A configurator resolves the release it designs a link against once, and every lookup it makes then
 * reads that release (ADR 0009). Where the link would not render — a catalog its environment does not
 * serve, a range nothing satisfies — it falls back and says why, rather than failing.
 */
class EpistolaServiceImplDesignScopeTest {

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
                CatalogFixtures.catalog("local", "valtimo", "brieven", "2.1.0"),
                CatalogFixtures.catalog("local", "valtimo", "formulieren", "1.0.0"));
        environmentsApi = mock(EnvironmentsApi.class);
        when(factory.createEnvironmentsApi(anyString(), anyString())).thenReturn(environmentsApi);
        templatesApi = mock(TemplatesApi.class);
        when(factory.createTemplatesApi(anyString(), anyString())).thenReturn(templatesApi);
        when(templatesApi.listTemplates(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new TemplateListResponse(List.of(), new PageMeta(0, 100, 0, 1)));
    }

    // ── In the link's environment ──

    @Test
    void inAnEnvironment_designsAgainstWhatItRenders_itsDeployedDraft() {
        deployed(
                deployment("rel-2-1", CatalogReleaseStatus.released, "2.1.0"),
                deployment("draft-1", CatalogReleaseStatus.draft, null));

        assertThat(service.getDesignScope(BASE_URL, API_KEY, TENANT, CATALOG, ENV, null)).isEqualTo(
                new DesignScope(CATALOG, List.of("2.1.0"), null, true, ENV, List.of(), new Read(ENV, null)));
    }

    @Test
    void inAnEnvironment_withoutADraft_designsAgainstItsHighestDeployedRelease() {
        deployed(
                deployment("rel-1-9", CatalogReleaseStatus.released, "1.9.0"),
                deployment("rel-1-10", CatalogReleaseStatus.released, "1.10.0"));

        assertThat(service.getDesignScope(BASE_URL, API_KEY, TENANT, CATALOG, ENV, null)).isEqualTo(
                new DesignScope(CATALOG, List.of("1.10.0", "1.9.0"), "1.10.0", false, ENV, List.of(),
                        new Read(ENV, null)));
    }

    @Test
    void inAnEnvironment_withARange_designsAgainstTheHighestDeployedReleaseThatSatisfiesIt_neverTheDraft() {
        deployed(
                deployment("rel-1-5", CatalogReleaseStatus.released, "1.5.0"),
                deployment("rel-2-1", CatalogReleaseStatus.released, "2.1.0"),
                deployment("draft-1", CatalogReleaseStatus.draft, null));

        assertThat(service.getDesignScope(BASE_URL, API_KEY, TENANT, CATALOG, ENV, " ^1.0.0 ")).isEqualTo(
                new DesignScope(CATALOG, List.of("2.1.0", "1.5.0"), "1.5.0", false, ENV, List.of(),
                        new Read(ENV, "1.5.0")));
    }

    // ── Falling back, and saying why ──

    @Test
    void aCatalogTheEnvironmentDoesNotServe_isReadCatalogWide_andSaysItIsNotDeployed() {
        deployed(deployment("formulieren", "rel-1", CatalogReleaseStatus.released, "1.0.0"));
        released("2.1.0", "1.9.0");

        assertThat(service.getDesignScope(BASE_URL, API_KEY, TENANT, CATALOG, ENV, null)).isEqualTo(
                new DesignScope(CATALOG, List.of("2.1.0", "1.9.0"), "2.1.0", false, null,
                        List.of(Problem.NOT_DEPLOYED), new Read(null, "2.1.0")));
    }

    @Test
    void aRangeTheEnvironmentDoesNotServe_isReadCatalogWide_andSaysSo() {
        deployed(deployment("rel-2-1", CatalogReleaseStatus.released, "2.1.0"));
        released("2.1.0", "1.9.0");

        assertThat(service.getDesignScope(BASE_URL, API_KEY, TENANT, CATALOG, ENV, "^1.0.0")).isEqualTo(
                new DesignScope(CATALOG, List.of("2.1.0", "1.9.0"), "1.9.0", false, null,
                        List.of(Problem.RANGE_NOT_DEPLOYED), new Read(null, "1.9.0")));
    }

    @Test
    void aRangeNoReleaseSatisfies_isDropped_andBothReasonsAreGiven() {
        deployed();
        released("2.1.0", "1.9.0");

        assertThat(service.getDesignScope(BASE_URL, API_KEY, TENANT, CATALOG, ENV, "^3.0.0")).isEqualTo(
                new DesignScope(CATALOG, List.of("2.1.0", "1.9.0"), "2.1.0", false, null,
                        List.of(Problem.NOT_DEPLOYED, Problem.RANGE_UNSATISFIED), new Read(null, "2.1.0")));
    }

    @Test
    void anEnvironmentThatDoesNotExist_isReadCatalogWide_andSaysSo() {
        when(environmentsApi.listEnvironmentDeployments(TENANT, "prodcutie"))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        released("2.1.0");

        DesignScope scope = service.getDesignScope(BASE_URL, API_KEY, TENANT, CATALOG, "prodcutie", null);

        assertThat(scope.problems()).containsExactly(Problem.UNKNOWN_ENVIRONMENT);
        assertThat(scope.read()).isEqualTo(new Read(null, "2.1.0"));
    }

    // ── Catalog-wide ──

    @Test
    void catalogWide_withARange_designsAgainstTheHighestReleaseThatSatisfiesIt() {
        released("1.9.0", "2.1.0", "1.10.0");

        assertThat(service.getDesignScope(BASE_URL, API_KEY, TENANT, CATALOG, null, "~1.9.0")).isEqualTo(
                new DesignScope(CATALOG, List.of("2.1.0", "1.10.0", "1.9.0"), "1.9.0", false, null, List.of(),
                        new Read(null, "1.9.0")));
    }

    @Test
    void aCatalogNeverReleased_designsAgainstItsNewestDraft() {
        released();
        drafts("draft-1");

        assertThat(service.getDesignScope(BASE_URL, API_KEY, TENANT, CATALOG, null, null)).isEqualTo(
                new DesignScope(CATALOG, List.of(), null, true, null, List.of(), new Read(null, null)));
    }

    @Test
    void aBareKeySavedAgainstContract1x_isAnsweredQualified() {
        released("2.1.0");

        assertThat(service.getDesignScope(BASE_URL, API_KEY, TENANT, "brieven", null, null).catalogId())
                .isEqualTo(CATALOG);
    }

    // ── What still fails ──

    @Test
    void whatIsNotARange_isABadRequest() {
        assertThatThrownBy(() -> service.getDesignScope(BASE_URL, API_KEY, TENANT, CATALOG, null, "latest"))
                .isInstanceOfSatisfying(EpistolaApiException.class, e -> assertThat(e.getHttpStatus()).isEqualTo(400));
    }

    @Test
    void aCatalogThatDoesNotExist_isNotFound() {
        assertThatThrownBy(() -> service.getDesignScope(BASE_URL, API_KEY, TENANT, "local/valtimo/weg", null, null))
                .isInstanceOfSatisfying(EpistolaApiException.class, e -> assertThat(e.getHttpStatus()).isEqualTo(404));
    }

    // ── The selection reads the release designed against ──

    @Test
    void theReadSelection_catalogWide_readsTheReleaseDesignedAgainst() {
        deployed();
        released("2.1.0", "1.9.0");
        Read read = service.getDesignScope(BASE_URL, API_KEY, TENANT, CATALOG, ENV, "^1.0.0").read();

        service.getTemplates(BASE_URL, API_KEY, TENANT, CATALOG, read.environmentId(), read.versionRange());

        verify(templatesApi).listTemplates(eq(TENANT), eq("local"), eq("valtimo"), eq("brieven"), eq("1.9.0"),
                any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void theReadSelection_inAnEnvironment_readsTheReleaseDesignedAgainst() {
        deployed(
                deployment("rel-1-5", CatalogReleaseStatus.released, "1.5.0"),
                deployment("rel-2-1", CatalogReleaseStatus.released, "2.1.0"));
        Read read = service.getDesignScope(BASE_URL, API_KEY, TENANT, CATALOG, ENV, "^1.0.0").read();

        service.getTemplates(BASE_URL, API_KEY, TENANT, CATALOG, read.environmentId(), read.versionRange());

        verify(templatesApi).listTemplates(eq(TENANT), eq("local"), eq("valtimo"), eq("brieven"), eq("rel-1-5"),
                any(), anyInt(), anyInt(), any(), any());
    }

    // ── The catalog list ──

    @Test
    void theCatalogList_isNotNarrowed_butMarksWhatTheEnvironmentServes() {
        deployed(deployment("formulieren", "rel-1", CatalogReleaseStatus.released, "1.0.0"));

        List<CatalogInfo> catalogs = service.getCatalogsMarkingDeployment(BASE_URL, API_KEY, TENANT, ENV);

        assertThat(catalogs).extracting(CatalogInfo::id, CatalogInfo::deployedToEnvironment).containsExactly(
                org.assertj.core.groups.Tuple.tuple("local/valtimo/brieven", false),
                org.assertj.core.groups.Tuple.tuple("local/valtimo/formulieren", true));
    }

    @Test
    void theCatalogList_withoutAnEnvironment_marksNothing() {
        assertThat(service.getCatalogsMarkingDeployment(BASE_URL, API_KEY, TENANT, null))
                .extracting(CatalogInfo::deployedToEnvironment).containsOnlyNulls().hasSize(2);
    }

    @Test
    void theCatalogList_inAnEnvironmentThatDoesNotExist_marksNothing_ratherThanFailing() {
        when(environmentsApi.listEnvironmentDeployments(TENANT, "prodcutie"))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        assertThat(service.getCatalogsMarkingDeployment(BASE_URL, API_KEY, TENANT, "prodcutie"))
                .extracting(CatalogInfo::deployedToEnvironment).containsOnlyNulls().hasSize(2);
    }

    private void released(String... versions) {
        stubReleases(CatalogReleaseStatus.released, Arrays.stream(versions)
                .map(version -> release(version, "rel-" + version, CatalogReleaseStatus.released))
                .toList());
    }

    private void drafts(String... releaseIds) {
        stubReleases(CatalogReleaseStatus.draft, Arrays.stream(releaseIds)
                .map(id -> release(null, id, CatalogReleaseStatus.draft))
                .toList());
    }

    private void stubReleases(CatalogReleaseStatus status, List<CatalogReleaseDto> releases) {
        when(catalogsApi.listCatalogReleases(eq(TENANT), eq("local"), eq("valtimo"), eq("brieven"),
                eq(status), isNull(), anyInt(), anyInt()))
                .thenReturn(new CatalogReleaseListResponse(releases, new PageMeta(0, 100, releases.size(), 1)));
    }

    private static CatalogReleaseDto release(String version, String releaseId, CatalogReleaseStatus status) {
        return new CatalogReleaseDto(version, releaseId, status, List.of(), List.of(), List.of(), null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, null, List.of(), null);
    }

    private void deployed(DeploymentDto... deployments) {
        when(environmentsApi.listEnvironmentDeployments(TENANT, ENV))
                .thenReturn(new DeploymentListResponse(List.of(deployments)));
    }

    private static DeploymentDto deployment(String releaseId, CatalogReleaseStatus status, String version) {
        return deployment("brieven", releaseId, status, version);
    }

    private static DeploymentDto deployment(String key, String releaseId, CatalogReleaseStatus status, String version) {
        return new DeploymentDto("local", "valtimo", key, releaseId, status, version, null,
                OffsetDateTime.now(), null, 0L);
    }
}
