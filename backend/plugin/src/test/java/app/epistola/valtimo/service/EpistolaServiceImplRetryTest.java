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

import app.epistola.client.api.GenerationApi;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.domain.GenerationJobDetail;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
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
    void importCatalog_preservesProblemDetailAndStatusFromDownstream() {
        EpistolaApiClientFactory factory = mock(EpistolaApiClientFactory.class);
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(factory.createRestClient(anyString(), anyString())).thenReturn(restClient);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/tenants/{tenantId}/catalogs/import", TENANT)).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);

        String problemBody = "{\"type\":\"https://epistola.app/errors/catalog-schema-too-old\","
                + "\"title\":\"Catalog Wire Schema Too Old\",\"status\":400,"
                + "\"detail\":\"Catalog wire schema version 2 predates the oldest supported version (4).\","
                + "\"version\":2,\"baselineVersion\":4}";
        HttpClientErrorException downstream = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                problemBody.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        when(responseSpec.body(String.class)).thenThrow(downstream);

        EpistolaServiceImpl service = new EpistolaServiceImpl(factory, 2);
        EpistolaApiException ex = assertThrows(EpistolaApiException.class,
                () -> service.importCatalog("http://x", "key", TENANT, new byte[]{1, 2, 3}, "full"));

        assertEquals(400, ex.getHttpStatus());
        assertEquals("catalog-schema-too-old", ex.getProblemTypeSlug());
        assertEquals(2, ex.getProblemExtensions().get("version"));
        assertEquals(4, ex.getProblemExtensions().get("baselineVersion"));
        assertTrue(ex.getMessage().contains("predates the oldest supported version"),
                "exception message should carry the suite's RFC-9457 detail, was: " + ex.getMessage());
    }
}
