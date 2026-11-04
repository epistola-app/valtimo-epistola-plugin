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

import app.epistola.client.api.TemplatesApi;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A failure that is not an Epistola problem response says what it was, so that the message the
 * letter composer shows — {@code Epistola could not describe template … : <this>} — tells a proxy's
 * error page from an unreachable host from a response the client could not read, without anyone
 * having to find the matching log line first. It names a status, a content type and exception
 * types only: the message travels into logs, and a downstream message may quote case data.
 */
class EpistolaServiceImplFailureMessageTest {

    private static final String BASE_URL = "http://epistola";
    private static final String API_KEY = "key";
    private static final String TENANT = "demo";
    private static final String CATALOG = "default";
    private static final String TEMPLATE = "besluit-bezwaar";

    private TemplatesApi templatesApi;
    private EpistolaServiceImpl service;

    @BeforeEach
    void setUp() {
        EpistolaApiClientFactory factory = mock(EpistolaApiClientFactory.class);
        templatesApi = mock(TemplatesApi.class);
        when(factory.createTemplatesApi(anyString(), anyString())).thenReturn(templatesApi);
        service = new EpistolaServiceImpl(factory);
    }

    @Test
    void anErrorPageThatIsNoProblemResponse_namesItsStatusAndContentType() {
        when(templatesApi.getTemplate(TENANT, CATALOG, TEMPLATE)).thenThrow(errorPage("Bad Gateway"));

        EpistolaApiException ex = fetch();

        assertEquals("Failed to fetch template details: HTTP 502 Bad Gateway (text/html)", ex.getMessage());
        assertEquals(502, ex.getHttpStatus());
    }

    @Test
    void aStatusLineWithoutReasonPhrase_fallsBackToTheStandardOne() {
        // HTTP/2 has no reason phrase, so the status text arrives empty.
        when(templatesApi.getTemplate(TENANT, CATALOG, TEMPLATE)).thenThrow(errorPage(""));

        assertEquals("Failed to fetch template details: HTTP 502 Bad Gateway (text/html)", fetch().getMessage());
    }

    @Test
    void aTransportFailure_namesTheFailureAndItsRootCause() {
        when(templatesApi.getTemplate(TENANT, CATALOG, TEMPLATE)).thenThrow(
                new ResourceAccessException("I/O error on GET request", new ConnectException("Connection refused")));

        assertEquals("Failed to fetch template details: ResourceAccessException caused by ConnectException",
                fetch().getMessage());
    }

    @Test
    void aDownstreamMessage_isNotQuoted() {
        when(templatesApi.getTemplate(TENANT, CATALOG, TEMPLATE)).thenThrow(
                new IllegalStateException("Cannot read value 'Jansen'"));

        EpistolaApiException ex = fetch();

        assertEquals("Failed to fetch template details: IllegalStateException", ex.getMessage());
        assertFalse(ex.getMessage().contains("Jansen"));
    }

    @Test
    void aResponseBody_isNotQuoted() {
        when(templatesApi.getTemplate(TENANT, CATALOG, TEMPLATE)).thenThrow(errorPage("Bad Gateway"));

        assertFalse(fetch().getMessage().contains("nginx"));
    }

    private EpistolaApiException fetch() {
        return assertThrows(EpistolaApiException.class,
                () -> service.getTemplateDetails(BASE_URL, API_KEY, TENANT, CATALOG, TEMPLATE));
    }

    private static HttpServerErrorException errorPage(String statusText) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        return HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, statusText, headers,
                "<html><body><center>nginx</center></body></html>".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }
}
