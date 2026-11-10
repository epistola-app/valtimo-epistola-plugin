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
import app.epistola.client.api.VariantsApi;
import app.epistola.client.error.ProblemDetailException;
import app.epistola.client.model.CatalogReleaseDto;
import app.epistola.client.model.CatalogReleaseListResponse;
import app.epistola.client.model.CatalogReleaseStatus;
import app.epistola.client.model.DeploymentDto;
import app.epistola.client.model.DeploymentListResponse;
import app.epistola.client.model.PageMeta;
import app.epistola.client.model.ProblemDetail;
import app.epistola.client.model.VariantListResponse;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GenerationTargetProblem;
import app.epistola.valtimo.domain.GenerationTargetProblem.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Epistola refuses a generation it cannot resolve with one problem type, {@code not-found}, whatever
 * is missing; only its free-text {@code detail} says what, and RFC 9457 §3.1.4 advises against
 * parsing that. So the plugin works out which part is missing itself (ADR 0009, addendum), and says
 * so in a message built from configuration — safe to log — keeping Epistola's text for the case.
 */
class EpistolaServiceImplRefusalTest {

    private static final String BASE_URL = "http://epistola";
    private static final String API_KEY = "key";
    private static final String TENANT = "demo";
    private static final String ENV = "staging";
    private static final String CATALOG = "local/valtimo/brieven";
    private static final String DETAIL = "Epistola's own words about it";

