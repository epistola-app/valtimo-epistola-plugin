package app.epistola.valtimo.client;

import app.epistola.client.api.EnvironmentsApi;
import app.epistola.client.api.GenerationApi;
import app.epistola.client.api.TemplatesApi;
import app.epistola.client.api.VariantsApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Factory for creating Epistola API clients with custom configuration.
 * <p>
 * Creates API client instances on demand with the specified base URL and API key.
 * This allows different plugin configurations to connect to different Epistola instances.
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
     */
    private RestClient createRestClient(String baseUrl, String apiKey) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(API_KEY_HEADER, apiKey)
                .messageConverters(converters -> converters.add(new MappingJackson2HttpMessageConverter()))
                .build();
    }
}
