package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.preview.PreviewService;
import app.epistola.valtimo.service.form.RetryFormService;
import app.epistola.valtimo.web.rest.dto.EvaluationRequest;
import app.epistola.valtimo.web.rest.dto.EvaluationResult;
import app.epistola.valtimo.web.rest.dto.PreviewRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.authorization.AuthorizationService;
import com.ritense.authorization.request.EntityAuthorizationRequest;
import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.contract.annotation.SkipComponentScan;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import com.ritense.valtimo.operaton.authorization.OperatonTaskActionProvider;
import com.ritense.valtimo.operaton.domain.OperatonTask;
import com.ritense.valtimo.security.exceptions.TaskNotFoundException;
import com.ritense.valtimo.service.OperatonTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller for Epistola generation, preview, and download operations.
 *
 * <p>Endpoints invoked from user task forms (preview, preview-sources, download, retry-form)
 * authorize on {@code OperatonTask:VIEW} for a {@code taskId} the caller supplies. Spring
 * Security translates {@code AccessDeniedException} from the framework into HTTP 403.
 *
 * <p>{@code evaluateMapping} is reachable only to {@code ROLE_ADMIN} via the HTTP layer
 * (it is used by the process-link configurator only) and has no controller-level PBAC check.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/plugin/epistola")
@SkipComponentScan
@RequiredArgsConstructor
public class EpistolaGenerationResource {

    private final PluginService pluginService;
    private final EpistolaService epistolaService;
    private final PreviewService previewService;
    private final RetryFormService retryFormService;
    private final JsonataMappingService jsonataMappingService;
    private final com.ritense.document.service.DocumentService documentService;
    private final ObjectMapper objectMapper;
    private final AuthorizationService authorizationService;
    private final OperatonTaskService operatonTaskService;

    /**
     * Evaluate a JSONata data mapping expression against a real document.
     * Returns the resolved JSON output that would be sent to Epistola.
     */
    @PostMapping("/evaluate-mapping")
    @SuppressWarnings("unchecked")
    public ResponseEntity<EvaluationResult> evaluateMapping(@RequestBody EvaluationRequest request) {
        log.debug("Evaluating mapping expression against document {}", request.documentId());
        try {
            var evalCtx = app.epistola.valtimo.mapping.EvaluationContext.builder()
                    .expression(request.expression())
                    .documentResolver(this::loadDocumentContent)
                    .documentId(request.documentId())
                    .build();
            Map<String, Object> result = jsonataMappingService.evaluate(evalCtx);
            return ResponseEntity.ok(EvaluationResult.success(result));
        } catch (Exception e) {
            return ResponseEntity.ok(EvaluationResult.failure(e.getMessage()));
        }
    }

    /**
     * Download a generated document directly from Epistola.
     * Resolves the plugin configuration by tenantId and proxies the download.
     *
     * <p>Requires {@code OperatonTask:VIEW} on the supplied {@code taskId} — the download is
     * only valid in the context of a user task the caller can already open.
     *
     * @param documentId The Epistola document ID
     * @param tenantId   The Epistola tenant ID (used to find the correct plugin configuration)
     * @param filename   The desired filename for the download (defaults to "document.pdf")
     * @param taskId     The Operaton user task ID providing the authorization context
     * @return The document bytes with PDF content type and attachment disposition
     */
    @GetMapping("/documents/{documentId}/download")
    public ResponseEntity<byte[]> downloadDocument(
            @PathVariable("documentId") String documentId,
            @RequestParam("tenantId") String tenantId,
            @RequestParam(value = "filename", defaultValue = "document.pdf") String filename,
            @RequestParam("taskId") String taskId
    ) {
        log.debug("Downloading document {} for tenantId={} taskId={}", documentId, tenantId, taskId);

        if (taskId == null || taskId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            requireTaskViewPermission(taskId);
        } catch (TaskNotFoundException e) {
            return ResponseEntity.notFound().build();
        }

        EpistolaPlugin plugin = findPluginByTenantId(tenantId);
        if (plugin == null) {
            log.warn("No Epistola plugin configuration found for tenantId='{}'", tenantId);
            return ResponseEntity.notFound().build();
        }

        byte[] content = epistolaService.downloadDocument(
                plugin.getBaseUrl(), plugin.getApiKey(), tenantId, documentId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename)
                .build());

