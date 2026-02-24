package com.ritense.valtimo.epistola.plugin;

import app.epistola.client.model.VariantSelectionAttribute;
import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GeneratedDocument;
import app.epistola.valtimo.domain.GenerationJobDetail;
import app.epistola.valtimo.domain.GenerationJobStatus;
import app.epistola.valtimo.service.DataMappingResolver;
import app.epistola.valtimo.service.EpistolaService;
import com.ritense.plugin.annotation.*;
import com.ritense.plugin.domain.EventType;
import com.ritense.processlink.domain.ActivityTypeWithEventName;
import com.ritense.valueresolver.ValueResolverService;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.delegate.DelegateExecution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
            @PluginActionProperty Map<String, String> variantAttributes,
            @PluginActionProperty String environmentId,
            @PluginActionProperty Map<String, Object> dataMapping,
            @PluginActionProperty FileFormat outputFormat,
            @PluginActionProperty String filename,
            @PluginActionProperty String correlationId,
            @PluginActionProperty String resultProcessVariable
    ) {
        log.info("Starting document generation: templateId={}, variantId={}, variantAttributes={}, outputFormat={}, filename={}",
                templateId, variantId, variantAttributes, outputFormat, filename);

        // Validate: variantId and variantAttributes are mutually exclusive.
        // If neither is provided, the API will use the template's default variant.
        boolean hasVariantId = variantId != null && !variantId.isBlank();
        boolean hasAttributes = variantAttributes != null && !variantAttributes.isEmpty();
        if (hasVariantId && hasAttributes) {
            throw new IllegalArgumentException("Cannot specify both variantId and variantAttributes");
        }

        // Resolve value expressions (doc:, pv:, etc.) recursively through the nested mapping
        Map<String, Object> resolvedData = resolveNestedMapping(execution, dataMapping != null ? dataMapping : Map.of());

        // Resolve the filename if it uses value resolvers
        String resolvedFilename = resolveValue(execution, filename);

        // Use action-level environmentId if provided, otherwise fall back to plugin default
        String effectiveEnvironmentId = (environmentId != null && !environmentId.isBlank())
                ? environmentId
                : defaultEnvironmentId;

        // Build variant selection attributes if using attribute-based selection
        List<VariantSelectionAttribute> resolvedAttributes = hasAttributes
                ? resolveVariantAttributes(execution, variantAttributes)
                : null;

        // Submit the document generation request
        GeneratedDocument result = epistolaService.generateDocument(
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

        // Store the request ID and tenant ID as both local and process-instance variables.
        // Local variables support parallel gateways and multi-instance subprocesses
        // (each branch gets its own scope). Process-instance variables provide a fallback
        // for the polling consumer, since the message catch event runs on a different
        // execution than the service task that set the local variables.
        execution.setVariable(resultProcessVariable, result.getRequestId());
        execution.setVariableLocal("epistolaRequestId", result.getRequestId());
        execution.setVariable("epistolaRequestId", result.getRequestId());
        execution.setVariableLocal("epistolaTenantId", tenantId);
        execution.setVariable("epistolaTenantId", tenantId);

        log.info("Document generation request submitted. Request ID stored in variable '{}': {}",
                resultProcessVariable, result.getRequestId());
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
     * Recursively resolve value expressions in a nested data mapping.
     * String values with prefixes (doc:, pv:, case:, etc.) are resolved via ValueResolverService.
     * Nested maps are traversed recursively; other values are passed through as-is.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveNestedMapping(DelegateExecution execution, Map<String, Object> mapping) {
        // First pass: collect all resolvable string values from the entire tree
        List<String> valuesToResolve = new ArrayList<>();
        collectResolvableValues(mapping, valuesToResolve);

        // Batch-resolve all values at once for efficiency
        Map<String, Object> resolvedValues = valuesToResolve.isEmpty()
                ? Map.of()
                : valueResolverService.resolveValues(
                        execution.getProcessInstanceId(),
                        execution,
                        valuesToResolve
                );

        // Second pass: build resolved tree using the batch-resolved values
        return applyResolvedValues(mapping, resolvedValues);
    }

    @SuppressWarnings("unchecked")
    private void collectResolvableValues(Map<String, Object> mapping, List<String> valuesToResolve) {
        for (Object value : mapping.values()) {
            if (value instanceof String str && isResolvableValue(str)) {
                valuesToResolve.add(str);
            } else if (DataMappingResolver.isArrayFieldMapping(value)) {
                // Per-item array mapping: only the _source expression is resolvable,
                // other entries are plain field name strings (not value resolver expressions)
                Map<String, Object> arrayMapping = (Map<String, Object>) value;
                Object source = arrayMapping.get(DataMappingResolver.ARRAY_SOURCE_KEY);
                if (source instanceof String str && isResolvableValue(str)) {
                    valuesToResolve.add(str);
                }
            } else if (value instanceof Map<?, ?> nested) {
                collectResolvableValues((Map<String, Object>) nested, valuesToResolve);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyResolvedValues(Map<String, Object> mapping, Map<String, Object> resolvedValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : mapping.entrySet()) {
            Object value = entry.getValue();
            if (DataMappingResolver.isArrayFieldMapping(value)) {
                result.put(entry.getKey(), resolveArrayFieldMapping((Map<String, Object>) value, resolvedValues));
            } else if (value instanceof Map<?, ?> nested) {
                result.put(entry.getKey(), applyResolvedValues((Map<String, Object>) nested, resolvedValues));
            } else if (value instanceof String str && isResolvableValue(str)) {
                result.put(entry.getKey(), resolvedValues.get(str));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    /**
     * Resolve a per-item array field mapping.
     * The _source expression is resolved to get the source list, then field name mappings
     * are applied to each item via {@link DataMappingResolver#mapArrayItems}.
     */
    @SuppressWarnings("unchecked")
    private Object resolveArrayFieldMapping(Map<String, Object> arrayMapping, Map<String, Object> resolvedValues) {
        String sourceExpression = (String) arrayMapping.get(DataMappingResolver.ARRAY_SOURCE_KEY);
        Object resolvedSource = (sourceExpression != null && isResolvableValue(sourceExpression))
                ? resolvedValues.get(sourceExpression)
                : sourceExpression;

        // Extract field name mappings (all entries except _source)
        Map<String, String> fieldMappings = new LinkedHashMap<>();
        for (var entry : arrayMapping.entrySet()) {
            if (!DataMappingResolver.ARRAY_SOURCE_KEY.equals(entry.getKey()) && entry.getValue() instanceof String str) {
                fieldMappings.put(entry.getKey(), str);
            }
        }

        if (resolvedSource instanceof List<?> sourceList) {
            return DataMappingResolver.mapArrayItems(sourceList, fieldMappings);
        }

        // Source didn't resolve to a list — return as-is (may be null or unexpected type)
        log.warn("Array field mapping _source did not resolve to a list: {}", resolvedSource);
        return resolvedSource;
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
     * Resolve variant attributes, resolving any value resolver expressions in the values.
     */
    private List<VariantSelectionAttribute> resolveVariantAttributes(
            DelegateExecution execution,
            Map<String, String> attributes
    ) {
        // Collect resolvable attribute values
        List<String> valuesToResolve = attributes.values().stream()
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
        return attributes.entrySet().stream()
                .map(entry -> {
                    String key = entry.getKey();
                    String rawValue = entry.getValue();
                    String resolvedValue = isResolvableValue(rawValue) && resolvedValues.containsKey(rawValue)
                            ? String.valueOf(resolvedValues.get(rawValue))
                            : rawValue;
                    return new VariantSelectionAttribute(key, resolvedValue, null);
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
