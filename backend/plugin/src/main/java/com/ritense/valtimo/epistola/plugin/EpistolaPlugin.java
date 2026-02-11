package com.ritense.valtimo.epistola.plugin;

import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GeneratedDocument;
import app.epistola.valtimo.domain.GenerationJobDetail;
import app.epistola.valtimo.domain.GenerationJobStatus;
import app.epistola.valtimo.service.EpistolaService;
import com.ritense.plugin.annotation.*;
import com.ritense.plugin.domain.EventType;
import com.ritense.processlink.domain.ActivityTypeWithEventName;
import com.ritense.valueresolver.ValueResolverService;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.delegate.DelegateExecution;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Placed here because Valtimo by default only scans com.ritense.valtimo package
@Plugin(
        key = EpistolaPlugin.PLUGIN_KEY,
        description = "Document generation using Epistola",
        title = "Epistola Document Suite"
)
@Slf4j
public class EpistolaPlugin {

    public static final String PLUGIN_KEY = "epistola";

    /**
     * Epistola slug pattern: lowercase alphanumeric with hyphens, no leading/trailing hyphens.
     * Used for tenantId, environmentId, templateId, variantId.
     */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");

    private final EpistolaService epistolaService;
    private final ValueResolverService valueResolverService;

    public EpistolaPlugin(EpistolaService epistolaService, ValueResolverService valueResolverService) {
        this.epistolaService = epistolaService;
        this.valueResolverService = valueResolverService;
    }

    /**
     * The base URL of the Epistola API.
     */
    @PluginProperty(
            key = "baseUrl",
            title = "Base URL",
            secret = false,
            required = true
    )
    private String baseUrl;

    /**
     * The API key for authenticating with Epistola.
     */
    @PluginProperty(
            key = "apiKey",
            title = "API Key",
            secret = true,
            required = true
    )
    private String apiKey;

    /**
     * The tenant slug in Epistola. Must be lowercase alphanumeric with hyphens (3-63 chars).
     * Example: "acme-corp", "my-tenant"
     */
    @PluginProperty(
            key = "tenantId",
            title = "Tenant ID",
            secret = false,
            required = true
    )
    private String tenantId;

    /**
     * The default environment slug for document generation. Must be lowercase alphanumeric
     * with hyphens (3-30 chars). Can be overridden per action.
     * Example: "production", "staging"
     */
    @PluginProperty(
            key = "defaultEnvironmentId",
            title = "Default Environment",
            secret = false,
            required = false
    )
    private String defaultEnvironmentId;

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getDefaultEnvironmentId() {
        return defaultEnvironmentId;
    }

    @PluginEvent(invokedOn = EventType.CREATE)
    public void onPluginCreate() {
        validateProperties();
        log.info("Epistola plugin configuration created: baseUrl={}, tenantId={}", baseUrl, tenantId);
    }

    @PluginEvent(invokedOn = EventType.DELETE)
    public void onPluginDelete() {
        log.info("Epistola plugin configuration deleted");
    }

    @PluginEvent(invokedOn = EventType.UPDATE)
    public void onPluginUpdate() {
        validateProperties();
        log.info("Epistola plugin configuration updated: baseUrl={}, tenantId={}", baseUrl, tenantId);
    }

    private void validateProperties() {
        validateSlug("tenantId", tenantId, 3, 63);

        if (defaultEnvironmentId != null && !defaultEnvironmentId.isBlank()) {
            validateSlug("defaultEnvironmentId", defaultEnvironmentId, 3, 30);
        }
    }

