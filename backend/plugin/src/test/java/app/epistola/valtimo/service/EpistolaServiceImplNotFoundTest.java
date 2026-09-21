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

import app.epistola.client.api.AttributesApi;
import app.epistola.client.api.TemplatesApi;
import app.epistola.client.api.VariantsApi;
import app.epistola.client.error.ProblemDetailException;
import app.epistola.client.model.ProblemDetail;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A catalog or template Epistola reports as missing keeps its 404 through the service, so the
 * REST layer can answer "not found" instead of turning a stale selection into a server error.
 */
class EpistolaServiceImplNotFoundTest {

    private static final String BASE_URL = "http://epistola";
    private static final String API_KEY = "key";
    private static final String TENANT = "demo";
    private static final String CATALOG = "default";
    private static final String TEMPLATE = "besluit-bezwaar";

    private EpistolaApiClientFactory factory;
    private EpistolaServiceImpl service;

    @BeforeEach
    void setUp() {
        factory = mock(EpistolaApiClientFactory.class);
        service = new EpistolaServiceImpl(factory);
    }

    @Test
    void getTemplateDetails_keepsTheNotFoundStatus() {
        TemplatesApi templatesApi = mock(TemplatesApi.class);
        when(factory.createTemplatesApi(anyString(), anyString())).thenReturn(templatesApi);
        when(templatesApi.getTemplate(TENANT, CATALOG, TEMPLATE)).thenThrow(notFound("Template Not Found"));

        assertNotFound(() -> service.getTemplateDetails(BASE_URL, API_KEY, TENANT, CATALOG, TEMPLATE));
    }

    @Test
    void getVariants_keepsTheNotFoundStatus() {
        VariantsApi variantsApi = mock(VariantsApi.class);
        when(factory.createVariantsApi(anyString(), anyString())).thenReturn(variantsApi);
        when(variantsApi.listVariants(eq(TENANT), eq(CATALOG), eq(TEMPLATE), anyInt(), anyInt(), any(), any()))
                .thenThrow(notFound("Template Not Found"));

        assertNotFound(() -> service.getVariants(BASE_URL, API_KEY, TENANT, CATALOG, TEMPLATE));
    }

    @Test
    void getTemplates_keepsTheNotFoundStatus() {
        TemplatesApi templatesApi = mock(TemplatesApi.class);
        when(factory.createTemplatesApi(anyString(), anyString())).thenReturn(templatesApi);
        when(templatesApi.listTemplates(eq(TENANT), eq(CATALOG), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(notFound("Catalog Not Found"));

        assertNotFound(() -> service.getTemplates(BASE_URL, API_KEY, TENANT, CATALOG));
    }

    @Test
    void getAttributes_keepsTheNotFoundStatus() {
        AttributesApi attributesApi = mock(AttributesApi.class);
        when(factory.createAttributesApi(anyString(), anyString())).thenReturn(attributesApi);
        when(attributesApi.listAttributes(eq(TENANT), eq(CATALOG), anyInt(), anyInt(), any(), any()))
                .thenThrow(notFound("Catalog Not Found"));

        assertNotFound(() -> service.getAttributes(BASE_URL, API_KEY, TENANT, CATALOG));
    }

    private static void assertNotFound(Executable call) {
        EpistolaApiException ex = assertThrows(EpistolaApiException.class, call);
        assertEquals(404, ex.getHttpStatus());
    }

    private static ProblemDetailException notFound(String title) {
        return new ProblemDetailException(
                new ProblemDetail(URI.create("https://epistola.app/errors/not-found"), title, 404,
                        title + " for tenant " + TENANT, null, Map.of()),
                List.of(),
                Map.of(),
                HttpStatus.NOT_FOUND,
                "Not Found",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8);
    }
}
