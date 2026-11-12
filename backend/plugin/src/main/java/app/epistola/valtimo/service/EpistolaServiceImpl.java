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

import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.AttributeDefinition;
import app.epistola.valtimo.domain.CatalogInfo;
import app.epistola.valtimo.domain.CatalogReleases;
import app.epistola.valtimo.domain.CatalogRef;
import app.epistola.valtimo.domain.CatalogTemplates;
import app.epistola.valtimo.domain.DesignScope;
import app.epistola.valtimo.domain.GenerationTargetProblem;
import app.epistola.valtimo.domain.GenerationTargetProblem.Reason;
import app.epistola.valtimo.domain.EnvironmentInfo;
import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GenerationJobResult;
import app.epistola.valtimo.domain.GenerationJobDetail;
import app.epistola.valtimo.domain.GenerationJobStatus;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateInfo;
import app.epistola.valtimo.domain.VariantInfo;
import app.epistola.valtimo.logging.Failures;
import app.epistola.valtimo.richtext.RichTextValues;
import app.epistola.valtimo.schema.JsonSchemaMappingAnalyzer;
import app.epistola.client.api.AttributesApi;
import app.epistola.client.api.CatalogsApi;
import app.epistola.client.api.EnvironmentsApi;
import app.epistola.client.api.GenerationApi;
import app.epistola.client.api.TemplatesApi;
import app.epistola.client.api.VariantsApi;
import app.epistola.client.model.CatalogDto;
import app.epistola.client.model.CatalogReleaseDto;
import app.epistola.client.model.CatalogReleaseListResponse;
import app.epistola.client.model.CatalogReleaseStatus;
import app.epistola.client.model.DeploymentDto;
import app.epistola.client.model.DeploymentListResponse;
import app.epistola.client.model.DocumentGenerationItemDto;
import app.epistola.client.model.DocumentGenerationJobDto;
import app.epistola.client.model.EnvironmentDto;
import app.epistola.client.model.EnvironmentListResponse;
import app.epistola.client.model.GenerateDocumentRequest;
import app.epistola.client.model.GenerationJobItemListResponse;
import app.epistola.client.model.GenerationJobResponse;
import app.epistola.client.error.ProblemDetailException;
import app.epistola.client.model.ImportCatalogResponse;
import app.epistola.client.model.PageMeta;
import app.epistola.client.model.PreviewDocumentRequest;
import app.epistola.client.model.PingRequest;
import app.epistola.client.model.PongDetailsDto;
import app.epistola.client.model.PongResponse;
import app.epistola.client.model.VariantSelectionAttribute;
import app.epistola.client.model.CatalogTemplateDto;
import app.epistola.client.model.CatalogTemplateListResponse;
import app.epistola.client.model.CatalogTemplateRelease;
import app.epistola.client.model.TemplateDto;
import app.epistola.client.model.TemplateListResponse;
import app.epistola.client.model.TemplateSummaryDto;
import app.epistola.client.model.VariantDto;
import app.epistola.client.model.VariantListResponse;
import lombok.extern.slf4j.Slf4j;
import org.semver4j.range.RangeList;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Implementation of EpistolaService using the Epistola REST API client.
 */
@Slf4j
public class EpistolaServiceImpl implements EpistolaService {

    private static final int LIST_PAGE_SIZE = 100;
    private static final int MAX_LIST_PAGES = 100;
    /** What every generated client enum holds for a value it does not know (since contract 2.0). */
    private static final String UNKNOWN_ENUM_VALUE =
            app.epistola.client.model.GenerationItemStatus.unknown_default_open_api.getValue();

    private final EpistolaApiClientFactory apiClientFactory;

    /** Retry attempts (beyond the first try) for idempotent reads on a transient failure. */
    private final int maxReadRetries;

    /** Turns the tagged rich text the plugin's editors submit into documents (ADR 0008). */
    private final RichTextValues richTextValues;

    public EpistolaServiceImpl(EpistolaApiClientFactory apiClientFactory) {
        this(apiClientFactory, 2);
    }

    public EpistolaServiceImpl(EpistolaApiClientFactory apiClientFactory, int maxReadRetries) {
        this(apiClientFactory, maxReadRetries, RichTextValues.withShippedFormats());
    }

    public EpistolaServiceImpl(
            EpistolaApiClientFactory apiClientFactory, int maxReadRetries, RichTextValues richTextValues) {
        this.apiClientFactory = apiClientFactory;
        this.maxReadRetries = maxReadRetries;
        this.richTextValues = richTextValues;
    }

