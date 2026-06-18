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
package app.epistola.valtimo.client;

import app.epistola.client.api.AttributesApi;
import app.epistola.client.api.CatalogsApi;
import app.epistola.client.api.EnvironmentsApi;
import app.epistola.client.api.GenerationApi;
import app.epistola.client.api.TemplatesApi;
import app.epistola.client.api.VariantsApi;
import app.epistola.client.identity.ClientIdentity;
import app.epistola.client.infrastructure.Serializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Factory for creating Epistola API clients with custom configuration.
 * <p>
 * Creates API client instances on demand with the specified base URL and API key.
 * This allows different plugin configurations to connect to different Epistola instances.
 * <p>
 * Uses the generated client's {@link Serializer#getJacksonObjectMapper()} to ensure
 * proper serialization settings (NON_ABSENT inclusion, lenient deserialization, etc.)
 * are consistent with the generated Kotlin DTOs.
 * <p>
 * Every request also carries the contract's {@link ClientIdentity} headers
 * ({@code User-Agent} starting with {@code epistola-contract/<version>} and
 * {@code X-EP-Node-Id}), which v0.3+ of the contract requires.
 * <p>
 * All clients get a connect timeout so a hung TCP connect cannot block a BPMN
 * job-executor thread (or the collector's virtual thread) forever. The generated
 * request/response API clients ({@code createXApi}) additionally get a socket read
 * timeout; {@link #createRestClient} (used for the result-collector poll, document
 * download, preview, and catalog import) deliberately gets only the connect timeout
 * so a legitimately slow render or large transfer is not cut off.
 */
@Slf4j
public class EpistolaApiClientFactory {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String PRODUCT_NAME = "valtimo-epistola-plugin";

    private final ClientHttpRequestInterceptor identityInterceptor;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    /** Defaults (10s connect, 30s read) for tests / callers that don't configure timeouts. */
    public EpistolaApiClientFactory() {
        this(Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    public EpistolaApiClientFactory(Duration connectTimeout, Duration readTimeout) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.identityInterceptor = ClientIdentity.Companion.builder()
                .product(PRODUCT_NAME, resolveProductVersion())
                .build()
                .interceptor();
    }

    /**
     * Create a GenerationApi client for document generation operations.
     */
    public GenerationApi createGenerationApi(String baseUrl, String apiKey) {
        return new GenerationApi(createApiRestClient(baseUrl, apiKey));
    }

    /**
     * Create a CatalogsApi client for catalog operations.
     */
    public CatalogsApi createCatalogsApi(String baseUrl, String apiKey) {
        return new CatalogsApi(createApiRestClient(baseUrl, apiKey));
    }

    /**
     * Create a TemplatesApi client for template operations.
     */
    public TemplatesApi createTemplatesApi(String baseUrl, String apiKey) {
        return new TemplatesApi(createApiRestClient(baseUrl, apiKey));
    }

    /**
     * Create an AttributesApi client for attribute definition operations.
     */
    public AttributesApi createAttributesApi(String baseUrl, String apiKey) {
        return new AttributesApi(createApiRestClient(baseUrl, apiKey));
    }

    /**
     * Create an EnvironmentsApi client for environment operations.
     */
    public EnvironmentsApi createEnvironmentsApi(String baseUrl, String apiKey) {
        return new EnvironmentsApi(createApiRestClient(baseUrl, apiKey));
    }

    /**
     * Create a VariantsApi client for variant operations.
     */
    public VariantsApi createVariantsApi(String baseUrl, String apiKey) {
        return new VariantsApi(createApiRestClient(baseUrl, apiKey));
    }

    /**
     * Create a RestClient with authentication and identity headers configured, with a
     * connect timeout but <em>no</em> read timeout.
     * <p>
     * Public so callers can make direct HTTP calls for endpoints where the generated
     * client's return type doesn't work (e.g., binary downloads, NDJSON streaming) or
     * which are legitimately long-running (preview render, catalog import, the
     * result-collector poll). Read-timeout-sensitive request/response calls should go
     * through the {@code createXApi} clients instead.
     */
    public RestClient createRestClient(String baseUrl, String apiKey) {
        return buildRestClient(baseUrl, apiKey, false);
    }

    /**
     * RestClient for short request/response API calls — connect <em>and</em> read timeout.
     */
    private RestClient createApiRestClient(String baseUrl, String apiKey) {
        return buildRestClient(baseUrl, apiKey, true);
    }

    private RestClient buildRestClient(String baseUrl, String apiKey, boolean withReadTimeout) {
        var converter = new MappingJackson2HttpMessageConverter(Serializer.getJacksonObjectMapper());
        return RestClient.builder()
                .requestFactory(requestFactory(withReadTimeout))
                .baseUrl(baseUrl)
                .defaultHeader(API_KEY_HEADER, apiKey)
                .requestInterceptor(identityInterceptor)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                    converters.add(converter);
                })
                .build();
    }

    private ClientHttpRequestFactory requestFactory(boolean withReadTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        // Omitting the read timeout leaves it at the JDK default (infinite), which is what
        // streaming/large/long operations need; short API calls pass true to cap it.
        if (withReadTimeout) {
            factory.setReadTimeout((int) readTimeout.toMillis());
        }
        return factory;
    }

    /**
     * Resolve this product's version from the package metadata, falling back
     * to "unknown" when running outside a packaged JAR (e.g. during tests).
     */
    private String resolveProductVersion() {
        Package pkg = EpistolaApiClientFactory.class.getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        return version != null && !version.isBlank() ? version : "unknown";
    }
}
