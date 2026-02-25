package app.epistola.valtimo.client;

import app.epistola.client.api.EnvironmentsApi;
import app.epistola.client.api.GenerationApi;
import app.epistola.client.api.TemplatesApi;
import app.epistola.client.api.VariantsApi;
import app.epistola.client.infrastructure.Serializer;
import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
public class EpistolaApiClientFactory {

    private static final String API_KEY_HEADER = "X-API-Key";

    /**
     * Create a GenerationApi client for document generation operations.
     *
     * @param baseUrl The Epistola API base URL
     * @param apiKey  The API key for authentication
     * @return Configured GenerationApi client
     */
    public GenerationApi createGenerationApi(String baseUrl, String apiKey) {
        return new GenerationApi(createRestClient(baseUrl, apiKey));
    }

    /**
     * Create a TemplatesApi client for template operations.
     *
     * @param baseUrl The Epistola API base URL
     * @param apiKey  The API key for authentication
     * @return Configured TemplatesApi client
     */
    public TemplatesApi createTemplatesApi(String baseUrl, String apiKey) {
        return new TemplatesApi(createRestClient(baseUrl, apiKey));
    }

    /**
     * Create an EnvironmentsApi client for environment operations.
     *
     * @param baseUrl The Epistola API base URL
     * @param apiKey  The API key for authentication
     * @return Configured EnvironmentsApi client
     */
    public EnvironmentsApi createEnvironmentsApi(String baseUrl, String apiKey) {
        return new EnvironmentsApi(createRestClient(baseUrl, apiKey));
    }

    /**
     * Create a VariantsApi client for variant operations.
     *
     * @param baseUrl The Epistola API base URL
     * @param apiKey  The API key for authentication
     * @return Configured VariantsApi client
     */
    public VariantsApi createVariantsApi(String baseUrl, String apiKey) {
        return new VariantsApi(createRestClient(baseUrl, apiKey));
    }

    /**
     * Create a RestClient with authentication headers configured.
     * <p>
     * Uses the generated client's ObjectMapper which has:
     * <ul>
     *   <li>NON_ABSENT serialization (excludes null fields from request bodies)</li>
     *   <li>FAIL_ON_UNKNOWN_PROPERTIES disabled (tolerates extra fields in responses)</li>
     *   <li>Kotlin module and Java time module registered</li>
     * </ul>
     * <p>
     * Public so callers can make direct HTTP calls for endpoints where
     * the generated client's return type doesn't work (e.g., binary downloads).
     */
    public RestClient createRestClient(String baseUrl, String apiKey) {
        var converter = new MappingJackson2HttpMessageConverter(Serializer.getJacksonObjectMapper());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(API_KEY_HEADER, apiKey)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                    converters.add(converter);
                })
                .build();
    }
}
