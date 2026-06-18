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
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

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
 */
@Slf4j
public class EpistolaApiClientFactory {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String PRODUCT_NAME = "valtimo-epistola-plugin";

    private final ClientHttpRequestInterceptor identityInterceptor;

    public EpistolaApiClientFactory() {
        this.identityInterceptor = ClientIdentity.Companion.builder()
                .product(PRODUCT_NAME, resolveProductVersion())
                .build()
                .interceptor();
    }

    /**
     * Create a GenerationApi client for document generation operations.
     */
    public GenerationApi createGenerationApi(String baseUrl, String apiKey) {
        return new GenerationApi(createRestClient(baseUrl, apiKey));
    }

    /**
     * Create a CatalogsApi client for catalog operations.
     */
    public CatalogsApi createCatalogsApi(String baseUrl, String apiKey) {
        return new CatalogsApi(createRestClient(baseUrl, apiKey));
    }

    /**
     * Create a TemplatesApi client for template operations.
     */
    public TemplatesApi createTemplatesApi(String baseUrl, String apiKey) {
        return new TemplatesApi(createRestClient(baseUrl, apiKey));
    }

    /**
     * Create an AttributesApi client for attribute definition operations.
     */
    public AttributesApi createAttributesApi(String baseUrl, String apiKey) {
        return new AttributesApi(createRestClient(baseUrl, apiKey));
    }

    /**
     * Create an EnvironmentsApi client for environment operations.
     */
    public EnvironmentsApi createEnvironmentsApi(String baseUrl, String apiKey) {
        return new EnvironmentsApi(createRestClient(baseUrl, apiKey));
    }

    /**
     * Create a VariantsApi client for variant operations.
     */
    public VariantsApi createVariantsApi(String baseUrl, String apiKey) {
        return new VariantsApi(createRestClient(baseUrl, apiKey));
    }

    /**
     * Create a RestClient with authentication and identity headers configured.
     * <p>
     * Public so callers can make direct HTTP calls for endpoints where
     * the generated client's return type doesn't work (e.g., binary downloads,
     * NDJSON streaming).
     */
    public RestClient createRestClient(String baseUrl, String apiKey) {
        var converter = new MappingJackson2HttpMessageConverter(Serializer.getJacksonObjectMapper());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(API_KEY_HEADER, apiKey)
                .requestInterceptor(identityInterceptor)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                    converters.add(converter);
                })
                .build();
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
