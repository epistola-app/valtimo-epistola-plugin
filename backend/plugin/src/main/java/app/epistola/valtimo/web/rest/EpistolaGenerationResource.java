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
import app.epistola.valtimo.domain.EpistolaProcessVariables;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.RuntimeService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.client.HttpClientErrorException;
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
 * <p>{@code downloadDocument} authorizes by the same task/case binding and resolves
 * the Epistola PDF id and tenant id from named process variables on the caller's
 * task. Callers never send a raw PDF id over the wire — forge-proof by construction.
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
    private final RuntimeService runtimeService;

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
     * Download a generated document by reading the PDF id and tenant from
     * named process variables on the caller's task. The caller never sends a
     * raw Epistola document id — this is forge-proof by construction.
     *
     * <p>Requires:
     * <ul>
     *   <li>{@code OperatonTask:VIEW} on {@code taskId}.</li>
     *   <li>{@code task.processInstance.businessKey == caseDocumentId} (same Valtimo case).</li>
     *   <li>The named {@code documentIdVariable} and {@code tenantIdVariable} both
     *       resolve to non-blank values on {@code task.processInstanceId}.</li>
     * </ul>
     *
     * @param taskId               The Operaton user task ID providing the authorization context
     * @param caseDocumentId       The Valtimo case document UUID; must equal {@code task.processInstance.businessKey}
     * @param documentIdVariable   Process-variable name holding the Epistola PDF id (default {@code epistolaResult}).
     *                             Resolves either a plain String (legacy scalar) OR a {@code Map<String,Object>}
     *                             with a {@code documentId} key (the canonical rich-result-object pattern).
     * @param tenantIdVariable     Process-variable name holding the Epistola tenant id (default {@code epistolaTenantId})
     * @param filename             Filename for the download (defaults to {@code document.pdf})
     * @param disposition          {@code attachment} (default) or {@code inline}
     * @return The PDF bytes with the requested Content-Disposition
     */
    @GetMapping("/documents/download")
    public ResponseEntity<byte[]> downloadDocument(
            @RequestParam("taskId") String taskId,
            @RequestParam("caseDocumentId") String caseDocumentId,
            @RequestParam(value = "documentIdVariable", defaultValue = "epistolaResult") String documentIdVariable,
            @RequestParam(value = "tenantIdVariable", defaultValue = EpistolaProcessVariables.TENANT_ID) String tenantIdVariable,
            @RequestParam(value = "filename", defaultValue = "document.pdf") String filename,
            @RequestParam(value = "disposition", defaultValue = "attachment") String disposition
    ) {
        if (taskId == null || taskId.isBlank()
                || caseDocumentId == null || caseDocumentId.isBlank()
                || documentIdVariable == null || documentIdVariable.isBlank()
                || tenantIdVariable == null || tenantIdVariable.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        OperatonTask task;
        try {
            task = requireTaskBoundTo(taskId, /* request supplies */ null, caseDocumentId);
            // requireTaskBoundTo accepts a null processInstanceId (only checks when supplied).
            // For download the task's processInstanceId is what we use to resolve variables;
            // no separate processInstanceId is sent on the wire.
        } catch (TaskNotFoundException e) {
            return ResponseEntity.notFound().build();
        }

        Object documentIdValue = runtimeService.getVariable(task.getProcessInstanceId(), documentIdVariable);
        Object tenantIdValue = runtimeService.getVariable(task.getProcessInstanceId(), tenantIdVariable);
        String documentId = resolveDocumentId(documentIdValue);
        String tenantId = tenantIdValue == null ? null : tenantIdValue.toString();

        if (documentId == null || documentId.isBlank() || tenantId == null || tenantId.isBlank()) {
            log.debug("Download requested for task {} but documentIdVariable={} / tenantIdVariable={} not yet set",
                    taskId, documentIdVariable, tenantIdVariable);
            return ResponseEntity.status(404)
                    .body(null);
        }

        EpistolaPlugin plugin = findPluginByTenantId(tenantId);
        if (plugin == null) {
            log.warn("No Epistola plugin configuration found for tenantId='{}'", tenantId);
            return ResponseEntity.notFound().build();
        }

        byte[] content;
        try {
            content = epistolaService.downloadDocument(
                    plugin.getBaseUrl(), plugin.getApiKey(), tenantId, documentId);
        } catch (HttpClientErrorException.NotFound e) {
            // Stale reference: process variable still holds an Epistola PDF id but the
            // server no longer has the document (data wiped, expired, deleted upstream).
            // Surface as 404 with a body distinct from the variable-null case so callers
            // can differentiate "not yet generated" from "no longer available".
            log.debug("Epistola returned 404 for documentId={} (tenantId={}); treating as not-available",
                    documentId, tenantId);
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        ContentDisposition contentDisposition = "inline".equalsIgnoreCase(disposition)
                ? ContentDisposition.inline().filename(filename).build()
                : ContentDisposition.attachment().filename(filename).build();
        headers.setContentDisposition(contentDisposition);

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
     *
     * @param taskId            Operaton user task id; required.
     * @param processInstanceId The expected process instance id, or {@code null} if the
     *                          caller infers it from the task itself (e.g.
     *                          {@code downloadDocument} doesn't carry it on the wire).
     *                          When non-null, must equal {@code task.getProcessInstanceId()}.
     * @param documentId        The expected Valtimo case-document UUID; required and
     *                          must equal {@code task.getProcessInstance().getBusinessKey()}.
     */
    private OperatonTask requireTaskBoundTo(String taskId, String processInstanceId, String documentId) {
        OperatonTask task = operatonTaskService.findTaskById(taskId);
        authorizationService.requirePermission(
                new EntityAuthorizationRequest<>(
                        OperatonTask.class,
                        OperatonTaskActionProvider.VIEW,
                        List.of(task)));

        String taskProcessInstanceId = task.getProcessInstanceId();
        if (processInstanceId != null && !processInstanceId.equals(taskProcessInstanceId)) {
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

    /**
     * Resolve a document id from whatever a process variable holds. Two shapes are supported:
     * <ul>
     *   <li>String — the legacy scalar {@code epistolaDocumentId} (still used by anything that
     *       writes a plain string into the configured variable).</li>
     *   <li>{@code Map<String,Object>} with a {@code documentId} key — the canonical
     *       rich-result-object pattern; the variable holds the full result and we dig out
     *       {@code documentId}. Both the {@code generate-document} action and the result
     *       collector write this shape.</li>
     * </ul>
     * Returns {@code null} if neither shape applies or the relevant value is null/blank.
     */
    private static String resolveDocumentId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Object docId = map.get(EpistolaProcessVariables.RESULT_KEY_DOCUMENT_ID);
            return docId == null ? null : docId.toString();
        }
        return value.toString();
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
