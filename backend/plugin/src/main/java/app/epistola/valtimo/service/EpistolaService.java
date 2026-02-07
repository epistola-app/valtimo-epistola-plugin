package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.*;

import java.util.List;
import java.util.Map;

/**
 * Service for interacting with Epistola document generation.
 * <p>
 * All methods require baseUrl and apiKey for authentication with the Epistola API.
 * These values are typically obtained from the plugin configuration.
 */
public interface EpistolaService {

    /**
     * Get all available templates for a tenant.
     *
     * @param baseUrl  The Epistola API base URL
     * @param apiKey   The API key for authentication
     * @param tenantId The tenant ID in Epistola
     * @return List of available templates
     */
    List<TemplateInfo> getTemplates(String baseUrl, String apiKey, String tenantId);

    /**
     * Get template details including its fields.
     *
     * @param baseUrl    The Epistola API base URL
     * @param apiKey     The API key for authentication
     * @param tenantId   The tenant ID in Epistola
     * @param templateId The ID of the template
     * @return Template details with fields
     */
    TemplateDetails getTemplateDetails(String baseUrl, String apiKey, String tenantId, String templateId);

    /**
     * Get all available environments for a tenant.
     *
     * @param baseUrl  The Epistola API base URL
     * @param apiKey   The API key for authentication
     * @param tenantId The tenant ID in Epistola
     * @return List of available environments
     */
    List<EnvironmentInfo> getEnvironments(String baseUrl, String apiKey, String tenantId);

    /**
     * Get all variants for a specific template.
     *
     * @param baseUrl    The Epistola API base URL
     * @param apiKey     The API key for authentication
     * @param tenantId   The tenant ID in Epistola
     * @param templateId The ID of the template
     * @return List of variants for the template
     */
    List<VariantInfo> getVariants(String baseUrl, String apiKey, String tenantId, String templateId);

    /**
     * Generate a document using a template.
     *
     * @param baseUrl       The Epistola API base URL
     * @param apiKey        The API key for authentication
     * @param tenantId      The tenant ID in Epistola
     * @param templateId    The ID of the template to use
     * @param variantId     The ID of the variant to use
     * @param environmentId The ID of the environment (optional)
     * @param data          The data to populate the template with (already resolved values)
     * @param format        The output format (PDF or HTML)
     * @param filename      The desired filename for the generated document
     * @param correlationId Optional correlation ID for tracking
     * @return The generated document information
     */
    GeneratedDocument generateDocument(
            String baseUrl,
            String apiKey,
            String tenantId,
            String templateId,
            String variantId,
            String environmentId,
            Map<String, Object> data,
            FileFormat format,
            String filename,
            String correlationId
    );

    /**
     * Get the status of a document generation job.
     *
     * @param baseUrl   The Epistola API base URL
     * @param apiKey    The API key for authentication
     * @param tenantId  The tenant ID in Epistola
     * @param requestId The request/job ID returned from generateDocument
     * @return Detailed job status information
     */
    GenerationJobDetail getJobStatus(String baseUrl, String apiKey, String tenantId, String requestId);

    /**
     * Download a generated document.
     *
     * @param baseUrl    The Epistola API base URL
     * @param apiKey     The API key for authentication
     * @param tenantId   The tenant ID in Epistola
     * @param documentId The ID of the document to download
     * @return The document bytes
     */
    byte[] downloadDocument(String baseUrl, String apiKey, String tenantId, String documentId);
}