        return ResponseEntity.ok().headers(headers).body(content);
    }

    /**
     * Get a dynamically generated Formio form for retrying a failed document generation.
     *
     * <p>Requires {@code OperatonTask:VIEW} on the supplied {@code taskId} — the retry form
     * is rendered inside a user task form and must only be accessible to a user who can
     * already open that task.
     *
     * @param taskId            The Operaton user task ID providing the authorization context
     * @param processInstanceId The process instance ID (used to find the process definition)
     * @param documentId        The Valtimo document ID (used to resolve doc: expressions)
     * @param sourceActivityId  The BPMN activity ID of the original generate-document service task (optional)
     * @return A Formio form definition with prefilled values
     */
    @GetMapping("/retry-form")
    public ResponseEntity<ObjectNode> getRetryForm(
            @RequestParam("taskId") String taskId,
            @RequestParam("processInstanceId") String processInstanceId,
            @RequestParam(value = "documentId", required = false) String documentId,
            @RequestParam(value = "sourceActivityId", required = false) String sourceActivityId
    ) {
        if (taskId == null || taskId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            requireTaskViewPermission(taskId);
        } catch (TaskNotFoundException e) {
            return ResponseEntity.notFound().build();
        }

        try {
            ObjectNode form = retryFormService.generateRetryForm(processInstanceId, documentId, sourceActivityId);
            return ResponseEntity.ok(form);
        } catch (RetryFormService.RetryFormException e) {
            log.warn("Failed to generate retry form: {}", e.getMessage());
            return switch (e.getReason()) {
                case PROCESS_NOT_FOUND, LINK_NOT_FOUND -> ResponseEntity.notFound().build();
                case AMBIGUOUS_ACTIVITY, MISSING_TEMPLATE, NO_DOCUMENT_ID -> ResponseEntity.badRequest().build();
            };
        }
    }

    /**
     * Discover all previewable document sources for a given Valtimo document.
     * Returns generate-document process links from running process instances.
     *
     * <p>Requires {@code OperatonTask:VIEW} on the supplied {@code taskId}.
     *
     * @param documentId The Valtimo document ID
     * @param taskId     The Operaton user task ID providing the authorization context
     * @return List of preview sources
     */
    @GetMapping("/preview-sources")
    public ResponseEntity<?> getPreviewSources(
            @RequestParam("documentId") String documentId,
            @RequestParam("taskId") String taskId
    ) {
        if (taskId == null || taskId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "taskId is required"));
        }
        try {
            requireTaskViewPermission(taskId);
        } catch (TaskNotFoundException e) {
            return ResponseEntity.notFound().build();
        }

        try {
            var sources = previewService.getPreviewSources(documentId);
            return ResponseEntity.ok(sources);
        } catch (Exception e) {
            log.debug("Failed to discover preview sources: {}", e.getMessage());
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Failed to discover preview sources: " + e.getMessage()));
        }
    }

    /**
     * Preview a document by "dry-running" the generate-document process link.
     *
     * <p>Resolves the data mapping, merges with optional overrides, and calls Epistola's
     * preview API to render a PDF without creating a generation job.
     *
     * <p>Requires {@code OperatonTask:VIEW} on {@link PreviewRequest#taskId()}.
     *
     * @param request The preview request with task context, document context, and optional overrides
     * @return Rendered PDF (inline) or error details
     */
    @PostMapping("/preview")
    public ResponseEntity<?> previewDocument(@RequestBody PreviewRequest request) {
        if (request.documentId() == null) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "documentId is required"));
        }
        if (request.taskId() == null || request.taskId().isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "taskId is required"));
        }
        try {
            requireTaskViewPermission(request.taskId());
        } catch (TaskNotFoundException e) {
            return ResponseEntity.notFound().build();
        }

        try {
            java.io.InputStream pdfStream = previewService.generatePreview(request);

            var resource = new org.springframework.core.io.InputStreamResource(pdfStream);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(ContentDisposition.inline()
                    .filename("preview.pdf")
                    .build());

            return ResponseEntity.ok().headers(headers).body(resource);
        } catch (PreviewService.PreviewException e) {
            log.debug("Preview unavailable: {}", e.getMessage());
            return switch (e.getReason()) {
                case PROCESS_NOT_FOUND, LINK_NOT_FOUND -> ResponseEntity.notFound().build();
                case AMBIGUOUS_ACTIVITY, MISSING_TEMPLATE, MISSING_CONTEXT ->
                        ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                case RENDER_FAILED ->
                        ResponseEntity.unprocessableEntity().body(Map.of(
                                "error", "Preview could not be generated",
                                "details", e.getMessage()));
            };
        }
    }

    /**
     * Resolve the task and require {@code OperatonTask:VIEW} for the current user.
     *
     * <p>{@code OperatonTaskService.findTaskById} performs the {@code VIEW} check itself,
     * so a separate {@code requirePermission} call is not needed — but we issue one anyway
     * for explicitness and to make the intent obvious in tests.
     */
    private void requireTaskViewPermission(String taskId) {
        OperatonTask task = operatonTaskService.findTaskById(taskId);
        authorizationService.requirePermission(
                new EntityAuthorizationRequest<>(
                        OperatonTask.class,
                        OperatonTaskActionProvider.VIEW,
                        List.of(task)));
    }

    /**
     * Find the Epistola plugin configuration matching the given tenantId.
     *
     * @return The matching plugin instance, or null if not found
     */
    private EpistolaPlugin findPluginByTenantId(String tenantId) {
        List<?> configurations = pluginService.findPluginConfigurations(
                EpistolaPlugin.class, props -> true);

        for (Object config : configurations) {
            try {
                EpistolaPlugin plugin = (EpistolaPlugin) pluginService.createInstance(
                        (PluginConfiguration) config);
                if (tenantId.equals(plugin.getTenantId())) {
                    return plugin;
                }
            } catch (Exception e) {
                log.warn("Failed to create plugin instance from configuration: {}", e.getMessage());
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadDocumentContent(String documentId) {
        try {
            var doc = documentService.findBy(
                    com.ritense.document.domain.impl.JsonSchemaDocumentId.existingId(java.util.UUID.fromString(documentId)));
            if (doc.isPresent()) {
                return (Map<String, Object>) objectMapper.convertValue(
                        doc.get().content().asJson(), Map.class);
            }
            return Map.of();
        } catch (Exception e) {
            log.warn("Failed to load document content for {}: {}", documentId, e.getMessage());
            return Map.of();
        }
    }
}
