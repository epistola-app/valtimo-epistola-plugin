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
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
}
