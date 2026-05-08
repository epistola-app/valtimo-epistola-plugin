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
import org.springframework.security.access.AccessDeniedException;
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
 * <p>Endpoints invoked from user task forms (preview, retry-form) authorize on
 * {@code OperatonTask:VIEW} AND verify that the supplied {@code processInstanceId} and
 * {@code documentId} are bound to the supplied task — the request must target the same
 * process instance and the same case document the task is operating on. Without this
 * second check, a caller with VIEW on any task could pivot to any document by supplying
 * foreign ids alongside a task id they legitimately own.
 *
 * <p>{@code downloadDocument} keeps the legacy {@code OperatonTask:VIEW}-only check
 * pending a separate decision on whether the endpoint should remain at all.
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
     * <p>Requires {@code OperatonTask:VIEW} on the supplied {@code taskId} AND that the
     * supplied {@code processInstanceId} matches the task's process instance AND that
     * the supplied {@code documentId} matches the task's case document (process business key).
     *
     * @param taskId            The Operaton user task ID providing the authorization context
     * @param processInstanceId The process instance ID containing the original generate-document
     * @param documentId        The Valtimo document ID (used to resolve doc: expressions)
     * @param sourceActivityId  The BPMN activity ID of the original generate-document service task (optional)
     * @return A Formio form definition with prefilled values
     */
    @GetMapping("/retry-form")
    public ResponseEntity<ObjectNode> getRetryForm(
            @RequestParam("taskId") String taskId,
            @RequestParam("processInstanceId") String processInstanceId,
            @RequestParam("documentId") String documentId,
            @RequestParam(value = "sourceActivityId", required = false) String sourceActivityId
    ) {
        if (taskId == null || taskId.isBlank()
                || processInstanceId == null || processInstanceId.isBlank()
                || documentId == null || documentId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            requireTaskBoundTo(taskId, processInstanceId, documentId);
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
     * Preview a document by "dry-running" the generate-document process link.
     *
     * <p>Resolves the data mapping, merges with optional overrides, and calls Epistola's
     * preview API to render a PDF without creating a generation job.
     *
     * <p>Requires {@code OperatonTask:VIEW} on {@link PreviewRequest#taskId()} AND that
     * {@link PreviewRequest#processInstanceId()} matches the task's process instance AND
     * that {@link PreviewRequest#documentId()} matches the task's case document
     * (process business key).
     *
     * @param request The preview request with task context, document context, and optional overrides
     * @return Rendered PDF (inline) or error details
     */
    @PostMapping("/preview")
    public ResponseEntity<?> previewDocument(@RequestBody PreviewRequest request) {
        if (request.documentId() == null || request.documentId().isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "documentId is required"));
        }
        if (request.taskId() == null || request.taskId().isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "taskId is required"));
        }
        if (request.processInstanceId() == null || request.processInstanceId().isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "processInstanceId is required"));
        }
        try {
            requireTaskBoundTo(request.taskId(), request.processInstanceId(), request.documentId());
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
     * Resolve the task, require {@code OperatonTask:VIEW}, and verify the request is
     * bound to the same process instance and case document the task is operating on.
     *
     * <p>{@code OperatonTaskService.findTaskById} performs the {@code VIEW} check itself,
     * but we issue an explicit {@code requirePermission} too so the intent and the
     * thrown {@link AccessDeniedException} are obvious in tests. After permission is
     * confirmed we compare the task's {@code processInstanceId} and business key
     * (which Valtimo dossier-driven processes set to the case document UUID) against
     * the request parameters. Mismatch throws {@link AccessDeniedException} → HTTP 403.
     */
    private OperatonTask requireTaskBoundTo(String taskId, String processInstanceId, String documentId) {
        OperatonTask task = operatonTaskService.findTaskById(taskId);
        authorizationService.requirePermission(
                new EntityAuthorizationRequest<>(
                        OperatonTask.class,
                        OperatonTaskActionProvider.VIEW,
                        List.of(task)));

        String taskProcessInstanceId = task.getProcessInstanceId();
        if (!processInstanceId.equals(taskProcessInstanceId)) {
            log.debug("processInstanceId {} does not match task {} (taskPid={})",
                    processInstanceId, taskId, taskProcessInstanceId);
            throw new AccessDeniedException("Request processInstanceId does not match task");
        }

        String taskBusinessKey = task.getProcessInstance() != null
                ? task.getProcessInstance().getBusinessKey()
                : null;
        if (taskBusinessKey == null || !taskBusinessKey.equals(documentId)) {
            log.debug("documentId {} does not match task {} business key (taskBusinessKey={})",
                    documentId, taskId, taskBusinessKey);
            throw new AccessDeniedException("Request documentId does not match task's case");
        }
        return task;
    }

    /**
     * Resolve the task and require {@code OperatonTask:VIEW} only — used by the
     * download endpoint, which intentionally does not enforce same-case binding
     * yet (pending a separate decision on the endpoint's design).
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
