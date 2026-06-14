package com.ritense.valtimo.epistola.plugin;

import app.epistola.client.model.VariantSelectionAttribute;
import app.epistola.valtimo.domain.DocumentStorageTarget;
import app.epistola.valtimo.domain.EpistolaProcessVariables;
import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GenerationJobResult;
import app.epistola.valtimo.domain.GenerationJobDetail;
import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.service.completion.EpistolaMessageCorrelationService;
import app.epistola.valtimo.service.completion.EpistolaResultCollectorRunner;
import app.epistola.valtimo.service.download.DocumentStorageStrategy;

import app.epistola.valtimo.service.EpistolaService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.plugin.annotation.*;
import com.ritense.plugin.domain.EventType;
import com.ritense.processlink.domain.ActivityTypeWithEventName;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.delegate.DelegateExecution;

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

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final EpistolaService epistolaService;
    private final ObjectMapper objectMapper;
    private final JsonataMappingService jsonataMappingService;
    private final com.ritense.document.service.DocumentService documentService;
    private final EpistolaResultCollectorRunner resultCollectorRunner;
    private final Map<DocumentStorageTarget, DocumentStorageStrategy> storageStrategies;

    public EpistolaPlugin(
            EpistolaService epistolaService,
            ObjectMapper objectMapper,
            JsonataMappingService jsonataMappingService,
            com.ritense.document.service.DocumentService documentService,
            EpistolaResultCollectorRunner resultCollectorRunner,
            Map<DocumentStorageTarget, DocumentStorageStrategy> storageStrategies
    ) {
        this.epistolaService = epistolaService;
        this.objectMapper = objectMapper;
        this.jsonataMappingService = jsonataMappingService;
        this.documentService = documentService;
        this.resultCollectorRunner = resultCollectorRunner;
        this.storageStrategies = storageStrategies;
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
     * process variable. Completion is correlated later by the result collector.
     * <p>
     * Variant selection supports three modes:
     * <ul>
     *   <li>Default: omit both variantId and variantAttributes — the template's default variant is used</li>
     *   <li>Explicit: specify variantId directly</li>
     *   <li>By attributes: specify variantAttributes (key-value pairs), and the API selects the matching variant.
     *       Expression values can use the JSONata context ($doc, $pv, $case).</li>
     * </ul>
     * variantId and variantAttributes are mutually exclusive.
     *
     * @param execution             The process execution context
     * @param catalogId             The ID of the catalog containing the template
     * @param templateId            The ID of the template to use for document generation
     * @param variantId             The ID of the template variant (optional — omit to use default or attribute selection)
     * @param variantAttributes     Key-value attributes for automatic variant selection (optional).
     *                              Expression values can use the JSONata context ($doc, $pv, $case).
     * @param environmentId         The environment ID (optional, uses plugin default if not specified)
     * @param dataMapping           JSONata expression that produces the template data payload.
     *                              Has access to $doc (document data), $pv (process variables), $case (case data).
     * @param outputFormat          The desired output format (PDF or HTML)
     * @param filename              The filename for the generated document
     * @param correlationId         Optional correlation ID for tracking across systems
     * @param resultProcessVariable The name of the process variable to store the request ID in
     */
    @PluginAction(
            key = "epistola-generate-document",
            title = "Generate Document",
            description = "Submit a document generation request to Epistola. The request ID will be stored in the specified process variable.",
            activityTypes = {ActivityTypeWithEventName.SERVICE_TASK_START, ActivityTypeWithEventName.TASK_START}
    )
    public void generateDocument(
            DelegateExecution execution,
            @PluginActionProperty String catalogId,
            @PluginActionProperty String templateId,
            @PluginActionProperty String variantId,
            @PluginActionProperty Object variantAttributes,
            @PluginActionProperty String environmentId,
            @PluginActionProperty String dataMapping,
            @PluginActionProperty FileFormat outputFormat,
            @PluginActionProperty String filename,
            @PluginActionProperty String correlationId,
            @PluginActionProperty String resultProcessVariable
    ) {
        log.info("Starting document generation: catalogId={}, templateId={}, variantId={}, variantAttributes={}, outputFormat={}, filename={}",
                catalogId, templateId, variantId, variantAttributes, outputFormat, filename);

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
            // Evaluate JSONata expression to produce the template data
            var evalCtx = app.epistola.valtimo.mapping.EvaluationContext.builder()
                    .expression(dataMapping != null ? dataMapping : "")
                    .documentResolver(this::loadDocumentContent)
                    .processVariableResolver(execution::getVariable)
                    .processVariableEnumerator(execution::getVariables)
                    .execution(execution)
                    .documentId(execution.getBusinessKey())
                    .build();
            resolvedData = jsonataMappingService.evaluate(evalCtx);
        }

        // Resolve the filename as a JSONata expression
        String resolvedFilename = jsonataMappingService.evaluateScalar(buildEvalCtx(execution, filename));

        // Use action-level environmentId if provided, otherwise fall back to plugin default
        String effectiveEnvironmentId = (environmentId != null && !environmentId.isBlank())
                ? environmentId
                : defaultEnvironmentId;

        // Resolve variantId if it uses a JSONata expression
        String resolvedVariantId = hasVariantId
                ? jsonataMappingService.evaluateScalar(buildEvalCtx(execution, variantId))
                : null;

        // Build variant selection attributes, resolving each value as a JSONata expression
        List<VariantSelectionAttribute> resolvedAttributes = hasAttributes
                ? normalizedAttributes.stream()
                        .map(attr -> new VariantSelectionAttribute(
                                attr.key(),
                                jsonataMappingService.evaluateScalar(buildEvalCtx(execution, attr.value())),
                                null,
                                attr.required()))
                        .toList()
                : null;

        // Compute a routing key that targets this Valtimo node's collector partition.
        // If the collector hasn't completed its first poll yet (cold start) this returns null,
        // in which case the server falls back to the requestId as the routing key — the
        // result then routes by hash, which may land on another node and bypass us.
        String baseRoutingKey = correlationId != null && !correlationId.isBlank()
                ? correlationId
                : java.util.UUID.randomUUID().toString();
        String routingKey = resultCollectorRunner.routingKeyFor(baseUrl, apiKey, tenantId, baseRoutingKey);

        // Submit the document generation request
        GenerationJobResult result;
        try {
            result = epistolaService.submitGenerationJob(
                    baseUrl,
                    apiKey,
                    tenantId,
                    catalogId,
                    templateId,
                    resolvedVariantId,
                    resolvedAttributes,
                    effectiveEnvironmentId,
                    resolvedData,
                    outputFormat,
                    resolvedFilename,
                    correlationId,
                    routingKey
            );
        } catch (Exception e) {
            // Submit-time failure: write a FAILED rich object on resultProcessVariable so
            // downstream BPMN (or a Formio retry form) can read the error via
            // ${<resultProcessVariable>.errorMessage} just like a post-submit failure.
            Map<String, Object> failureData = new LinkedHashMap<>();
            failureData.put(EpistolaProcessVariables.RESULT_KEY_REQUEST_ID, null);
            failureData.put(EpistolaProcessVariables.RESULT_KEY_STATUS, "FAILED");
            failureData.put(EpistolaProcessVariables.RESULT_KEY_DOCUMENT_ID, null);
            failureData.put(EpistolaProcessVariables.RESULT_KEY_ERROR_MESSAGE,
                    "Document generation request failed: " + e.getMessage());
            execution.setVariable(resultProcessVariable, failureData);
            execution.setVariable(EpistolaProcessVariables.RESULT_VARIABLE_NAME, resultProcessVariable);
            throw new RuntimeException("Failed to submit document generation request to Epistola", e);
        }

        // Store a rich result object on the user-configured process variable. The
        // collector updates the same variable in-place when the result lands; users
        // read individual fields via JUEL: ${var.status}, ${var.documentId}, etc.
        // Pre-populated with status=PENDING so downstream BPMN can react immediately
        // (e.g. a "result not yet available" branch).
        Map<String, Object> resultData = new LinkedHashMap<>();
        resultData.put(EpistolaProcessVariables.RESULT_KEY_REQUEST_ID, result.getRequestId());
        resultData.put(EpistolaProcessVariables.RESULT_KEY_STATUS, "PENDING");
        resultData.put(EpistolaProcessVariables.RESULT_KEY_DOCUMENT_ID, null);
        resultData.put(EpistolaProcessVariables.RESULT_KEY_ERROR_MESSAGE, null);
        execution.setVariable(resultProcessVariable, resultData);

        // Companion variable: the *name* of resultProcessVariable on this instance,
        // so the result collector knows where to write the updated rich object later.
        execution.setVariable(EpistolaProcessVariables.RESULT_VARIABLE_NAME, resultProcessVariable);

        // Store tenantId as a standalone process variable so it can be used in forms
        // (e.g. for building document download URLs without parsing the composite jobPath)
        execution.setVariable(EpistolaProcessVariables.TENANT_ID, tenantId);

        // Store a single composite job path that encodes both tenantId and requestId.
        // This avoids scoping issues where separate variables might not both be visible
        // to the polling consumer's execution.
        String jobPath = EpistolaMessageCorrelationService.buildJobPath(tenantId, result.getRequestId());
        execution.setVariable(EpistolaProcessVariables.JOB_PATH, jobPath);

        // Hint the collector to look for the result soon — if it's currently
        // backed off into idle mode, this brings the next poll forward to
        // ~kickIntervalMs (default 3s) instead of waiting out the full backoff
        // (which can be up to maxIntervalMs, default 30s). Threshold-guarded
        // inside the contract collector: no-op when polling fast.
        resultCollectorRunner.kickFor(baseUrl, apiKey, tenantId);

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
            key = "epistola-check-job-status",
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
        // The variable can be either a plain String (legacy: when only `generate-document`
        // wrote the requestId) or a Map<String,Object> with a `requestId` key (current:
        // the rich result object). Accept both for backward compatibility — a process
        // mid-flight when this version ships might still hold the legacy String.
        Object rawValue = execution.getVariable(requestIdVariable);
        String requestId;
        if (rawValue instanceof Map<?, ?> map) {
            Object idValue = map.get(EpistolaProcessVariables.RESULT_KEY_REQUEST_ID);
            requestId = idValue == null ? null : idValue.toString();
        } else if (rawValue instanceof String str) {
            requestId = str;
        } else if (rawValue == null) {
            requestId = null;
        } else {
            throw new IllegalArgumentException("Variable '" + requestIdVariable
                    + "' must be a String or a Map with a '" + EpistolaProcessVariables.RESULT_KEY_REQUEST_ID
                    + "' key, got " + rawValue.getClass().getName());
        }
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
     * Download a generated document and materialize it for the rest of the process.
     * <p>
     * The document must have been successfully generated (status = COMPLETED) before it can be
     * downloaded. Where the bytes are materialized is selected by {@code storageTarget}; see
     * {@code docs/adr/0001-download-document-content-storage.md}.
     *
     * @param execution           The process execution context
     * @param documentVariable    Name of the process variable that holds the result. May be a
     *                            plain String document id (legacy) or a {@code Map<String,Object>}
     *                            rich result with a {@code documentId} key (canonical).
     * @param storageTarget       Where to materialize the PDF. Optional; defaults to
     *                            {@link DocumentStorageTarget#TEMPORARY_RESOURCE}.
     * @param resourceIdVariable  Output variable for {@code TEMPORARY_RESOURCE}: receives the temporary
     *                            resource id (hand this to {@code documenten-api:store-temp-document}).
     * @param contentVariable     Output variable for {@code PROCESS_VARIABLE}: receives the raw PDF
     *                            bytes inline (small/non-sensitive documents).
     */
    @PluginAction(
            key = "epistola-download-document",
            title = "Download Document",
            description = "Download a generated document from Epistola. By default stores it in temporary resource storage and writes the resource id to the configured variable (ready to hand to documenten-api:store-temp-document).",
            activityTypes = {ActivityTypeWithEventName.SERVICE_TASK_START, ActivityTypeWithEventName.TASK_START}
    )
    public void downloadDocument(
            DelegateExecution execution,
            @PluginActionProperty String documentVariable,
            @PluginActionProperty DocumentStorageTarget storageTarget,
            @PluginActionProperty String resourceIdVariable,
            @PluginActionProperty String contentVariable
    ) {
        // Type-tolerant: documentVariable may hold a plain String (legacy scalar) or a
        // Map<String,Object> with a documentId key (canonical rich-result-object). Both work.
        Object rawValue = execution.getVariable(documentVariable);
        String documentId;
        if (rawValue instanceof Map<?, ?> map) {
            Object docId = map.get(EpistolaProcessVariables.RESULT_KEY_DOCUMENT_ID);
            documentId = docId == null ? null : docId.toString();
        } else if (rawValue instanceof String str) {
            documentId = str;
        } else if (rawValue == null) {
            documentId = null;
        } else {
            throw new IllegalArgumentException("Variable '" + documentVariable
                    + "' must be a String or a Map with a '" + EpistolaProcessVariables.RESULT_KEY_DOCUMENT_ID
                    + "' key, got " + rawValue.getClass().getName());
        }
        log.info("Downloading document: {}", documentId);

        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("Document variable '" + documentVariable + "' is null or empty");
        }

        // Resolve the target + its output variable before downloading, so misconfiguration fails
        // fast — see docs/adr/0001-download-document-content-storage.md.
        DocumentStorageTarget target = storageTarget != null ? storageTarget : DocumentStorageTarget.TEMPORARY_RESOURCE;
        String outputVariable = switch (target) {
            case TEMPORARY_RESOURCE -> resourceIdVariable;
            case PROCESS_VARIABLE -> contentVariable;
        };
        if (outputVariable == null || outputVariable.isBlank()) {
            throw new IllegalArgumentException("download-document: no output variable configured for storageTarget " + target);
        }
        DocumentStorageStrategy strategy = storageStrategies.get(target);
        if (strategy == null) {
            throw new IllegalStateException("download-document: storageTarget " + target
                    + " is not available — its backend is not configured in this environment");
        }

        byte[] content = epistolaService.downloadDocument(baseUrl, apiKey, tenantId, documentId);
        strategy.store(execution, documentId, content, outputVariable);

        log.info("Document {} downloaded successfully ({} bytes, target={}, outputVariable={})",
                documentId, content.length, target, outputVariable);
    }

    /**
     * Load the full document content as a Map via DocumentService.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadDocumentContent(String documentId) {
        try {
            var doc = documentService.findBy(
                    com.ritense.document.domain.impl.JsonSchemaDocumentId.existingId(
                            java.util.UUID.fromString(documentId)));
            if (doc.isPresent()) {
                return (Map<String, Object>) objectMapper.convertValue(
                        doc.get().content().asJson(), Map.class);
            }
            log.warn("Document not found: {}", documentId);
            return Map.of();
        } catch (Exception e) {
            log.warn("Failed to load document content for {}: {}", documentId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Build an EvaluationContext for scalar expression evaluation (filename, variantId, attribute values).
     */
    private app.epistola.valtimo.mapping.EvaluationContext buildEvalCtx(DelegateExecution execution, String expression) {
        return app.epistola.valtimo.mapping.EvaluationContext.builder()
                .expression(expression)
                .documentResolver(this::loadDocumentContent)
                .processVariableResolver(execution::getVariable)
                .processVariableEnumerator(execution::getVariables)
                .execution(execution)
                .documentId(execution.getBusinessKey())
                .build();
    }

    /**
     * Internal representation of a variant attribute entry, supporting both old and new config formats.
     */
    record NormalizedAttribute(String key, String value, Boolean required) {}

    /**
     * Normalize variant attributes from either the old format (Map&lt;String, String&gt;)
     * or the new format (List of objects with key, value, required).
     */
    @SuppressWarnings("unchecked")
    List<NormalizedAttribute> normalizeVariantAttributes(Object raw) {
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

}
