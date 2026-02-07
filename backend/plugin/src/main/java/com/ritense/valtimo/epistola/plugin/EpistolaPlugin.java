package com.ritense.valtimo.epistola.plugin;

import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GeneratedDocument;
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

    private final EpistolaService epistolaService;
    private final ValueResolverService valueResolverService;

    public EpistolaPlugin(EpistolaService epistolaService, ValueResolverService valueResolverService) {
        this.epistolaService = epistolaService;
        this.valueResolverService = valueResolverService;
    }

    /**
     * The tenant ID where the document templates are stored in Epistola.
     */
    @PluginProperty(
            key = "tenantId",
            title = "Tenant ID",
            secret = false,
            required = true
    )
    private String tenantId;

    public String getTenantId() {
        return tenantId;
    }

    @PluginEvent(invokedOn = EventType.CREATE)
    public void onPluginCreate() {
        log.info("Epistola plugin configuration created with tenantId: {}", tenantId);
    }

    @PluginEvent(invokedOn = EventType.DELETE)
    public void onPluginDelete() {
        log.info("Epistola plugin configuration deleted");
    }

    @PluginEvent(invokedOn = EventType.UPDATE)
    public void onPluginUpdate() {
        log.info("Epistola plugin configuration updated with tenantId: {}", tenantId);
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
     * @param dataMapping           Key-value mapping of template fields to data sources.
     *                              Values can use prefixes: doc: (case data), pv: (process variable)
     * @param outputFormat          The desired output format (PDF or HTML)
     * @param filename              The filename for the generated document (can use value resolvers)
     * @param resultProcessVariable The name of the process variable to store the request ID in
     */
    @PluginAction(
            key = "generate-document",
            title = "Generate Document",
            description = "Submit a document generation request to Epistola. The request ID will be stored in the specified process variable.",
            activityTypes = {ActivityTypeWithEventName.SERVICE_TASK_START,ActivityTypeWithEventName.TASK_START}
    )
    public void generateDocument(
            DelegateExecution execution,
            @PluginActionProperty String templateId,
            @PluginActionProperty Map<String, String> dataMapping,
            @PluginActionProperty FileFormat outputFormat,
            @PluginActionProperty String filename,
            @PluginActionProperty String resultProcessVariable
    ) {
        log.info("Starting document generation: templateId={}, outputFormat={}, filename={}",
                templateId, outputFormat, filename);

        // Resolve the data mapping values (doc:, pv:, etc.)
        Map<String, Object> resolvedData = resolveDataMapping(execution, dataMapping);

        // Resolve the filename if it uses value resolvers
        String resolvedFilename = resolveValue(execution, filename);

        // Submit the document generation request
        GeneratedDocument result = epistolaService.generateDocument(
                tenantId,
                templateId,
                resolvedData,
                outputFormat,
                resolvedFilename
        );

        // Store the request ID in the process variable
        execution.setVariable(resultProcessVariable, result.getDocumentId());

        log.info("Document generation request submitted. Request ID stored in variable '{}': {}",
                resultProcessVariable, result.getDocumentId());
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
