package com.ritense.valtimo.epistola.plugin;

import app.epistola.client.model.VariantSelectionAttribute;
import app.epistola.valtimo.domain.EpistolaProcessVariables;
import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GenerationJobResult;
import app.epistola.valtimo.domain.GenerationJobDetail;
import app.epistola.valtimo.service.DataMappingResolverService;
import app.epistola.valtimo.service.EpistolaMessageCorrelationService;

import app.epistola.valtimo.service.EpistolaService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.plugin.annotation.*;
import com.ritense.plugin.domain.EventType;
import com.ritense.processlink.domain.ActivityTypeWithEventName;
import com.ritense.valueresolver.ValueResolverService;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.delegate.DelegateExecution;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final EpistolaService epistolaService;
    private final ValueResolverService valueResolverService;
    private final ObjectMapper objectMapper;
    private final DataMappingResolverService dataMappingResolverService;

    public EpistolaPlugin(
            EpistolaService epistolaService,
            ValueResolverService valueResolverService,
            ObjectMapper objectMapper,
            DataMappingResolverService dataMappingResolverService
    ) {
        this.epistolaService = epistolaService;
        this.valueResolverService = valueResolverService;
        this.objectMapper = objectMapper;
        this.dataMappingResolverService = dataMappingResolverService;
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

    /**
     * Whether to enable automatic template synchronization from classpath to Epistola.
     * When enabled, template definitions from config/epistola/templates/ are synced on startup.
     */
    @PluginProperty(
            key = "templateSyncEnabled",
            title = "Template Sync",
            secret = false,
            required = false
    )
    private Boolean templateSyncEnabled;

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

    public Boolean getTemplateSyncEnabled() {
        return templateSyncEnabled;
    }

    public boolean isTemplateSyncEnabled() {
        return Boolean.TRUE.equals(templateSyncEnabled);
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
     * <p>
     * Variant selection supports three modes:
     * <ul>
     *   <li>Default: omit both variantId and variantAttributes — the template's default variant is used</li>
     *   <li>Explicit: specify variantId directly</li>
     *   <li>By attributes: specify variantAttributes (key-value pairs), and the API selects the matching variant.
     *       Values can use value resolver expressions (doc:, pv:, case:).</li>
     * </ul>
     * variantId and variantAttributes are mutually exclusive.
     *
     * @param execution             The process execution context
     * @param templateId            The ID of the template to use for document generation
     * @param variantId             The ID of the template variant (optional — omit to use default or attribute selection)
     * @param variantAttributes     Key-value attributes for automatic variant selection (optional).
     *                              Values can use value resolver expressions (doc:, pv:, case:).
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
            @PluginActionProperty Object variantAttributes,
            @PluginActionProperty String environmentId,
            @PluginActionProperty Map<String, Object> dataMapping,
            @PluginActionProperty FileFormat outputFormat,
            @PluginActionProperty String filename,
            @PluginActionProperty String correlationId,
            @PluginActionProperty String resultProcessVariable
    ) {
        log.info("Starting document generation: templateId={}, variantId={}, variantAttributes={}, outputFormat={}, filename={}",
                templateId, variantId, variantAttributes, outputFormat, filename);

        // Normalize variant attributes from either old (Map<String, String>) or new (List<Map>) format
        List<NormalizedAttribute> normalizedAttributes = normalizeVariantAttributes(variantAttributes);

        // Validate: variantId and variantAttributes are mutually exclusive.
        // If neither is provided, the API will use the template's default variant.
        boolean hasVariantId = variantId != null && !variantId.isBlank();
        boolean hasAttributes = !normalizedAttributes.isEmpty();
        if (hasVariantId && hasAttributes) {
            throw new IllegalArgumentException("Cannot specify both variantId and variantAttributes");
        }

        // Check if this is a retry with user-edited data from the fallback form.
        Object rawEditedData = execution.getVariable(EpistolaProcessVariables.EDITED_DATA);
        String editedDataJson = rawEditedData instanceof String s ? s : null;
        boolean isRetry = editedDataJson != null && !editedDataJson.isBlank();

        log.info("generate-document retry check: variable='{}', exists={}, isRetry={}, type={}, value={}",
                EpistolaProcessVariables.EDITED_DATA, rawEditedData != null, isRetry,
                rawEditedData != null ? rawEditedData.getClass().getSimpleName() : "null",
                editedDataJson != null ? editedDataJson.substring(0, Math.min(200, editedDataJson.length())) : "null");

        Map<String, Object> resolvedData;
        if (isRetry) {
            log.info("Retry detected: using edited data from '{}' process variable", EpistolaProcessVariables.EDITED_DATA);
            try {
                resolvedData = objectMapper.readValue(editedDataJson, MAP_TYPE);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to parse '" + EpistolaProcessVariables.EDITED_DATA + "' process variable as JSON", e);
            }
            // Clear the edited data so subsequent non-retry invocations resolve normally
            execution.removeVariable(EpistolaProcessVariables.EDITED_DATA);
        } else {
            // Resolve value expressions (doc:, pv:, etc.) recursively through the nested mapping
            resolvedData = dataMappingResolverService.resolveMapping(execution, dataMapping != null ? dataMapping : Map.of());
        }

        // Resolve the filename if it uses value resolvers
        String resolvedFilename = resolveValue(execution, filename);

        // Use action-level environmentId if provided, otherwise fall back to plugin default
        String effectiveEnvironmentId = (environmentId != null && !environmentId.isBlank())
                ? environmentId
                : defaultEnvironmentId;

        // Build variant selection attributes if using attribute-based selection
        List<VariantSelectionAttribute> resolvedAttributes = hasAttributes
                ? resolveVariantAttributes(execution, normalizedAttributes)
                : null;

        // Submit the document generation request
        GenerationJobResult result;
        try {
            result = epistolaService.submitGenerationJob(
                    baseUrl,
                    apiKey,
                    tenantId,
                    templateId,
                    hasVariantId ? variantId : null,
                    resolvedAttributes,
                    effectiveEnvironmentId,
                    resolvedData,
                    outputFormat,
                    resolvedFilename,
                    correlationId
            );
        } catch (Exception e) {
            // Store error details so the retry flow can trigger
            execution.setVariable(EpistolaProcessVariables.STATUS, "FAILED");
            execution.setVariable(EpistolaProcessVariables.ERROR_MESSAGE,
                    "Document generation request failed: " + e.getMessage());
            throw new RuntimeException("Failed to submit document generation request to Epistola", e);
        }

        // Store the request ID in the user-configured process variable
        execution.setVariable(resultProcessVariable, result.getRequestId());

        // Store tenantId as a standalone process variable so it can be used in forms
        // (e.g. for building document download URLs without parsing the composite jobPath)
        execution.setVariable(EpistolaProcessVariables.TENANT_ID, tenantId);

        // Store a single composite job path that encodes both tenantId and requestId.
        // This avoids scoping issues where separate variables might not both be visible
        // to the polling consumer's execution.
        String jobPath = EpistolaMessageCorrelationService.buildJobPath(tenantId, result.getRequestId());
        execution.setVariable(EpistolaProcessVariables.JOB_PATH, jobPath);

        log.info("Document generation request submitted. jobPath={}, resultVar={}",
                jobPath, resultProcessVariable);
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
     * Internal representation of a variant attribute entry, supporting both old and new config formats.
     */
    private record NormalizedAttribute(String key, String value, Boolean required) {}

    /**
     * Normalize variant attributes from either the old format (Map&lt;String, String&gt;)
     * or the new format (List of objects with key, value, required).
     */
    @SuppressWarnings("unchecked")
    private List<NormalizedAttribute> normalizeVariantAttributes(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            // New format: List<{key, value, required}>
            return list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> {
                        Map<String, Object> map = (Map<String, Object>) item;
                        String key = map.get("key") != null ? String.valueOf(map.get("key")) : "";
                        String value = map.get("value") != null ? String.valueOf(map.get("value")) : "";
                        Boolean required = map.get("required") instanceof Boolean b ? b : null;
                        return new NormalizedAttribute(key, value, required);
                    })
                    .filter(a -> !a.key().isEmpty() && !a.value().isEmpty())
                    .toList();
        }
        if (raw instanceof Map<?, ?> map) {
            // Old format: Map<String, String> — treat all as required (null = API default true)
            return map.entrySet().stream()
                    .map(entry -> new NormalizedAttribute(
                            String.valueOf(entry.getKey()),
                            String.valueOf(entry.getValue()),
                            null
                    ))
                    .filter(a -> !a.key().isEmpty() && !a.value().isEmpty())
                    .toList();
        }
        log.warn("Unexpected variantAttributes type: {}", raw.getClass().getName());
        return List.of();
    }

    /**
     * Resolve variant attributes, resolving any value resolver expressions in the values.
     */
    private List<VariantSelectionAttribute> resolveVariantAttributes(
            DelegateExecution execution,
            List<NormalizedAttribute> attributes
    ) {
        // Collect resolvable attribute values
        List<String> valuesToResolve = attributes.stream()
                .map(NormalizedAttribute::value)
                .filter(this::isResolvableValue)
                .toList();

        // Batch-resolve all values at once
        Map<String, Object> resolvedValues = valuesToResolve.isEmpty()
                ? Map.of()
                : valueResolverService.resolveValues(
                        execution.getProcessInstanceId(),
                        execution,
                        valuesToResolve
                );

        // Build VariantSelectionAttribute list with resolved values
        return attributes.stream()
                .map(attr -> {
                    String resolvedValue = isResolvableValue(attr.value()) && resolvedValues.containsKey(attr.value())
                            ? String.valueOf(resolvedValues.get(attr.value()))
                            : attr.value();
                    return new VariantSelectionAttribute(attr.key(), resolvedValue, attr.required());
                })
                .toList();
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