    private void validateSlug(String propertyName, String value, int minLength, int maxLength) {
        if (value == null || value.isBlank()) {
            return; // required-ness is handled by @PluginProperty(required)
        }
        if (value.length() < minLength || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    String.format("'%s' must be between %d and %d characters, got %d: '%s'",
                            propertyName, minLength, maxLength, value.length(), value));
        }
        if (!SLUG_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    String.format("'%s' must be a lowercase slug (pattern: %s): '%s'",
                            propertyName, SLUG_PATTERN.pattern(), value));
        }
    }

    /**
     * Generate a document using Epistola.
     * <p>
     * This action submits a document generation request to Epistola. The generation is
     * asynchronous - a request ID is returned immediately and stored in the specified
     * process variable. The actual document will be available via a callback when
     * generation is complete.
     *
     * @param execution             The process execution context
     * @param templateId            The ID of the template to use for document generation
     * @param variantId             The ID of the template variant to use
     * @param environmentId         The environment ID (optional, uses plugin default if not specified)
     * @param dataMapping           Key-value mapping of template fields to data sources.
     *                              Values can use prefixes: doc: (case data), pv: (process variable)
     * @param outputFormat          The desired output format (PDF or HTML)
     * @param filename              The filename for the generated document (can use value resolvers)
     * @param correlationId         Optional correlation ID for tracking across systems
     * @param resultProcessVariable The name of the process variable to store the request ID in
     */
    @PluginAction(
            key = "generate-document",
            title = "Generate Document",
            description = "Submit a document generation request to Epistola. The request ID will be stored in the specified process variable.",
            activityTypes = {ActivityTypeWithEventName.SERVICE_TASK_START, ActivityTypeWithEventName.TASK_START}
    )
    public void generateDocument(
            DelegateExecution execution,
            @PluginActionProperty String templateId,
            @PluginActionProperty String variantId,
            @PluginActionProperty String environmentId,
            @PluginActionProperty Map<String, String> dataMapping,
            @PluginActionProperty FileFormat outputFormat,
            @PluginActionProperty String filename,
            @PluginActionProperty String correlationId,
            @PluginActionProperty String resultProcessVariable
    ) {
        log.info("Starting document generation: templateId={}, variantId={}, outputFormat={}, filename={}",
                templateId, variantId, outputFormat, filename);

        // Resolve the data mapping values (doc:, pv:, etc.)
        Map<String, Object> resolvedData = resolveDataMapping(execution, dataMapping);

        // Resolve the filename if it uses value resolvers
        String resolvedFilename = resolveValue(execution, filename);

        // Use action-level environmentId if provided, otherwise fall back to plugin default
        String effectiveEnvironmentId = (environmentId != null && !environmentId.isBlank())
                ? environmentId
                : defaultEnvironmentId;

        // Submit the document generation request
        GeneratedDocument result = epistolaService.generateDocument(
                baseUrl,
                apiKey,
                tenantId,
                templateId,
                variantId,
                effectiveEnvironmentId,
                resolvedData,
                outputFormat,
                resolvedFilename,
                correlationId
        );

        // Store the request ID in the process variable
        execution.setVariable(resultProcessVariable, result.getDocumentId());

        log.info("Document generation request submitted. Request ID stored in variable '{}': {}",
                resultProcessVariable, result.getDocumentId());
    }

    /**
     * Check the status of a document generation job.
     * <p>
     * This action retrieves the current status of a generation request. It can be used
     * in a polling pattern to wait for document generation to complete.
     *
     * @param execution               The process execution context
     * @param requestIdVariable       The name of the process variable containing the request ID
     * @param statusVariable          The name of the process variable to store the status in
     * @param documentIdVariable      The name of the process variable to store the document ID in (when completed)
     * @param errorMessageVariable    The name of the process variable to store any error message in (when failed)
     */
    @PluginAction(
            key = "check-job-status",
            title = "Check Job Status",
            description = "Check the status of a document generation job. Stores status, document ID (if completed), and error message (if failed) in process variables.",
            activityTypes = {ActivityTypeWithEventName.SERVICE_TASK_START, ActivityTypeWithEventName.TASK_START}
    )
    public void checkJobStatus(
            DelegateExecution execution,
            @PluginActionProperty String requestIdVariable,
            @PluginActionProperty String statusVariable,
            @PluginActionProperty String documentIdVariable,
            @PluginActionProperty String errorMessageVariable
    ) {
        String requestId = (String) execution.getVariable(requestIdVariable);
        log.info("Checking job status for requestId: {}", requestId);

        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID variable '" + requestIdVariable + "' is null or empty");
        }

        GenerationJobDetail jobDetail = epistolaService.getJobStatus(baseUrl, apiKey, tenantId, requestId);

        // Store the status
        execution.setVariable(statusVariable, jobDetail.getStatus().name());

        // Store document ID if available (when completed)
        if (jobDetail.getDocumentId() != null && documentIdVariable != null && !documentIdVariable.isBlank()) {
            execution.setVariable(documentIdVariable, jobDetail.getDocumentId());
        }

        // Store error message if available (when failed)
        if (jobDetail.getErrorMessage() != null && errorMessageVariable != null && !errorMessageVariable.isBlank()) {
            execution.setVariable(errorMessageVariable, jobDetail.getErrorMessage());
        }

        log.info("Job status for requestId {}: status={}, documentId={}, errorMessage={}",
                requestId, jobDetail.getStatus(), jobDetail.getDocumentId(), jobDetail.getErrorMessage());
    }

    /**
     * Download a generated document.
     * <p>
     * This action downloads a completed document from Epistola. The document must have
     * been successfully generated (status = COMPLETED) before it can be downloaded.
     *
     * @param execution              The process execution context
     * @param documentIdVariable     The name of the process variable containing the document ID
     * @param contentVariable        The name of the process variable to store the document content (Base64 encoded)
     */
    @PluginAction(
            key = "download-document",
            title = "Download Document",
            description = "Download a generated document from Epistola. Stores the document content as Base64 in the specified process variable.",
            activityTypes = {ActivityTypeWithEventName.SERVICE_TASK_START, ActivityTypeWithEventName.TASK_START}
    )
    public void downloadDocument(
            DelegateExecution execution,
            @PluginActionProperty String documentIdVariable,
            @PluginActionProperty String contentVariable
    ) {
        String documentId = (String) execution.getVariable(documentIdVariable);
        log.info("Downloading document: {}", documentId);

        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("Document ID variable '" + documentIdVariable + "' is null or empty");
        }

        byte[] content = epistolaService.downloadDocument(baseUrl, apiKey, tenantId, documentId);

        // Store the content as Base64 encoded string
        String base64Content = java.util.Base64.getEncoder().encodeToString(content);
        execution.setVariable(contentVariable, base64Content);

        log.info("Document {} downloaded successfully ({} bytes)", documentId, content.length);
    }

    /**
     * Resolve data mapping values using the ValueResolverService.
     * Values with prefixes (doc:, pv:, case:, etc.) will be resolved to their actual values.
     */
    private Map<String, Object> resolveDataMapping(DelegateExecution execution, Map<String, String> dataMapping) {
        if (dataMapping == null || dataMapping.isEmpty()) {
            return new HashMap<>();
        }

        // Collect all values that need resolution
        List<String> valuesToResolve = dataMapping.values().stream()
                .filter(this::isResolvableValue)
                .collect(Collectors.toList());

        // Resolve values
        Map<String, Object> resolvedValues = valueResolverService.resolveValues(
                execution.getProcessInstanceId(),
                execution,
                valuesToResolve
        );

        // Build the result map with resolved values
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, String> entry : dataMapping.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (isResolvableValue(value)) {
                result.put(key, resolvedValues.get(value));
            } else {
                // Literal value, use as-is
                result.put(key, value);
            }
        }

        return result;
    }

    /**
     * Resolve a single value if it uses a value resolver prefix.
     */
    private String resolveValue(DelegateExecution execution, String value) {
        if (value == null || !isResolvableValue(value)) {
            return value;
        }

        Map<String, Object> resolved = valueResolverService.resolveValues(
                execution.getProcessInstanceId(),
                execution,
                List.of(value)
        );

        Object resolvedValue = resolved.get(value);
        return resolvedValue != null ? resolvedValue.toString() : value;
    }

    /**
     * Check if a value should be resolved using the ValueResolverService.
     */
    private boolean isResolvableValue(String value) {
        return value != null && (
                value.startsWith("doc:") ||
                value.startsWith("case:") ||
                value.startsWith("pv:") ||
                value.startsWith("template:")
        );
    }
}