    private EpistolaApiClientFactory factory;
    private EpistolaServiceImpl service;
    private CatalogsApi catalogsApi;
    private EnvironmentsApi environmentsApi;
    private TemplatesApi templatesApi;
    private VariantsApi variantsApi;
    private GenerationApi generationApi;

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
        variantsApi = mock(VariantsApi.class);
        when(factory.createVariantsApi(anyString(), anyString())).thenReturn(variantsApi);
        generationApi = mock(GenerationApi.class);
        when(factory.createGenerationApi(anyString(), anyString())).thenReturn(generationApi);
        when(generationApi.generateDocument(eq(TENANT), any())).thenThrow(problem("not-found", 404, Map.of()));
    }

    // ── Which part is missing, checked in the order Epistola resolves them ──

    @Test
    void anEnvironmentThatDoesNotExist() {
        when(environmentsApi.listEnvironmentDeployments(TENANT, ENV)).thenThrow(problem("not-found", 404, Map.of()));

        assertThat(refusedSubmit(ENV, null, null).getProblem().reason()).isEqualTo(Reason.ENVIRONMENT_NOT_FOUND);
    }

    @Test
    void aCatalogNotDeployedToTheEnvironment() {
        deployed();

        GenerationTargetProblem problem = refusedSubmit(ENV, null, null).getProblem();

        assertThat(problem.reason()).isEqualTo(Reason.NOT_DEPLOYED);
        assertThat(problem.describe()).isEqualTo("Catalog 'local/valtimo/brieven' is not deployed to environment "
                + "'staging'. Deploy a release of it there in Epistola.");
    }

    @Test
    void aRangeNothingDeployedSatisfies() {
        deployed(deployment("rel-2-1", CatalogReleaseStatus.released, "2.1.0"));

        GenerationTargetProblem problem = refusedSubmit(ENV, "^1.0.0", null).getProblem();

        assertThat(problem.reason()).isEqualTo(Reason.RANGE_UNSATISFIED);
        assertThat(problem.describe()).isEqualTo("No release of catalog 'local/valtimo/brieven' deployed to "
                + "environment 'staging' satisfies \"^1.0.0\". Deploy or release one that does, or change the release.");
    }

    @Test
    void aTemplateTheReleaseDoesNotContain_namingTheRelease() {
        deployed(deployment("rel-1-5", CatalogReleaseStatus.released, "1.5.0"));
        when(templatesApi.getTemplate(TENANT, "local", "valtimo", "brieven", "rel-1-5", "besluit"))
                .thenThrow(problem("not-found", 404, Map.of()));

        EpistolaTargetNotFoundException refused = refusedSubmit(ENV, "^1.0.0", null);

        assertThat(refused.getProblem().reason()).isEqualTo(Reason.TEMPLATE_NOT_IN_RELEASE);
        assertThat(refused.getProblem().release()).isEqualTo("1.5.0");
        assertThat(refused.getMessage()).isEqualTo("Template 'besluit' is not in catalog 'local/valtimo/brieven' in "
                + "release 1.5.0 (selected by \"^1.0.0\" in environment 'staging'). Choose a release that contains "
                + "it, or another template.");
    }

    @Test
    void aVariantTheReleaseDoesNotContain() {
        released("2.1.0");
        when(variantsApi.listVariants(eq(TENANT), eq("local"), eq("valtimo"), eq("brieven"), eq("2.1.0"),
                eq("besluit"), anyInt(), anyInt()))
                .thenReturn(new VariantListResponse(List.of(), new PageMeta(0, 100, 0, 1)));

        GenerationTargetProblem problem = refusedSubmit(null, null, "formeel").getProblem();

        assertThat(problem.reason()).isEqualTo(Reason.VARIANT_NOT_IN_RELEASE);
        assertThat(problem.release()).isEqualTo("2.1.0");
    }

    @Test
    void aCatalogThatDoesNotExist() {
        GenerationTargetProblem problem = service.diagnoseNotFound(BASE_URL, API_KEY, TENANT, "local/valtimo/weg",
                "besluit", null, null, null, new EpistolaApiException("x", null, 404, null, Map.of()));

        assertThat(problem.reason()).isEqualTo(Reason.CATALOG_NOT_FOUND);
    }

    @Test
    void noVariantMatchingTheAttributes_isItsOwnProblemType() {
        when(generationApi.generateDocument(eq(TENANT), any())).thenThrow(problem("no-matching-variant", 404, Map.of()));

        assertThat(refusedSubmit(null, null, null).getProblem().reason()).isEqualTo(Reason.NO_MATCHING_VARIANT);
    }

    // ── When the server says what is missing ──

    @Test
    void readsTheMissingMember_withoutCheckingForItself() {
        when(generationApi.generateDocument(eq(TENANT), any())).thenThrow(problem("not-found", 404,
                Map.of("missing", Map.of("kind", "template", "release", "1.4.0", "template", "besluit"))));

        GenerationTargetProblem problem = refusedSubmit(ENV, null, null).getProblem();

        assertThat(problem.reason()).isEqualTo(Reason.TEMPLATE_NOT_IN_RELEASE);
        assertThat(problem.release()).isEqualTo("1.4.0");
        org.mockito.Mockito.verifyNoInteractions(environmentsApi, templatesApi);
    }

    @Test
    void aMissingKindItDoesNotKnow_isCheckedForItself() {
        when(generationApi.generateDocument(eq(TENANT), any())).thenThrow(problem("not-found", 404,
                Map.of("missing", Map.of("kind", "stencil"))));
        deployed();

        assertThat(refusedSubmit(ENV, null, null).getProblem().reason()).isEqualTo(Reason.NOT_DEPLOYED);
    }

    // ── What it says, and to whom ──

    @Test
    void keepsEpistolasWordsForTheCase_outOfTheMessage_andAttachesNoCause() {
        deployed();

        EpistolaTargetNotFoundException refused = refusedSubmit(ENV, null, null);

        assertThat(refused.getEpistolaDetail()).isEqualTo(DETAIL);
        assertThat(refused.getMessage()).doesNotContain(DETAIL);
        assertThat(refused.getCause()).isNull();
        assertThat(refused.getHttpStatus()).isEqualTo(404);
    }

    @Test
    void aDiagnosisThatCannotBeCompleted_isUnknown_notASecondFailure() {
        when(environmentsApi.listEnvironmentDeployments(TENANT, ENV)).thenThrow(new IllegalStateException("down"));

        assertThat(refusedSubmit(ENV, null, null).getProblem().reason()).isEqualTo(Reason.UNKNOWN);
    }

    @Test
    void anythingButNotFound_keepsItsStatus_soATransientFailureStaysTransient() {
        when(generationApi.generateDocument(eq(TENANT), any())).thenThrow(problem("internal-error", 503, Map.of()));

        EpistolaApiException refused = catchThrowableOfType(EpistolaApiException.class, () -> submit(null, null, null));

        assertThat(refused).isNotInstanceOf(EpistolaTargetNotFoundException.class);
        assertThat(refused.getHttpStatus()).isEqualTo(503);
    }

    @Test
    void aPreviewIsDiagnosedTheSameWay() {
        deployed();
        when(factory.createLongRunningGenerationApi(anyString(), anyString())).thenReturn(generationApi);
        when(generationApi.previewDocument(eq(TENANT), any())).thenThrow(problem("not-found", 404, Map.of()));

        EpistolaTargetNotFoundException refused = catchThrowableOfType(EpistolaTargetNotFoundException.class,
                () -> service.previewDocument(BASE_URL, API_KEY, TENANT, CATALOG, "besluit", null, ENV, null, Map.of()));

        assertThat(refused.getProblem().reason()).isEqualTo(Reason.NOT_DEPLOYED);
    }

    private EpistolaTargetNotFoundException refusedSubmit(String environment, String range, String variant) {
        return catchThrowableOfType(EpistolaTargetNotFoundException.class, () -> submit(environment, range, variant));
    }

    private void submit(String environment, String range, String variant) {
        service.submitGenerationJob(BASE_URL, API_KEY, TENANT, CATALOG, "besluit", variant, null, environment,
                range, Map.of(), FileFormat.PDF, "besluit.pdf", null, null);
    }

    private static ProblemDetailException problem(String slug, int status, Map<String, Object> extensions) {
        return new ProblemDetailException(
                new ProblemDetail(URI.create("https://epistola.app/errors/" + slug), slug, status, DETAIL,
                        null, null, extensions),
                List.of(), Map.of(), HttpStatus.valueOf(status), slug, HttpHeaders.EMPTY, new byte[0],
                StandardCharsets.UTF_8);
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
