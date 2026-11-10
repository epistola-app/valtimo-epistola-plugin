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
import app.epistola.client.model.CatalogTemplateDto;
import app.epistola.client.model.CatalogTemplateListResponse;
import app.epistola.client.model.CatalogTemplateRelease;
import app.epistola.client.model.DeploymentDto;
import app.epistola.client.model.DeploymentListResponse;
import app.epistola.client.model.PageMeta;
import app.epistola.client.model.TemplateListResponse;
import app.epistola.client.model.TemplateSummaryDto;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.CatalogTemplates;
import app.epistola.valtimo.domain.DesignScope.Read;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A link whose release is an expression can render any release it reaches, so its configurator
 * offers every template in them, saying where each exists (ADR 0009, addendum).
 */
class EpistolaServiceImplCatalogTemplatesTest {

    private static final String BASE_URL = "http://epistola";
    private static final String API_KEY = "key";
    private static final String TENANT = "demo";
    private static final String ENV = "staging";
    private static final String CATALOG = "local/valtimo/brieven";

    private EpistolaServiceImpl service;
    private CatalogsApi catalogsApi;
    private EnvironmentsApi environmentsApi;
    private TemplatesApi templatesApi;

    @BeforeEach
    void setUp() {
        EpistolaApiClientFactory factory = mock(EpistolaApiClientFactory.class);
        service = new EpistolaServiceImpl(factory);
        catalogsApi = CatalogFixtures.stubCatalogs(factory, TENANT,
                CatalogFixtures.catalog("local", "valtimo", "brieven", "2.1.0"));
        environmentsApi = mock(EnvironmentsApi.class);
        when(factory.createEnvironmentsApi(anyString(), anyString())).thenReturn(environmentsApi);
        templatesApi = mock(TemplatesApi.class);
        when(factory.createTemplatesApi(anyString(), anyString())).thenReturn(templatesApi);
        // Like every Suite so far: the per-release listing below is what these tests exercise.
        when(templatesApi.listCatalogTemplates(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(problem(501));
    }

    // ── A server that lists them itself (contract 2.0.0-rc.2) ──

    @Test
    void asksTheServerFirst_oneCallInsteadOfOnePerRelease() {
        org.mockito.Mockito.reset(templatesApi);
        when(templatesApi.listCatalogTemplates(eq(TENANT), eq("local"), eq("valtimo"), eq("brieven"), isNull(),
                isNull(), anyInt(), anyInt()))
                .thenReturn(new CatalogTemplateListResponse(List.of(
                        new CatalogTemplateDto("oud", "Oud", List.of(release("1.9.0")), null),
                        new CatalogTemplateDto("besluit", "Besluit",
                                List.of(release("2.1.0"), release("1.9.0")), null)),
                        new PageMeta(0, 100, 2, 1)));

        CatalogTemplates result = service.getCatalogTemplates(BASE_URL, API_KEY, TENANT, CATALOG, null);

        assertThat(result.templates()).containsExactly(
                new CatalogTemplates.Template("besluit", "Besluit", null, List.of("2.1.0", "1.9.0"),
                        new Read(null, "2.1.0")),
                new CatalogTemplates.Template("oud", "Oud", null, List.of("1.9.0"), new Read(null, "1.9.0")));
        verify(templatesApi, org.mockito.Mockito.never()).listTemplates(any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), any(), any());
    }

    @Test
    void theServersDraft_isReadFromTheEnvironment() {
        org.mockito.Mockito.reset(templatesApi);
        when(templatesApi.listCatalogTemplates(eq(TENANT), eq("local"), eq("valtimo"), eq("brieven"), eq(ENV),
                isNull(), anyInt(), anyInt()))
                .thenReturn(new CatalogTemplateListResponse(List.of(new CatalogTemplateDto("concept", "Concept",
                        List.of(new CatalogTemplateRelease(null, "draft-1", CatalogReleaseStatus.draft)), null)),
                        new PageMeta(0, 100, 1, 1)));

        assertThat(service.getCatalogTemplates(BASE_URL, API_KEY, TENANT, CATALOG, ENV).templates())
                .containsExactly(new CatalogTemplates.Template("concept", "Concept", null, List.of("draft"),
                        new Read(ENV, null)));
    }

    @Test
    void anEnvironmentTheServerListsNothingIn_isListedCatalogWide() {
        org.mockito.Mockito.doReturn(new CatalogTemplateListResponse(List.of(), new PageMeta(0, 100, 0, 1)))
                .when(templatesApi).listCatalogTemplates(any(), any(), any(), any(), eq(ENV), any(), any(), any());
        deployed();
        released("2.1.0");
        templatesIn("2.1.0", "besluit");

        assertThat(service.getCatalogTemplates(BASE_URL, API_KEY, TENANT, CATALOG, ENV).templates())
                .extracting(CatalogTemplates.Template::read)
                .containsExactly(new Read(null, "2.1.0"));
    }

    @Test
    void aServerThatDoesNotKnowTheRoute_isListedPerRelease() {
        org.mockito.Mockito.reset(templatesApi);
        when(templatesApi.listCatalogTemplates(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(problem(404));
        released("2.1.0");
        templatesIn("2.1.0", "besluit");

        assertThat(service.getCatalogTemplates(BASE_URL, API_KEY, TENANT, CATALOG, null).templates())
                .extracting(CatalogTemplates.Template::id).containsExactly("besluit");
    }

    // ── A server that does not, yet: one listing per release ──

    @Test
    void listsEveryTemplate_withTheReleasesItIsIn_andReadsItFromTheHighest() {
        released("2.1.0", "2.0.0", "1.9.0");
        templatesIn("2.1.0", "besluit", "nieuw");
        templatesIn("2.0.0", "besluit");
        templatesIn("1.9.0", "besluit", "oud");

        CatalogTemplates result = service.getCatalogTemplates(BASE_URL, API_KEY, TENANT, "brieven", null);

        assertThat(result.catalogId()).isEqualTo(CATALOG);
        assertThat(result.truncated()).isFalse();
        assertThat(result.templates()).containsExactly(
                new CatalogTemplates.Template("besluit", "Besluit", null, List.of("2.1.0", "2.0.0", "1.9.0"),
                        new Read(null, "2.1.0")),
                new CatalogTemplates.Template("nieuw", "Nieuw", null, List.of("2.1.0"), new Read(null, "2.1.0")),
                new CatalogTemplates.Template("oud", "Oud", null, List.of("1.9.0"), new Read(null, "1.9.0")));
    }

    @Test
    void inAnEnvironment_onlyWhatIsDeployedThere_draftFirst() {
        deployed(
                deployment("rel-1-9", CatalogReleaseStatus.released, "1.9.0"),
                deployment("draft-1", CatalogReleaseStatus.draft, null));
        templatesIn("draft-1", "besluit", "concept");
        templatesIn("rel-1-9", "besluit");

        CatalogTemplates result = service.getCatalogTemplates(BASE_URL, API_KEY, TENANT, CATALOG, ENV);

        assertThat(result.templates()).containsExactly(
                new CatalogTemplates.Template("besluit", "Besluit", null, List.of("draft", "1.9.0"), new Read(ENV, null)),
                new CatalogTemplates.Template("concept", "Concept", null, List.of("draft"), new Read(ENV, null)));
    }

    @Test
    void aCatalogTheEnvironmentDoesNotServe_isListedCatalogWide() {
        deployed();
        released("2.1.0");
        templatesIn("2.1.0", "besluit");

        assertThat(service.getCatalogTemplates(BASE_URL, API_KEY, TENANT, CATALOG, ENV).templates())
                .extracting(CatalogTemplates.Template::read)
                .containsExactly(new Read(null, "2.1.0"));
    }

    @Test
    void readsAReleasedReleaseOnce_becauseItNeverChanges() {
        released("2.1.0");
        templatesIn("2.1.0", "besluit");

        service.getCatalogTemplates(BASE_URL, API_KEY, TENANT, CATALOG, null);
        service.getCatalogTemplates(BASE_URL, API_KEY, TENANT, CATALOG, null);

        verify(templatesApi, times(1)).listTemplates(eq(TENANT), eq("local"), eq("valtimo"), eq("brieven"),
                eq("2.1.0"), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void readsADraftEveryTime_becauseItDoes() {
        deployed(deployment("draft-1", CatalogReleaseStatus.draft, null));
        templatesIn("draft-1", "besluit");

        service.getCatalogTemplates(BASE_URL, API_KEY, TENANT, CATALOG, ENV);
        service.getCatalogTemplates(BASE_URL, API_KEY, TENANT, CATALOG, ENV);

        verify(templatesApi, times(2)).listTemplates(eq(TENANT), eq("local"), eq("valtimo"), eq("brieven"),
                eq("draft-1"), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void readsOnlyTheNewestReleases_andSaysSo() {
        String[] versions = IntStream.rangeClosed(1, 30).mapToObj(i -> i + ".0.0").toArray(String[]::new);
        released(versions);
        for (String version : versions) {
            templatesIn(version, "besluit");
        }

        CatalogTemplates result = service.getCatalogTemplates(BASE_URL, API_KEY, TENANT, CATALOG, null);

        assertThat(result.truncated()).isTrue();
        assertThat(result.templates().get(0).releases()).hasSize(EpistolaServiceImpl.MAX_TEMPLATE_RELEASES)
                .first().isEqualTo("30.0.0");
    }

    private static CatalogTemplateRelease release(String version) {
        return new CatalogTemplateRelease(version, "rel-" + version, CatalogReleaseStatus.released);
    }

    private static app.epistola.client.error.ProblemDetailException problem(int status) {
        return new app.epistola.client.error.ProblemDetailException(
                new app.epistola.client.model.ProblemDetail(java.net.URI.create("https://epistola.app/errors/x"),
                        "x", status, null, null, null, java.util.Map.of()),
                List.of(), java.util.Map.of(), org.springframework.http.HttpStatus.valueOf(status), "x",
                org.springframework.http.HttpHeaders.EMPTY, new byte[0], java.nio.charset.StandardCharsets.UTF_8);
    }

    private void templatesIn(String release, String... keys) {
        List<TemplateSummaryDto> items = Arrays.stream(keys)
                .map(key -> new TemplateSummaryDto(key, TENANT, Character.toUpperCase(key.charAt(0)) + key.substring(1),
                        OffsetDateTime.now(), OffsetDateTime.now(), null))
                .toList();
        when(templatesApi.listTemplates(eq(TENANT), eq("local"), eq("valtimo"), eq("brieven"), eq(release),
                any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new TemplateListResponse(items, new PageMeta(0, 100, items.size(), 1)));
    }

    private void released(String... versions) {
        List<CatalogReleaseDto> releases = Arrays.stream(versions)
                .map(version -> new CatalogReleaseDto(version, "rel-" + version, CatalogReleaseStatus.released,
                        List.of(), List.of(), List.of(), null, null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                        null, null, null, null, List.of(), null))
                .toList();
        when(catalogsApi.listCatalogReleases(eq(TENANT), eq("local"), eq("valtimo"), eq("brieven"),
                eq(CatalogReleaseStatus.released), isNull(), anyInt(), anyInt()))
                .thenReturn(new CatalogReleaseListResponse(releases, new PageMeta(0, 100, releases.size(), 1)));
    }

    private void deployed(DeploymentDto... deployments) {
        when(environmentsApi.listEnvironmentDeployments(TENANT, ENV))
                .thenReturn(new DeploymentListResponse(List.of(deployments)));
    }

    private static DeploymentDto deployment(String releaseId, CatalogReleaseStatus status, String version) {
        return new DeploymentDto("local", "valtimo", "brieven", releaseId, status, version, null,
                OffsetDateTime.now(), null, 0L);
    }
}
