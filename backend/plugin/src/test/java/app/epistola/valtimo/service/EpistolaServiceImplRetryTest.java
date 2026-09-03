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

import app.epistola.client.EpistolaJson;
import app.epistola.client.api.CatalogsApi;
import app.epistola.client.api.GenerationApi;
import app.epistola.client.error.ProblemDetailException;
import app.epistola.client.model.ProblemDetail;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.GenerationJobDetail;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.core.io.Resource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the transient-failure retry on the idempotent reads (job status / download).
 * Uses a mocked {@link GenerationApi} so the retry control flow is exercised without a
 * live server.
 */
class EpistolaServiceImplRetryTest {

    private static final String TENANT = "tenant";
    private static final String REQUEST_ID = UUID.randomUUID().toString();

    @Test
    void getJobStatus_retriesOnTransientFailureThenSucceeds() {
        EpistolaApiClientFactory factory = mock(EpistolaApiClientFactory.class);
        GenerationApi generationApi = mock(GenerationApi.class);
        when(factory.createGenerationApi(anyString(), anyString())).thenReturn(generationApi);

        // A response with no items maps to a PENDING detail — enough to prove the call returned.
        app.epistola.client.model.GenerationJobDetail response =
                mock(app.epistola.client.model.GenerationJobDetail.class);
        when(generationApi.getGenerationJobStatus(eq(TENANT), any(UUID.class)))
                .thenThrow(new ResourceAccessException("connect timeout"))
                .thenReturn(response);

        EpistolaServiceImpl service = new EpistolaServiceImpl(factory, 2);
        GenerationJobDetail detail = service.getJobStatus("http://x", "key", TENANT, REQUEST_ID);

        assertNotNull(detail);
        assertEquals(REQUEST_ID, detail.getRequestId());
        verify(generationApi, times(2)).getGenerationJobStatus(eq(TENANT), any(UUID.class));
    }