    /**
     * Run an idempotent read, retrying on transient failures — connect/read timeouts and
     * connection errors ({@link ResourceAccessException}) or 5xx responses. 4xx responses
     * and everything else propagate immediately without a retry.
     * <p>
     * The decision is made on the response status rather than the exception class: since the
     * client installs the RFC 9457 status handler, an error carrying {@code problem+json}
     * arrives as {@link ProblemDetailException}, which extends {@link RestClientResponseException}
     * directly and is <em>not</em> an {@code HttpServerErrorException}. Branching on the class
     * would silently stop retrying every problem-shaped 5xx.
     */
    private <T> T withRetry(String operation, Supplier<T> call) {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (RestClientResponseException | ResourceAccessException e) {
                if (e instanceof RestClientResponseException response
                        && !response.getStatusCode().is5xxServerError()) {
                    throw e;
                }
                if (attempt >= maxReadRetries) {
                    throw e;
                }
                attempt++;
                long backoffMs = 200L * (1L << (attempt - 1));
                log.warn("Transient failure on {} (attempt {} of {}): {} — retrying in {}ms",
                        operation, attempt, maxReadRetries + 1, e.getMessage(), backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new EpistolaApiException("Interrupted while retrying " + operation, ie);
                }
            }
        }
    }

    private <T, R> List<T> fetchAllPages(
            String operation,
            Function<Integer, R> fetchPage,
            Function<R, List<T>> itemsExtractor,
            Function<R, PageMeta> pageExtractor
    ) {
        List<T> results = new ArrayList<>();
        int pageNumber = 0;
        while (true) {
            R response = fetchPage.apply(pageNumber);
            if (response == null) {
                return results;
            }

            List<T> items = itemsExtractor.apply(response);
            if (items != null) {
                results.addAll(items);
            }

            PageMeta page = pageExtractor.apply(response);
            if (page == null || page.getTotalPages() <= pageNumber + 1) {
                return results;
            }

            pageNumber++;
            if (pageNumber >= MAX_LIST_PAGES) {
                throw new EpistolaApiException("Refusing to fetch more than " + MAX_LIST_PAGES + " pages for " + operation);
            }
        }
    }

    @Override
    public List<CatalogInfo> getCatalogs(String baseUrl, String apiKey, String tenantId, String environmentId) {
        log.debug("Fetching catalogs for tenant: {}, environment: {}", tenantId, environmentId);
        try {
            return candidateCatalogs(baseUrl, apiKey, tenantId, environmentId).stream()
                    .map(dto -> new CatalogInfo(
                            refOf(dto).toString(),
                            dto.getName(),
                            dto.getRegistry(),
                            dto.getNamespace(),
                            dto.getCatalogKey(),
                            dto.getEditable()))
                    .toList();
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch catalogs for tenant {}: {}", tenantId, e.getMessage());
            throw new EpistolaApiException("Failed to fetch catalogs", e);
        }
    }

    private List<CatalogDto> listCatalogs(String baseUrl, String apiKey, String tenantId) {
        CatalogsApi catalogsApi = apiClientFactory.createCatalogsApi(baseUrl, apiKey);
        return fetchAllPages(
                "catalogs",
                page -> withRetry("listCatalogs",
                        () -> catalogsApi.listCatalogs(tenantId, page, LIST_PAGE_SIZE)),
                response -> response.getItems(),
                response -> response.getPage()
        );
    }

    /**
     * What {@code environmentId} serves: every release of a catalog deployed to it. Dependencies of
     * a deployed release are not in it — the environment uses them without deploying them — unless
     * someone deployed them explicitly.
     */
    private List<DeploymentDto> listDeployments(String baseUrl, String apiKey, String tenantId, String environmentId) {
        EnvironmentsApi environmentsApi = apiClientFactory.createEnvironmentsApi(baseUrl, apiKey);
        try {
            DeploymentListResponse response = withRetry("listEnvironmentDeployments",
                    () -> environmentsApi.listEnvironmentDeployments(tenantId, environmentId));
            return response == null || response.getItems() == null ? List.of() : response.getItems();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new EpistolaApiException("Environment not found: " + environmentId, null, 404, null, Map.of());
            }
            throw e;
        }
    }

    /**
     * The catalogs a selection may name: every catalog of the tenant, or, for an environment, those
     * with a release deployed to it, in the order the tenant lists them.
     */
    private List<CatalogDto> candidateCatalogs(String baseUrl, String apiKey, String tenantId, String environmentId) {
        List<CatalogDto> catalogs = listCatalogs(baseUrl, apiKey, tenantId);
        if (environmentId == null) {
            return catalogs;
        }
        Set<CatalogRef> deployed = listDeployments(baseUrl, apiKey, tenantId, environmentId).stream()
                .map(EpistolaServiceImpl::refOf)
                .collect(Collectors.toSet());
        return catalogs.stream().filter(dto -> deployed.contains(refOf(dto))).toList();
    }

    private static CatalogRef refOf(CatalogDto dto) {
        return new CatalogRef(dto.getRegistry(), dto.getNamespace(), dto.getCatalogKey());
    }

    private static CatalogRef refOf(DeploymentDto dto) {
        return new CatalogRef(dto.getRegistry(), dto.getNamespace(), dto.getCatalogKey());
    }

    /**
     * A catalog as contract 2.0 addresses it, with the release the plugin reads its content from.
     *
     * @param registry  the registry, in full
     * @param namespace the namespace
     * @param key       the catalog key
     * @param release   the release to read templates, variants and attributes from: the one
     *                  generation renders. With a version range, the highest release that satisfies
     *                  it. Otherwise, in an environment, its deployed draft, else its highest
     *                  deployed release; without one, the catalog's highest release, or its newest
     *                  draft when it has never been released
     */
    record ResolvedCatalog(String registry, String namespace, String key, String release) {
    }

    /**
     * Find the catalog a stored reference names among the catalogs a selection may name (see
     * {@link #candidateCatalogs}).
     * <p>
     * A reference saved against contract 1.x is only a key; it is looked up by key. Of several
     * catalogs with that key, it is the one Epistola's 2.0 upgrade named the tenant's own
     * ({@link CatalogRef#select}); without that one it is refused, because guessing would read (and
     * later render) the wrong one. The message names the qualified references to choose from.
     */
    CatalogDto findCatalog(String baseUrl, String apiKey, String tenantId, CatalogRef ref, String environmentId) {
        List<CatalogDto> matches = ref.select(
                candidateCatalogs(baseUrl, apiKey, tenantId, environmentId), EpistolaServiceImpl::refOf, tenantId);
        if (matches.isEmpty()) {
            String message = environmentId == null
                    ? "Catalog not found: " + ref
                    : "Catalog '" + ref + "' is not deployed to environment '" + environmentId + "'";
            throw new EpistolaApiException(message, null, 404, null, Map.of());
        }
        if (matches.size() > 1) {
            List<String> candidates = matches.stream()
                    .map(dto -> refOf(dto).toString())
                    .sorted()
                    .toList();
            throw new EpistolaApiException(
                    "Catalog '" + ref + "' is ambiguous; name it in full, one of " + candidates,
                    null, 409, null, Map.of());
        }
        return matches.get(0);
    }

    /** The catalog a stored reference names, with the release its content is read from. */
    ResolvedCatalog resolveForRead(
            String baseUrl, String apiKey, String tenantId, String catalogId, String environmentId, String versionRange) {
        CatalogRef ref = parseCatalogRef(catalogId);
        RangeList range = versionRange == null ? null : VersionRanges.parse(versionRange);
        if (environmentId != null) {
            return resolveInEnvironment(baseUrl, apiKey, tenantId, ref, environmentId, versionRange, range);
        }
        CatalogDto catalog = findCatalog(baseUrl, apiKey, tenantId, ref, null);
        String release;
        if (range != null) {
            release = releasedVersions(baseUrl, apiKey, tenantId, catalog).stream()
                    .filter(version -> VersionRanges.satisfies(version, range))
                    .findFirst()
                    .orElseThrow(() -> new EpistolaApiException(
                            "No release of catalog '" + refOf(catalog) + "' satisfies '" + versionRange + "'",
                            null, 404, null, Map.of()));
        } else {
            release = catalog.getHighestVersion();
            if (release == null) {
                release = newestDraft(baseUrl, apiKey, tenantId, catalog);
            }
        }
        return new ResolvedCatalog(catalog.getRegistry(), catalog.getNamespace(), catalog.getCatalogKey(), release);
    }

    /**
     * The release an environment renders a catalog from, read from its deployments alone
     * ({@code listEnvironmentDeployments}), so a qualified reference costs one call. With no version
     * range, its deployed draft, else its highest deployed release; with one, the highest deployed
     * release that satisfies it, never the draft.
     */
    private ResolvedCatalog resolveInEnvironment(
            String baseUrl, String apiKey, String tenantId, CatalogRef ref, String environmentId,
            String versionRange, RangeList range) {
        List<DeploymentDto> deployments = deploymentsOf(baseUrl, apiKey, tenantId, ref, environmentId);
        DeploymentDto served;
        if (range != null) {
            served = deployments.stream()
                    .filter(dto -> dto.getStatus() == CatalogReleaseStatus.released)
                    .filter(dto -> VersionRanges.satisfies(dto.getVersion(), range))
                    .max(Comparator.comparing(DeploymentDto::getVersion, ReleaseVersions.PRECEDENCE))
                    .orElseThrow(() -> new EpistolaApiException(
                            "No release of catalog '" + refOf(deployments.get(0)) + "' deployed to environment '"
                                    + environmentId + "' satisfies '" + versionRange + "'",
                            null, 404, null, Map.of()));
        } else {
            served = deployments.stream()
                    .filter(dto -> dto.getStatus() == CatalogReleaseStatus.draft)
                    .findFirst()
                    .orElseGet(() -> deployments.stream()
                            .max(Comparator.comparing(DeploymentDto::getVersion, ReleaseVersions.PRECEDENCE))
                            .orElseThrow());
        }
        return new ResolvedCatalog(served.getRegistry(), served.getNamespace(), served.getCatalogKey(), served.getReleaseId());
    }

    /**
     * The deployments of the one catalog {@code ref} names in an environment: refused with a 404 when
     * none is deployed there, and with a 409 when a bare key matches catalogs in two namespaces,
     * neither of them the one Epistola's 2.0 upgrade named the tenant's own ({@link CatalogRef#select}).
     */
    private List<DeploymentDto> deploymentsOf(
            String baseUrl, String apiKey, String tenantId, CatalogRef ref, String environmentId) {
        List<DeploymentDto> deployments = selectDeployments(
                listDeployments(baseUrl, apiKey, tenantId, environmentId), ref, tenantId, environmentId);
        if (deployments.isEmpty()) {
            throw new EpistolaApiException(
                    "Catalog '" + ref + "' is not deployed to environment '" + environmentId + "'",
                    null, 404, null, Map.of());
        }
        return deployments;
    }

    /**
     * The deployments of the one catalog {@code ref} names among an environment's: empty when none is
     * deployed there, and refused with a 409 when a bare key matches catalogs in two namespaces.
     */
    private static List<DeploymentDto> selectDeployments(
            List<DeploymentDto> all, CatalogRef ref, String tenantId, String environmentId) {
        List<DeploymentDto> deployments = ref.select(all, EpistolaServiceImpl::refOf, tenantId);
        List<String> catalogs = deployments.stream().map(dto -> refOf(dto).toString()).distinct().sorted().toList();
        if (catalogs.size() > 1) {
            throw new EpistolaApiException(
                    "Catalog '" + ref + "' is ambiguous in environment '" + environmentId
                            + "'; name it in full, one of " + catalogs,
                    null, 409, null, Map.of());
        }
        return deployments;
    }

    /** A catalog's released versions, highest first. */
    private List<String> releasedVersions(String baseUrl, String apiKey, String tenantId, CatalogDto catalog) {
        CatalogsApi catalogsApi = apiClientFactory.createCatalogsApi(baseUrl, apiKey);
        List<CatalogReleaseDto> releases = fetchAllPages(
                "catalog releases",
                page -> withRetry("listCatalogReleases", () -> catalogsApi.listCatalogReleases(
                        tenantId, catalog.getRegistry(), catalog.getNamespace(), catalog.getCatalogKey(),
                        CatalogReleaseStatus.released, null, page, LIST_PAGE_SIZE)),
                CatalogReleaseListResponse::getItems,
                CatalogReleaseListResponse::getPage
        );
        return highestFirst(releases.stream().map(CatalogReleaseDto::getVersion).toList());
    }

    private static List<String> highestFirst(List<String> versions) {
        return versions.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(ReleaseVersions.PRECEDENCE.reversed())
                .toList();
    }

    @Override
    public CatalogReleases getReleases(
            String baseUrl, String apiKey, String tenantId, String catalogId, String environmentId, String versionRange) {
        log.debug("Fetching releases for tenant: {}, catalog: {}, environment: {}", tenantId, catalogId, environmentId);
        String environment = blankToNull(environmentId);
        String rangeSource = rangeOrNull(versionRange);
        try {
            CatalogRef ref = parseCatalogRef(catalogId);
            RangeList range = rangeSource == null ? null : VersionRanges.parse(rangeSource);
            List<String> versions;
            boolean draft = false;
            if (environment != null) {
                List<DeploymentDto> deployments = deploymentsOf(baseUrl, apiKey, tenantId, ref, environment);
                versions = highestFirst(deployments.stream()
                        .filter(dto -> dto.getStatus() == CatalogReleaseStatus.released)
                        .map(DeploymentDto::getVersion)
                        .toList());
                draft = range == null && deployments.stream()
                        .anyMatch(dto -> dto.getStatus() == CatalogReleaseStatus.draft);
            } else {
                versions = releasedVersions(baseUrl, apiKey, tenantId, findCatalog(baseUrl, apiKey, tenantId, ref, null));
            }
            String selected = draft ? null : versions.stream()
                    .filter(version -> range == null || VersionRanges.satisfies(version, range))
                    .findFirst()
                    .orElse(null);
            return new CatalogReleases(versions, selected, draft);
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch releases for tenant {}: {}", tenantId, e.getMessage());
            throw toApiException("Failed to fetch catalog releases", e);
        }
    }

    @Override
    public List<CatalogInfo> getCatalogsMarkingDeployment(
            String baseUrl, String apiKey, String tenantId, String environmentId) {
        String environment = blankToNull(environmentId);
        log.debug("Fetching catalogs for tenant: {}, marked against environment: {}", tenantId, environment);
        List<CatalogInfo> catalogs = getCatalogs(baseUrl, apiKey, tenantId, null);
        if (environment == null) {
            return catalogs;
        }
        try {
            Set<String> deployed = listDeployments(baseUrl, apiKey, tenantId, environment).stream()
                    .map(dto -> refOf(dto).toString())
                    .collect(Collectors.toSet());
            return catalogs.stream()
                    .map(catalog -> catalog.deployedToEnvironment(deployed.contains(catalog.id())))
                    .toList();
        } catch (EpistolaApiException e) {
            if (Integer.valueOf(404).equals(e.getHttpStatus())) {
                // An environment that does not exist marks nothing; the design scope says why.
                return catalogs;
            }
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch deployments for tenant {}: {}", tenantId, e.getMessage());
            throw toApiException("Failed to fetch environment deployments", e);
        }
    }

    @Override
    public DesignScope getDesignScope(
            String baseUrl, String apiKey, String tenantId, String catalogId, String environmentId, String versionRange) {
        String environment = blankToNull(environmentId);
        String rangeSource = rangeOrNull(versionRange);
        log.debug("Resolving design scope for tenant: {}, catalog: {}, environment: {}, range: {}",
                tenantId, catalogId, environment, rangeSource);
        try {
            CatalogRef ref = parseCatalogRef(catalogId);
            RangeList range = rangeSource == null ? null : VersionRanges.parse(rangeSource);
            List<DesignScope.Problem> problems = new ArrayList<>();
            if (environment != null) {
                DesignScope inEnvironment = designInEnvironment(
                        baseUrl, apiKey, tenantId, ref, environment, range, problems);
                if (inEnvironment != null) {
                    return inEnvironment;
                }
            }
            return designCatalogWide(baseUrl, apiKey, tenantId, ref, range, problems);
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to resolve design scope for tenant {}: {}", tenantId, e.getMessage());
            throw toApiException("Failed to resolve the catalog release", e);
        }
    }

    /**
     * What {@code environment} renders of the catalog for {@code range}, as generation would pick it,
     * or null — with the reason added to {@code problems} — when it renders nothing of it.
     */
    private DesignScope designInEnvironment(
            String baseUrl, String apiKey, String tenantId, CatalogRef ref, String environment,
            RangeList range, List<DesignScope.Problem> problems) {
        List<DeploymentDto> all;
        try {
            all = listDeployments(baseUrl, apiKey, tenantId, environment);
        } catch (EpistolaApiException e) {
            if (Integer.valueOf(404).equals(e.getHttpStatus())) {
                problems.add(DesignScope.Problem.UNKNOWN_ENVIRONMENT);
                return null;
            }
            throw e;
        }
        List<DeploymentDto> deployments = selectDeployments(all, ref, tenantId, environment);
        if (deployments.isEmpty()) {
            problems.add(DesignScope.Problem.NOT_DEPLOYED);
            return null;
        }
        String qualified = refOf(deployments.get(0)).toString();
        List<DeploymentDto> released = deployments.stream()
                .filter(dto -> dto.getStatus() == CatalogReleaseStatus.released)
                .toList();
        List<String> versions = highestFirst(released.stream().map(DeploymentDto::getVersion).toList());
        if (range != null) {
            // A range never matches a draft (resolveInEnvironment).
            Optional<String> version = versions.stream()
                    .filter(candidate -> VersionRanges.satisfies(candidate, range))
                    .findFirst();
            if (version.isEmpty()) {
                problems.add(DesignScope.Problem.RANGE_NOT_DEPLOYED);
                return null;
            }
            return new DesignScope(qualified, versions, version.get(), false, environment,
                    List.copyOf(problems), new DesignScope.Read(environment, version.get()));
        }
        Optional<DeploymentDto> draft = deployments.stream()
                .filter(dto -> dto.getStatus() == CatalogReleaseStatus.draft)
                .findFirst();
        if (draft.isPresent()) {
            return new DesignScope(qualified, versions, null, true, environment,
                    List.copyOf(problems), new DesignScope.Read(environment, null));
        }
        String highest = deployments.stream()
                .map(DeploymentDto::getVersion)
                .max(ReleaseVersions.PRECEDENCE)
                .orElseThrow();
        return new DesignScope(qualified, versions, highest, false, environment,
                List.copyOf(problems), new DesignScope.Read(environment, null));
    }

    /**
     * The catalog read regardless of environment: the highest release satisfying {@code range}, else
     * — with {@code RANGE_UNSATISFIED} — its highest release, else its newest draft.
     */
    private DesignScope designCatalogWide(
            String baseUrl, String apiKey, String tenantId, CatalogRef ref, RangeList range,
            List<DesignScope.Problem> problems) {
        CatalogDto catalog = findCatalog(baseUrl, apiKey, tenantId, ref, null);
        String qualified = refOf(catalog).toString();
        List<String> versions = releasedVersions(baseUrl, apiKey, tenantId, catalog);
        if (range != null) {
            Optional<String> version = versions.stream()
                    .filter(candidate -> VersionRanges.satisfies(candidate, range))
                    .findFirst();
            if (version.isPresent()) {
                return new DesignScope(qualified, versions, version.get(), false, null,
                        List.copyOf(problems), new DesignScope.Read(null, version.get()));
            }
            problems.add(DesignScope.Problem.RANGE_UNSATISFIED);
        }
        if (!versions.isEmpty()) {
            return new DesignScope(qualified, versions, versions.get(0), false, null,
                    List.copyOf(problems), new DesignScope.Read(null, versions.get(0)));
        }
        newestDraft(baseUrl, apiKey, tenantId, catalog); // refuses a catalog with nothing to read
        return new DesignScope(qualified, versions, null, true, null,
                List.copyOf(problems), new DesignScope.Read(null, null));
    }

    /** How many of a catalog's reachable releases {@link #getCatalogTemplates} reads, newest first. */
    static final int MAX_TEMPLATE_RELEASES = 25;

    /**
     * The templates of released releases, which never change once released: read once per
     * installation, tenant, catalog and release. A draft is always read again. Bounded, so a
     * long-running instance with many catalogs does not grow without end.
     */
    private final Map<String, List<TemplateSummaryDto>> releasedTemplates = Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<TemplateSummaryDto>> eldest) {
                    return size() > 1000;
                }
            });

    /** A release a link can reach: where to read it, how to name it, and whether it is a draft. */
    private record ReachableRelease(String path, String label, boolean draft) {
    }

    @Override
    public CatalogTemplates getCatalogTemplates(
            String baseUrl, String apiKey, String tenantId, String catalogId, String environmentId) {
        String environment = blankToNull(environmentId);
        log.debug("Listing templates across releases for tenant: {}, catalog: {}, environment: {}",
                tenantId, catalogId, environment);
        try {
            CatalogRef ref = parseCatalogRef(catalogId);
            CatalogTemplates listed = listedByServer(baseUrl, apiKey, tenantId, ref, environment);
            if (listed != null) {
                return listed;
            }
            String registry;
            String namespace;
            String key;
            List<ReachableRelease> reachable = new ArrayList<>();
            String readEnvironment = null;
            List<DeploymentDto> deployments = environment == null
                    ? List.of()
                    : selectDeployments(deploymentsOrNone(baseUrl, apiKey, tenantId, environment), ref, tenantId, environment);
            if (!deployments.isEmpty()) {
                // What the environment serves; a catalog it does not serve is read catalog-wide, as
                // the design scope does.
                readEnvironment = environment;
                DeploymentDto first = deployments.get(0);
                registry = first.getRegistry();
                namespace = first.getNamespace();
                key = first.getCatalogKey();
                deployments.stream()
                        .filter(dto -> dto.getStatus() == CatalogReleaseStatus.draft)
                        .findFirst()
                        .ifPresent(draft -> reachable.add(new ReachableRelease(draft.getReleaseId(), "draft", true)));
                deployments.stream()
                        .filter(dto -> dto.getStatus() == CatalogReleaseStatus.released)
                        .sorted(Comparator.comparing(DeploymentDto::getVersion, ReleaseVersions.PRECEDENCE).reversed())
                        .forEach(dto -> reachable.add(new ReachableRelease(dto.getReleaseId(), dto.getVersion(), false)));
            } else {
                CatalogDto catalog = findCatalog(baseUrl, apiKey, tenantId, ref, null);
                registry = catalog.getRegistry();
                namespace = catalog.getNamespace();
                key = catalog.getCatalogKey();
                List<String> versions = releasedVersions(baseUrl, apiKey, tenantId, catalog);
                if (versions.isEmpty()) {
                    reachable.add(new ReachableRelease(newestDraft(baseUrl, apiKey, tenantId, catalog), "draft", true));
                } else {
                    versions.forEach(version -> reachable.add(new ReachableRelease(version, version, false)));
                }
            }
            boolean truncated = reachable.size() > MAX_TEMPLATE_RELEASES;
            List<ReachableRelease> read = truncated ? reachable.subList(0, MAX_TEMPLATE_RELEASES) : reachable;

            TemplatesApi templatesApi = apiClientFactory.createTemplatesApi(baseUrl, apiKey);
            Map<String, CatalogTemplates.Template> byKey = new java.util.LinkedHashMap<>();
            Map<String, List<String>> releasesByKey = new java.util.LinkedHashMap<>();
            for (ReachableRelease release : read) {
                for (TemplateSummaryDto dto : templatesOf(templatesApi, baseUrl, tenantId, registry, namespace, key, release)) {
                    releasesByKey.computeIfAbsent(dto.getKey(), k -> new ArrayList<>()).add(release.label());
                    if (!byKey.containsKey(dto.getKey())) {
                        // The highest release containing it: where its name, fields and variants come from.
                        DesignScope.Read readSelection = release.draft()
                                ? new DesignScope.Read(readEnvironment, null)
                                : new DesignScope.Read(readEnvironment, release.label());
                        byKey.put(dto.getKey(), new CatalogTemplates.Template(
                                dto.getKey(), dto.getName(), dto.getDescription(), List.of(), readSelection));
                    }
                }
            }
            List<CatalogTemplates.Template> templates = byKey.values().stream()
                    .map(t -> new CatalogTemplates.Template(t.id(), t.name(), t.description(),
                            List.copyOf(releasesByKey.get(t.id())), t.read()))
                    .sorted(Comparator.comparing(CatalogTemplates.Template::name,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .toList();
            return new CatalogTemplates(new CatalogRef(registry, namespace, key).toString(), templates, truncated);
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to list templates across releases for tenant {} ({})", tenantId, Failures.describe(e));
            throw toApiException("Failed to list templates across releases", e);
        }
    }

    /**
     * The templates across releases as the server lists them ({@code listCatalogTemplates}): one call
     * instead of one per release. Contract 2.0.0 lets a Suite answer 501 until it implements it, so
     * null — and the caller lists release by release — when the server does not (501, or 404/405 from
     * a pre-release server without the route), and when an environment lists nothing, which may only
     * mean the catalog is not deployed there: that is read catalog-wide, as the design scope reads it.
     */
    private CatalogTemplates listedByServer(
            String baseUrl, String apiKey, String tenantId, CatalogRef ref, String environment) {
        CatalogRef catalog = ref.isQualified() ? ref : refOf(findCatalog(baseUrl, apiKey, tenantId, ref, null));
        TemplatesApi templatesApi = apiClientFactory.createTemplatesApi(baseUrl, apiKey);
        List<CatalogTemplateDto> items;
        try {
            items = fetchAllPages(
                    "catalog templates",
                    page -> templatesApi.listCatalogTemplates(tenantId, catalog.registry(), catalog.namespace(),
                            catalog.key(), environment, null, page, LIST_PAGE_SIZE),
                    CatalogTemplateListResponse::getItems,
                    CatalogTemplateListResponse::getPage);
        } catch (Exception e) {
            if (environment != null && hasStatus(e, 404)) {
                // An environment that does not exist renders nothing: read catalog-wide, as the
                // design scope does (UNKNOWN_ENVIRONMENT). A server without the route answers 404
                // again, and the caller lists per release.
                log.debug("listCatalogTemplates found no environment '{}'; listing catalog-wide", environment);
                return listedByServer(baseUrl, apiKey, tenantId, ref, null);
            }
            if (isNotImplemented(e)) {
                log.debug("listCatalogTemplates not available on this server ({}); listing per release",
                        Failures.describe(e));
                return null;
            }
            throw e;
        }
        if (environment != null && items.isEmpty()) {
            return null;
        }
        List<CatalogTemplates.Template> templates = items.stream()
                .filter(dto -> dto.getReleases() != null && !dto.getReleases().isEmpty())
                .map(dto -> {
                    List<String> releases = dto.getReleases().stream()
                            .map(release -> release.getStatus() == CatalogReleaseStatus.draft ? "draft" : release.getVersion())
                            .toList();
                    CatalogTemplateRelease highest = dto.getReleases().get(0);
                    DesignScope.Read read = highest.getStatus() == CatalogReleaseStatus.draft
                            ? new DesignScope.Read(environment, null)
                            : new DesignScope.Read(environment, highest.getVersion());
                    return new CatalogTemplates.Template(dto.getKey(), dto.getName(), dto.getDescription(), releases, read);
                })
                .sorted(Comparator.comparing(CatalogTemplates.Template::name,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        return new CatalogTemplates(catalog.toString(), templates, false);
    }

    /** A server answering that it does not implement an operation, or does not know its route. */
    private static boolean isNotImplemented(Throwable failure) {
        return hasStatus(failure, 501) || hasStatus(failure, 404) || hasStatus(failure, 405);
    }

    /** Whether {@code failure}, or a cause of it, is an HTTP answer with {@code status}. */
    private static boolean hasStatus(Throwable failure, int status) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            int answered = t instanceof ProblemDetailException problem ? problem.getProblemStatus()
                    : t instanceof RestClientResponseException response ? response.getStatusCode().value()
                    : t instanceof EpistolaApiException api && api.getHttpStatus() != null ? api.getHttpStatus()
                    : -1;
            if (answered == status) {
                return true;
            }
        }
        return false;
    }

    /** What {@code environment} serves, or nothing when it does not exist (404). */
    private List<DeploymentDto> deploymentsOrNone(String baseUrl, String apiKey, String tenantId, String environment) {
        try {
            return listDeployments(baseUrl, apiKey, tenantId, environment);
        } catch (EpistolaApiException e) {
            if (Integer.valueOf(404).equals(e.getHttpStatus())) {
                return List.of();
            }
            throw e;
        }
    }

    private List<TemplateSummaryDto> templatesOf(
            TemplatesApi templatesApi, String baseUrl, String tenantId,
            String registry, String namespace, String key, ReachableRelease release) {
        java.util.function.Supplier<List<TemplateSummaryDto>> fetch = () -> fetchAllPages(
                "templates",
                page -> templatesApi.listTemplates(tenantId, registry, namespace, key, release.path(),
                        null, page, LIST_PAGE_SIZE, null, null),
                TemplateListResponse::getItems,
                TemplateListResponse::getPage);
        if (release.draft()) {
            return fetch.get();
        }
        String cacheKey = String.join("|", baseUrl, tenantId, registry, namespace, key, release.path());
        List<TemplateSummaryDto> cached = releasedTemplates.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<TemplateSummaryDto> fetched = List.copyOf(fetch.get());
        releasedTemplates.put(cacheKey, fetched);
        return fetched;
    }

    private String newestDraft(String baseUrl, String apiKey, String tenantId, CatalogDto catalog) {
        CatalogsApi catalogsApi = apiClientFactory.createCatalogsApi(baseUrl, apiKey);
        List<CatalogReleaseDto> drafts = fetchAllPages(
                "catalog releases",
                page -> withRetry("listCatalogReleases", () -> catalogsApi.listCatalogReleases(
                        tenantId, catalog.getRegistry(), catalog.getNamespace(), catalog.getCatalogKey(),
                        CatalogReleaseStatus.draft, null, page, LIST_PAGE_SIZE)),
                CatalogReleaseListResponse::getItems,
                CatalogReleaseListResponse::getPage
        );
        return drafts.stream()
                .max(Comparator.comparing(CatalogReleaseDto::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(CatalogReleaseDto::getReleaseId)
                .orElseThrow(() -> new EpistolaApiException(
                        "Catalog " + refOf(catalog) + " has no release to read templates from",
                        null, 404, null, Map.of()));
    }

    /**
     * The catalog a generation or preview names. A qualified reference is sent as it is, with no
     * lookup; only a key saved against contract 1.x costs a list call, and is looked up among what
     * the environment serves when there is one.
     */
    private CatalogRef resolveForRender(String baseUrl, String apiKey, String tenantId, String catalogId, String environmentId) {
        CatalogRef ref = parseCatalogRef(catalogId);
        if (ref.isQualified()) {
            return ref;
        }
        return refOf(findCatalog(baseUrl, apiKey, tenantId, ref, blankToNull(environmentId)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** A version range as a request carries it: trimmed, and null when there is none. */
    private static String rangeOrNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static CatalogRef parseCatalogRef(String catalogId) {
        try {
            return CatalogRef.parse(catalogId);
        } catch (IllegalArgumentException e) {
            throw new EpistolaApiException(e.getMessage(), null, 400, null, Map.of());
        }
    }

    @Override
    public SystemInfo getSystemInfo(String baseUrl, String apiKey) {
        log.debug("Fetching Epistola system metadata");
        try {
            PongResponse response = withRetry("fetch system metadata", () ->
                    apiClientFactory.createSystemApi(baseUrl, apiKey).ping(new PingRequest()));
            PongDetailsDto details = response != null ? response.getDetails() : null;
            return new SystemInfo(
                    details != null ? details.getServerVersion() : null,
                    details != null ? details.getApiVersion() : null
            );
        } catch (Exception e) {
            log.debug("Failed to fetch Epistola system metadata: {}", e.getMessage());
            throw new EpistolaApiException("Failed to fetch system metadata", e);
        }
    }

    @Override
    public List<TemplateInfo> getTemplates(
            String baseUrl, String apiKey, String tenantId, String catalogId, String environmentId, String versionRange) {
        log.debug("Fetching templates for tenant: {}, catalog: {}", tenantId, catalogId);
        try {
            ResolvedCatalog catalog = resolveForRead(
                    baseUrl, apiKey, tenantId, catalogId, blankToNull(environmentId), rangeOrNull(versionRange));
            TemplatesApi templatesApi = apiClientFactory.createTemplatesApi(baseUrl, apiKey);
            var templates = fetchAllPages(
                    "templates",
                    page -> templatesApi.listTemplates(tenantId, catalog.registry(), catalog.namespace(),
                            catalog.key(), catalog.release(), null, page, LIST_PAGE_SIZE, null, null),
                    TemplateListResponse::getItems,
                    TemplateListResponse::getPage
            );

            return templates.stream()
                    .map(dto -> mapToTemplateInfo(dto, catalogId))
                    .toList();
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch templates for tenant {}: {}", tenantId, e.getMessage());
            throw toApiException("Failed to fetch templates", e);
        }
    }

    @Override
    public TemplateDetails getTemplateDetails(
            String baseUrl, String apiKey, String tenantId, String catalogId, String templateId,
            String environmentId, String versionRange) {
        log.debug("Fetching template details for tenant: {}, catalog: {}, template: {}", tenantId, catalogId, templateId);
        try {
            ResolvedCatalog catalog = resolveForRead(
                    baseUrl, apiKey, tenantId, catalogId, blankToNull(environmentId), rangeOrNull(versionRange));
            TemplatesApi templatesApi = apiClientFactory.createTemplatesApi(baseUrl, apiKey);
            TemplateDto response = templatesApi.getTemplate(tenantId, catalog.registry(), catalog.namespace(),
                    catalog.key(), catalog.release(), templateId);

            if (response == null) {
                throw new EpistolaApiException("Template not found: " + templateId);
            }

            return mapToTemplateDetails(response);
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch template details for tenant {}, template {}: {}", tenantId, templateId, e.getMessage());
            throw toApiException("Failed to fetch template details", e);
        }
    }

    @Override
    public List<AttributeDefinition> getAttributes(
            String baseUrl, String apiKey, String tenantId, String catalogId, String environmentId, String versionRange) {
        log.debug("Fetching attribute definitions for tenant: {}, catalog: {}", tenantId, catalogId);
        try {
            ResolvedCatalog catalog = resolveForRead(
                    baseUrl, apiKey, tenantId, catalogId, blankToNull(environmentId), rangeOrNull(versionRange));
            AttributesApi attributesApi = apiClientFactory.createAttributesApi(baseUrl, apiKey);
            var attributes = fetchAllPages(
                    "attributes",
                    page -> attributesApi.listAttributes(tenantId, catalog.registry(), catalog.namespace(),
                            catalog.key(), catalog.release(), page, LIST_PAGE_SIZE),
                    response -> response.getItems(),
                    response -> response.getPage()
            );

            return attributes.stream()
                    .map(dto -> new AttributeDefinition(dto.getKey(), dto.getName()))
                    .toList();
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch attribute definitions for tenant {}: {}", tenantId, e.getMessage());
            throw toApiException("Failed to fetch attribute definitions", e);
        }
    }

    @Override
    public List<EnvironmentInfo> getEnvironments(String baseUrl, String apiKey, String tenantId) {
        log.debug("Fetching environments for tenant: {}", tenantId);
        try {
            EnvironmentsApi environmentsApi = apiClientFactory.createEnvironmentsApi(baseUrl, apiKey);
            var environments = fetchAllPages(
                    "environments",
                    page -> environmentsApi.listEnvironments(tenantId, page, LIST_PAGE_SIZE),
                    EnvironmentListResponse::getItems,
                    EnvironmentListResponse::getPage
            );

            return environments.stream()
                    .map(this::mapToEnvironmentInfo)
                    .toList();
        } catch (Exception e) {
            log.error("Failed to fetch environments for tenant {}: {}", tenantId, e.getMessage());
            throw new EpistolaApiException("Failed to fetch environments", e);
        }
    }

    @Override
    public List<VariantInfo> getVariants(
            String baseUrl, String apiKey, String tenantId, String catalogId, String templateId,
            String environmentId, String versionRange) {
        log.debug("Fetching variants for tenant: {}, catalog: {}, template: {}", tenantId, catalogId, templateId);
        try {
            ResolvedCatalog catalog = resolveForRead(
                    baseUrl, apiKey, tenantId, catalogId, blankToNull(environmentId), rangeOrNull(versionRange));
            VariantsApi variantsApi = apiClientFactory.createVariantsApi(baseUrl, apiKey);
            var variants = fetchAllPages(
                    "variants",
                    page -> variantsApi.listVariants(tenantId, catalog.registry(), catalog.namespace(),
                            catalog.key(), catalog.release(), templateId, page, LIST_PAGE_SIZE),
                    VariantListResponse::getItems,
                    VariantListResponse::getPage
            );

            return variants.stream()
                    .map(this::mapToVariantInfo)
                    .toList();
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch variants for tenant {}, template {}: {}", tenantId, templateId, e.getMessage());
            throw toApiException("Failed to fetch variants", e);
        }
    }

    @Override
    public GenerationJobResult submitGenerationJob(
            String baseUrl,
            String apiKey,
            String tenantId,
            String catalogId,
            String templateId,
            String variantId,
            List<VariantSelectionAttribute> variantAttributes,
            String environmentId,
            String versionRange,
            Map<String, Object> data,
            FileFormat format,
            String filename,
            String correlationId,
            String routingKey
    ) {
        log.debug("Submitting document generation request: tenantId={}, templateId={}, variantId={}, attributes={}, versionRange={}, format={}, filename={}, routingKey={}",
                tenantId, templateId, variantId, variantAttributes, versionRange, format, filename, routingKey);
        // Never the data itself: it is a letter's contents — names, identifiers, addresses, the
        // text of a decision. The keys say what was sent, which is what a log is for here.
        log.debug("Template data keys: {}", data == null ? "none" : data.keySet());
        // Outside the try: a value its own format cannot read is the caller's to report, with the
        // field it names, not an API failure. The message names the field, never the text.
        Map<String, Object> documents =
                richTextValues.toDocuments(data, templateId, RichTextValues.Purpose.GENERATION);

        try {
            CatalogRef catalog = resolveForRender(baseUrl, apiKey, tenantId, catalogId, environmentId);
            GenerationApi generationApi = apiClientFactory.createGenerationApi(baseUrl, apiKey);

            // Build the request using the constructor (Kotlin data class - immutable)
            // When neither variantId nor variantAttributes is set, Epistola server
            // resolves the default variant automatically (since v0.4.x).
            GenerateDocumentRequest request = new GenerateDocumentRequest(
                    catalog.namespace(),
                    catalog.key(),
                    templateId,
                    documents,
                    catalog.registry(), // null - the server's default registry
                    variantId,          // nullable - omit when using attribute-based selection
                    variantAttributes,  // nullable - for attribute-based variant selection
                    rangeOrNull(versionRange), // null: the environment's draft, else its highest release
                    environmentId,
                    filename,
                    correlationId,
                    routingKey          // routes the result back to the submitting collector node
            );

            GenerationJobResponse response = generationApi.generateDocument(tenantId, request);

            log.debug("Document generation request submitted: requestId={}, status={}",
                    response.getRequestId(), response.getStatus());

            return GenerationJobResult.builder()
                    .requestId(response.getRequestId().toString())
                    .status(GenerationJobStatus.normalize(response.getStatus().getValue()))
                    .build();
        } catch (Exception e) {
            EpistolaApiException refused = refusal(e, "Failed to submit document generation request",
                    baseUrl, apiKey, tenantId, catalogId, templateId, variantId, environmentId, versionRange);
            log.error("Failed to submit document generation request ({})", describeRefusal(refused));
            throw refused;
        }
    }

    @Override
    public GenerationJobDetail getJobStatus(String baseUrl, String apiKey, String tenantId, String requestId) {
        log.debug("Fetching job status for tenant: {}, requestId: {}", tenantId, requestId);
        try {
            GenerationApi generationApi = apiClientFactory.createGenerationApi(baseUrl, apiKey);
            UUID requestUuid = UUID.fromString(requestId);
            // The plugin submits one document per job, so its first item is the letter: its status,
            // document and error. A job lists its items apart from its status since contract
            // 2.0.0-alpha.4; before an item is listed, the job's own status stands in.
            GenerationJobItemListResponse items = withRetry("getJobStatus",
                    () -> generationApi.listGenerationJobItems(tenantId, requestUuid, null, 0, 1));
            if (items != null && items.getItems() != null && !items.getItems().isEmpty()) {
                return mapToGenerationJobDetail(items.getItems().get(0), requestId);
            }

            DocumentGenerationJobDto job = withRetry("getJobStatus",
                    () -> generationApi.getGenerationJobStatus(tenantId, requestUuid));
            if (job == null) {
                throw new EpistolaApiException("Job not found: " + requestId);
            }
            return mapToGenerationJobDetail(job, requestId);
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch job status for tenant {}, requestId {}: {}", tenantId, requestId, e.getMessage());
            throw new EpistolaApiException("Failed to fetch job status", e);
        }
    }

    @Override
    public byte[] downloadDocument(String baseUrl, String apiKey, String tenantId, String documentId) {
        log.debug("Downloading document for tenant: {}, documentId: {}", tenantId, documentId);
        UUID id = parseDocumentId(documentId);
        try {
            // The long-running client: a document download has no meaningful read timeout.
            Resource resource = withRetry("downloadDocument", () ->
                    apiClientFactory.createLongRunningGenerationApi(baseUrl, apiKey)
                            .downloadDocument(tenantId, id));

            byte[] content = resource.getContentAsByteArray();
            if (content.length == 0) {
                throw new EpistolaApiException("Downloaded document is empty: " + documentId);
            }

            log.debug("Downloaded document {} ({} bytes)", documentId, content.length);
            return content;
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to download document for tenant {}, documentId {}: {}", tenantId, documentId, e.getMessage());
            throw toApiException("Failed to download document", e);
        }
    }

    /**
     * The generated client types {@code documentId} as a {@link UUID}. Rejecting a malformed id
     * here keeps the failure a 400-shaped {@link EpistolaApiException} instead of an
     * {@link IllegalArgumentException} escaping as a 500.
     */
    private UUID parseDocumentId(String documentId) {
        try {
            return UUID.fromString(documentId);
        } catch (IllegalArgumentException e) {
            throw new EpistolaApiException("Not a valid Epistola document id: " + documentId, e, 400, null, Map.of());
        }
    }

    public ImportCatalogResult importCatalog(
            String baseUrl, String apiKey, String tenantId, byte[] zipBytes, String registry, String namespace) {
        log.info("Importing catalog ZIP ({} bytes) for tenant: {}, registry: {}, namespace: {}",
                zipBytes.length, tenantId, registry, namespace);
        try {
            Resource zipResource = new ByteArrayResource(zipBytes) {
                @Override
                public String getFilename() {
                    return "catalog.zip";
                }
            };

            // Contract 2.0 installs the release the archive carries, beside the catalog's others.
            // Registry and namespace name the catalog for an archive from before wire v8, which
            // has neither; a v8 archive names its own, and they are left out.
            ImportCatalogResponse response = apiClientFactory.createLongRunningCatalogsApi(baseUrl, apiKey)
                    .importCatalog(tenantId, zipResource, registry, namespace);

            log.info("Catalog import completed for tenant: {}, catalog={}/{}/{}, version={}, newlyInstalled={}, total={}",
                    tenantId, response.getRegistry(), response.getNamespace(), response.getCatalogKey(),
                    response.getVersion(), response.getNewlyInstalled(), response.getTotal());

            return new ImportCatalogResult(
                    response.getRegistry(),
                    response.getNamespace(),
                    response.getCatalogKey(),
                    response.getCatalogName(),
                    response.getVersion(),
                    response.getNewlyInstalled(),
                    response.getTotal());
        } catch (EpistolaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to import catalog for tenant {}: {}", tenantId, e.getMessage());
            // Preserves the downstream RFC-9457 problem detail (covers 4xx and 5xx): the suite's
            // import endpoint returns a structured body (e.g. catalog-schema-too-old with
            // version/baselineVersion) that callers translate and map to a correct status class.
            throw toApiException("Failed to import catalog", e);
        }
    }

    public java.io.InputStream previewDocument(
            String baseUrl, String apiKey, String tenantId,
            String catalogId, String templateId, String variantId, String environmentId, String versionRange,
            Map<String, Object> data
    ) {
        log.debug("Previewing document for tenant: {}, catalog: {}, template: {}", tenantId, catalogId, templateId);
        Map<String, Object> documents =
                richTextValues.toDocuments(data, templateId, RichTextValues.Purpose.PREVIEW);
        try {
            CatalogRef catalog = resolveForRender(baseUrl, apiKey, tenantId, catalogId, environmentId);
            var request = new PreviewDocumentRequest(
                    catalog.namespace(),
                    catalog.key(),
                    templateId,
                    documents,
                    catalog.registry(),
                    variantId,
                    null,           // attributes — the plugin selects a variant explicitly or by default
                    rangeOrNull(versionRange), // null: the environment's draft, else its highest release
                    environmentId,
                    null);          // draft — the plugin previews what generation would render

            // The long-running client: a preview render has no meaningful read timeout.
            Resource resource = apiClientFactory.createLongRunningGenerationApi(baseUrl, apiKey)
                    .previewDocument(tenantId, request);

            byte[] content = resource.getContentAsByteArray();
            if (content.length == 0) {
                throw new EpistolaApiException("Preview returned empty content");
            }

            log.debug("Preview generated for tenant: {}, template: {}", tenantId, templateId);
            return new java.io.ByteArrayInputStream(content);
        } catch (Exception e) {
            EpistolaApiException refused = refusal(e, "Failed to preview document",
                    baseUrl, apiKey, tenantId, catalogId, templateId, variantId, environmentId, versionRange);
            log.debug("Failed to preview document for tenant {} ({})", tenantId, describeRefusal(refused));
            throw refused;
        }
    }

    /**
     * What a failed submit or preview is. A <em>refusal</em> is Epistola answering the request itself
     * with an error — nothing accepted, no job — as opposed to a job it accepted that fails later
     * while rendering, which arrives as a {@code FAILED} result. A refusal of a target that does not
     * exist becomes an
     * {@link EpistolaTargetNotFoundException} saying which part (ADR 0009, addendum); anything else
     * keeps Epistola's status and problem type, so a caller can tell permanent (4xx) from transient.
     */
    private EpistolaApiException refusal(
            Exception failure, String fallbackMessage, String baseUrl, String apiKey, String tenantId,
            String catalogId, String templateId, String variantId, String environmentId, String versionRange) {
        EpistolaApiException api = failure instanceof EpistolaApiException known
                ? known
                : toApiException(fallbackMessage, failure);
        if (failure instanceof EpistolaTargetNotFoundException || !Integer.valueOf(404).equals(api.getHttpStatus())) {
            return api;
        }
        GenerationTargetProblem problem = diagnoseNotFound(baseUrl, apiKey, tenantId, catalogId, templateId,
                variantId, blankToNull(environmentId), rangeOrNull(versionRange), api);
        return new EpistolaTargetNotFoundException(problem, epistolaDetailOf(failure), api.getProblemType());
    }

    /** Epistola's own text about a failure, when it sent one: for the case, never for a log. */
    private static String epistolaDetailOf(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ProblemDetailException problem) {
                return problem.getDetail() != null ? problem.getDetail() : problem.getTitle();
            }
        }
        return null;
    }

    /** A refusal as a log may name it: the plugin's own reason, else the failure's class and status. */
    private static String describeRefusal(EpistolaApiException refused) {
        if (refused instanceof EpistolaTargetNotFoundException notFound) {
            return notFound.getProblem().reason() + ": " + notFound.getMessage();
        }
        return Failures.describe(refused) + (refused.getHttpStatus() == null ? "" : ", status " + refused.getHttpStatus());
    }

    /**
     * Which part of a generation target does not exist. Epistola's {@code not-found} names it in a
     * {@code missing} member, which contract 2.0.0 makes optional, so a server may leave it out; otherwise
     * the plugin checks the parts in the order Epistola resolves them — environment, catalog,
     * release, template, variant — with the reads it already makes, and stops at the first that is
     * not there. Only ever after a refusal, so the extra calls cost nothing on the normal path. A
     * diagnosis that cannot be completed is {@link Reason#UNKNOWN}, never a second failure.
     */
    GenerationTargetProblem diagnoseNotFound(
            String baseUrl, String apiKey, String tenantId, String catalogId, String templateId,
            String variantId, String environmentId, String versionRange, EpistolaApiException refusal) {
        Diagnosis target = new Diagnosis(catalogId, templateId, variantId, environmentId, versionRange);
        if ("no-matching-variant".equals(refusal.getProblemTypeSlug())) {
            return target.problem(Reason.NO_MATCHING_VARIANT, null);
        }
        GenerationTargetProblem stated = fromMissingMember(target, refusal.getProblemExtensions().get("missing"));
        if (stated != null) {
            return stated;
        }
        try {
            return diagnoseByReading(baseUrl, apiKey, tenantId, target);
        } catch (Exception e) {
            log.debug("Could not tell what Epistola did not find ({})", Failures.describe(e));
            return target.problem(Reason.UNKNOWN, null);
        }
    }

    private record Diagnosis(
            String catalogId, String templateId, String variantId, String environmentId, String versionRange) {
        GenerationTargetProblem problem(Reason reason, String release) {
            return new GenerationTargetProblem(
                    reason, catalogId, templateId, variantId, environmentId, versionRange, release);
        }
    }

    private static GenerationTargetProblem fromMissingMember(Diagnosis target, Object missing) {
        if (!(missing instanceof Map<?, ?> member) || !(member.get("kind") instanceof String kind)) {
            return null;
        }
        String release = member.get("release") instanceof String named ? named : null;
        Reason reason = switch (kind) {
            case "environment" -> Reason.ENVIRONMENT_NOT_FOUND;
            case "catalog" -> Reason.CATALOG_NOT_FOUND;
            case "release" -> target.versionRange() != null
                    ? Reason.RANGE_UNSATISFIED
                    : target.environmentId() != null ? Reason.NOT_DEPLOYED : Reason.NOT_RELEASED;
            case "template" -> Reason.TEMPLATE_NOT_IN_RELEASE;
            case "variant" -> Reason.VARIANT_NOT_IN_RELEASE;
            default -> null; // a kind this plugin does not know yet: check for itself
        };
        return reason == null ? null : target.problem(reason, release);
    }

    private GenerationTargetProblem diagnoseByReading(
            String baseUrl, String apiKey, String tenantId, Diagnosis target) {
        CatalogRef ref = parseCatalogRef(target.catalogId());
        RangeList range = target.versionRange() == null ? null : VersionRanges.parse(target.versionRange());
        String registry;
        String namespace;
        String key;
        String releasePath;
        String releaseLabel;
        if (target.environmentId() != null) {
            List<DeploymentDto> all;
            try {
                all = listDeployments(baseUrl, apiKey, tenantId, target.environmentId());
            } catch (EpistolaApiException e) {
                if (Integer.valueOf(404).equals(e.getHttpStatus())) {
                    return target.problem(Reason.ENVIRONMENT_NOT_FOUND, null);
                }
                throw e;
            }
            List<DeploymentDto> deployments = selectDeployments(all, ref, tenantId, target.environmentId());
            if (deployments.isEmpty()) {
                boolean exists = !ref.select(listCatalogs(baseUrl, apiKey, tenantId),
                        EpistolaServiceImpl::refOf, tenantId).isEmpty();
                return target.problem(exists ? Reason.NOT_DEPLOYED : Reason.CATALOG_NOT_FOUND, null);
            }
            DeploymentDto served;
            if (range != null) {
                Optional<DeploymentDto> satisfying = deployments.stream()
                        .filter(dto -> dto.getStatus() == CatalogReleaseStatus.released)
                        .filter(dto -> VersionRanges.satisfies(dto.getVersion(), range))
                        .max(Comparator.comparing(DeploymentDto::getVersion, ReleaseVersions.PRECEDENCE));
                if (satisfying.isEmpty()) {
                    return target.problem(Reason.RANGE_UNSATISFIED, null);
                }
                served = satisfying.get();
            } else {
                served = deployments.stream()
                        .filter(dto -> dto.getStatus() == CatalogReleaseStatus.draft)
                        .findFirst()
                        .orElseGet(() -> deployments.stream()
                                .max(Comparator.comparing(DeploymentDto::getVersion, ReleaseVersions.PRECEDENCE))
                                .orElseThrow());
            }
            registry = served.getRegistry();
            namespace = served.getNamespace();
            key = served.getCatalogKey();
            releasePath = served.getReleaseId();
            releaseLabel = served.getStatus() == CatalogReleaseStatus.draft ? "draft" : served.getVersion();
        } else {
            List<CatalogDto> matches = ref.select(listCatalogs(baseUrl, apiKey, tenantId),
                    EpistolaServiceImpl::refOf, tenantId);
            if (matches.size() != 1) {
                return target.problem(matches.isEmpty() ? Reason.CATALOG_NOT_FOUND : Reason.UNKNOWN, null);
            }
            CatalogDto catalog = matches.get(0);
            List<String> versions = releasedVersions(baseUrl, apiKey, tenantId, catalog);
            if (range != null) {
                Optional<String> satisfying = versions.stream()
                        .filter(version -> VersionRanges.satisfies(version, range))
                        .findFirst();
                if (satisfying.isEmpty()) {
                    return target.problem(Reason.RANGE_UNSATISFIED, null);
                }
                releasePath = satisfying.get();
                releaseLabel = releasePath;
            } else if (!versions.isEmpty()) {
                releasePath = versions.get(0);
                releaseLabel = releasePath;
            } else {
                try {
                    releasePath = newestDraft(baseUrl, apiKey, tenantId, catalog);
                } catch (EpistolaApiException e) {
                    return target.problem(Reason.NOT_RELEASED, null);
                }
                releaseLabel = "draft";
            }
            registry = catalog.getRegistry();
            namespace = catalog.getNamespace();
            key = catalog.getCatalogKey();
        }

        try {
            apiClientFactory.createTemplatesApi(baseUrl, apiKey)
                    .getTemplate(tenantId, registry, namespace, key, releasePath, target.templateId());
        } catch (Exception e) {
            if (isNotFound(e)) {
                return target.problem(Reason.TEMPLATE_NOT_IN_RELEASE, releaseLabel);
            }
            throw e;
        }
        if (target.variantId() != null && !target.variantId().isBlank()) {
            VariantsApi variantsApi = apiClientFactory.createVariantsApi(baseUrl, apiKey);
            boolean present = fetchAllPages(
                    "variants",
                    page -> variantsApi.listVariants(tenantId, registry, namespace, key, releasePath,
                            target.templateId(), page, LIST_PAGE_SIZE),
                    VariantListResponse::getItems,
                    VariantListResponse::getPage
            ).stream().anyMatch(variant -> target.variantId().equals(variant.getKey()));
            if (!present) {
                return target.problem(Reason.VARIANT_NOT_IN_RELEASE, releaseLabel);
            }
        }
        return target.problem(Reason.UNKNOWN, releaseLabel);
    }

    private static boolean isNotFound(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ProblemDetailException problem && problem.getProblemStatus() == 404) {
                return true;
            }
            if (t instanceof RestClientResponseException response && response.getStatusCode().value() == 404) {
                return true;
            }
            if (t instanceof EpistolaApiException api && Integer.valueOf(404).equals(api.getHttpStatus())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wrap a downstream failure, preserving the RFC 9457 problem detail when there is one.
     * <p>
     * The client's status handler parses {@code application/problem+json} into a
     * {@link ProblemDetailException}, so the type URI and the extension members Epistola sends
     * (for example {@code version} / {@code baselineVersion} on {@code catalog-schema-too-old})
     * arrive already parsed; callers translate them and map the failure to a correct status
     * class. A non-problem error body keeps its status but carries no type or extensions.
     * <p>
     * Any other failure says what it was after the fallback message — a status and content type
     * for an error that is no problem response (a proxy's error page reads {@code HTTP 502 Bad
     * Gateway (text/html)}), the exception types otherwise — so the letter composer, which shows
     * this message, tells an unreachable host from a gateway from an unreadable response. Never a
     * response body or a downstream message: this message reaches logs, and either may quote case
     * data (see "Never log personal data" in {@code CLAUDE.md}).
     */
    private EpistolaApiException toApiException(String fallbackMessage, Throwable cause) {
        if (cause instanceof ProblemDetailException problem) {
            String message = problem.getDetail() != null ? problem.getDetail() : problem.getTitle();
            return new EpistolaApiException(
                    message != null ? message : fallbackMessage,
                    problem,
                    problem.getProblemStatus(),
                    problem.getType().toString(),
                    problem.getExtensions());
        }
        if (cause instanceof RestClientResponseException response) {
            return new EpistolaApiException(
                    fallbackMessage + ": " + describeStatus(response),
                    response,
                    response.getStatusCode().value(),
                    null,
                    Map.of());
        }
        return new EpistolaApiException(fallbackMessage + ": " + Failures.describe(cause), cause);
    }

    /** {@code HTTP 502 Bad Gateway (text/html)}: the status line and content type, never the body. */
    private static String describeStatus(RestClientResponseException response) {
        int code = response.getStatusCode().value();
        String reason = response.getStatusText();
        if (reason == null || reason.isBlank()) {
            // HTTP/2 carries no reason phrase.
            HttpStatus known = HttpStatus.resolve(code);
            reason = known != null ? known.getReasonPhrase() : "";
        }
        MediaType contentType = response.getResponseHeaders() != null
                ? response.getResponseHeaders().getContentType()
                : null;
        return ("HTTP " + code + " " + reason).strip()
                + (contentType != null ? " (" + contentType.getType() + "/" + contentType.getSubtype() + ")" : "");
    }

    // Mapping methods
    //
    // Since contract 2.0 a resource's address is its `key`; the 1.x `id` and `slug` are gone.

    private TemplateInfo mapToTemplateInfo(TemplateSummaryDto dto, String catalogId) {
        return new TemplateInfo(
                dto.getKey(),
                dto.getName(),
                dto.getDescription(),
                catalogId,
                null       // catalogName is not available in TemplateSummaryDto
        );
    }

    private TemplateDetails mapToTemplateDetails(TemplateDto dto) {
        // Required since contract 2.0.0-alpha.4: {} for a template without constraints.
        Object schemaSource = dto.getDataModel();
        JsonSchemaMappingAnalyzer.Analysis schemaAnalysis = new JsonSchemaMappingAnalyzer().analyze(schemaSource);

        return new TemplateDetails(
                dto.getKey(),
                dto.getName(),
                schemaAnalysis.fields(),
                schemaSource,
                schemaAnalysis.support()
        );
    }

    /**
     * Extract fields from the template's JSON schema.
     * Recursively processes nested objects and arrays to produce a tree of TemplateField nodes.
     */
    List<TemplateField> extractFieldsFromSchema(Object schema) {
        return new JsonSchemaMappingAnalyzer().analyze(schema).fields();
    }

    private EnvironmentInfo mapToEnvironmentInfo(EnvironmentDto dto) {
        return new EnvironmentInfo(
                dto.getKey(),
                dto.getName()
        );
    }

    private VariantInfo mapToVariantInfo(VariantDto dto) {
        return new VariantInfo(
                dto.getKey(),
                dto.getTemplateKey(),
                dto.getName() != null ? dto.getName() : dto.getKey(),  // fallback to key if no name
                dto.getAttributes() != null ? dto.getAttributes() : Map.of()
        );
    }

    private GenerationJobDetail mapToGenerationJobDetail(DocumentGenerationItemDto item, String requestId) {
        UUID documentId = item.getDocumentId();
        return GenerationJobDetail.builder()
                .requestId(requestId)
                .status(mapToJobStatus(item.getStatus() != null ? item.getStatus().getValue() : null))
                .documentId(documentId != null ? documentId.toString() : null)
                .errorMessage(item.getErrorMessage())
                .createdAt(toInstant(item.getCreatedAt()))
                .completedAt(toInstant(item.getCompletedAt()))
                .build();
    }

    private GenerationJobDetail mapToGenerationJobDetail(DocumentGenerationJobDto job, String requestId) {
        return GenerationJobDetail.builder()
                .requestId(requestId)
                .status(mapToJobStatus(job.getStatus() != null ? job.getStatus().getValue() : null))
                .errorMessage(job.getErrorMessage())
                .createdAt(toInstant(job.getCreatedAt()))
                .completedAt(toInstant(job.getCompletedAt()))
                .build();
    }

    private GenerationJobStatus mapToJobStatus(String status) {
        if (status == null) {
            return GenerationJobStatus.PENDING;
        }
        // Since contract 2.0 the client reads a status a newer 2.x server added as this
        // sentinel rather than failing the response. Every terminal status is known, so an unknown
        // one is read as "not finished yet": the process keeps waiting instead of the check failing.
        if (UNKNOWN_ENUM_VALUE.equals(status)) {
            return GenerationJobStatus.IN_PROGRESS;
        }
        return GenerationJobStatus.fromApi(status);
    }

    private Instant toInstant(OffsetDateTime dateTime) {
        return dateTime != null ? dateTime.toInstant() : null;
    }
}