    @Test
    void getJobStatus_exhaustsRetriesOnPersistent5xx() {
        EpistolaApiClientFactory factory = mock(EpistolaApiClientFactory.class);
        GenerationApi generationApi = mock(GenerationApi.class);
        when(factory.createGenerationApi(anyString(), anyString())).thenReturn(generationApi);
        when(generationApi.getGenerationJobStatus(eq(TENANT), any(UUID.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        EpistolaServiceImpl service = new EpistolaServiceImpl(factory, 2);
        assertThrows(EpistolaApiException.class,
                () -> service.getJobStatus("http://x", "key", TENANT, REQUEST_ID));

        // first try + 2 retries = 3 invocations
        verify(generationApi, times(3)).getGenerationJobStatus(eq(TENANT), any(UUID.class));
    }

    @Test
    void getJobStatus_doesNotRetryOn4xx() {
        EpistolaApiClientFactory factory = mock(EpistolaApiClientFactory.class);
        GenerationApi generationApi = mock(GenerationApi.class);
        when(factory.createGenerationApi(anyString(), anyString())).thenReturn(generationApi);
        when(generationApi.getGenerationJobStatus(eq(TENANT), any(UUID.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        EpistolaServiceImpl service = new EpistolaServiceImpl(factory, 2);
        assertThrows(EpistolaApiException.class,
                () -> service.getJobStatus("http://x", "key", TENANT, REQUEST_ID));

        // 4xx is a definitive answer — exactly one invocation, no retry
        verify(generationApi, times(1)).getGenerationJobStatus(eq(TENANT), any(UUID.class));
    }

    @Test
    void importCatalog_preservesProblemDetailAndStatusFromDownstream() throws Exception {
        // The problem body the suite returns when a bundled catalog predates its baseline.
        String problemBody = "{\"type\":\"https://epistola.app/errors/catalog-schema-too-old\","
                + "\"title\":\"Catalog Wire Schema Too Old\",\"status\":400,"
                + "\"detail\":\"Catalog wire schema version 2 predates the oldest supported version (4).\","
                + "\"version\":2,\"baselineVersion\":4}";

        // Deserialize it with the client's own mapper, exactly as its RFC 9457 status handler
        // does. This pins the part the plugin depends on but does not own: that `version` and
        // `baselineVersion` survive as extension members rather than being dropped, which is
        // what lets the admin page render an actionable redeploy message.
        ProblemDetail problem = EpistolaJson.INSTANCE.getObjectMapper()
                .readValue(problemBody, ProblemDetail.class);
        assertEquals(2, problem.getExtensions().get("version"));
        assertEquals(4, problem.getExtensions().get("baselineVersion"));

        ProblemDetailException downstream = new ProblemDetailException(
                problem,
                List.of(),
                Map.of(),
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                HttpHeaders.EMPTY,
                problemBody.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        EpistolaApiClientFactory factory = mock(EpistolaApiClientFactory.class);
        CatalogsApi catalogsApi = mock(CatalogsApi.class);
        when(factory.createLongRunningCatalogsApi(anyString(), anyString())).thenReturn(catalogsApi);
        when(catalogsApi.importCatalog(eq(TENANT), any(Resource.class), any(), any()))
                .thenThrow(downstream);

        EpistolaServiceImpl service = new EpistolaServiceImpl(factory, 2);
        EpistolaApiException ex = assertThrows(EpistolaApiException.class,
                () -> service.importCatalog("http://x", "key", TENANT, new byte[]{1, 2, 3}, "AUTHORED"));

        assertEquals(400, ex.getHttpStatus());
        assertEquals("catalog-schema-too-old", ex.getProblemTypeSlug());
        assertEquals(2, ex.getProblemExtensions().get("version"));
        assertEquals(4, ex.getProblemExtensions().get("baselineVersion"));
        assertTrue(ex.getMessage().contains("predates the oldest supported version"),
                "exception message should carry the suite's RFC-9457 detail, was: " + ex.getMessage());
    }

    @Test
    void withRetry_retriesProblemShaped5xx() {
        // A 5xx carrying problem+json arrives as ProblemDetailException, which extends
        // RestClientResponseException directly and is NOT an HttpServerErrorException.
        // Branching on the exception class instead of the status would stop retrying it.
        ProblemDetailException serverProblem = new ProblemDetailException(
                new ProblemDetail(
                        URI.create("https://epistola.app/errors/internal-error"),
                        "Internal Server Error", 503, "Upstream unavailable", null, Map.of()),
                List.of(),
                Map.of(),
                HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8);

        EpistolaApiClientFactory factory = mock(EpistolaApiClientFactory.class);
        GenerationApi generationApi = mock(GenerationApi.class);
        when(factory.createGenerationApi(anyString(), anyString())).thenReturn(generationApi);
        when(generationApi.getGenerationJobStatus(eq(TENANT), any(UUID.class))).thenThrow(serverProblem);

        EpistolaServiceImpl service = new EpistolaServiceImpl(factory, 2);
        assertThrows(EpistolaApiException.class,
                () -> service.getJobStatus("http://x", "key", TENANT, REQUEST_ID));

        // 1 initial attempt + 2 retries
        verify(generationApi, times(3)).getGenerationJobStatus(eq(TENANT), any(UUID.class));
    }

    @Test
    void withRetry_doesNotRetryProblemShaped4xx() {
        ProblemDetailException clientProblem = new ProblemDetailException(
                new ProblemDetail(
                        URI.create("https://epistola.app/errors/not-found"),
                        "Not Found", 404, "No such job", null, Map.of()),
                List.of(),
                Map.of(),
                HttpStatus.NOT_FOUND,
                "Not Found",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8);

        EpistolaApiClientFactory factory = mock(EpistolaApiClientFactory.class);
        GenerationApi generationApi = mock(GenerationApi.class);
        when(factory.createGenerationApi(anyString(), anyString())).thenReturn(generationApi);
        when(generationApi.getGenerationJobStatus(eq(TENANT), any(UUID.class))).thenThrow(clientProblem);

        EpistolaServiceImpl service = new EpistolaServiceImpl(factory, 2);
        assertThrows(EpistolaApiException.class,
                () -> service.getJobStatus("http://x", "key", TENANT, REQUEST_ID));

        verify(generationApi, times(1)).getGenerationJobStatus(eq(TENANT), any(UUID.class));
    }
}
