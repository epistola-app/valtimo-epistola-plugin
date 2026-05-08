package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.web.rest.dto.PreviewRequest;
import com.ritense.authorization.AuthorizationService;
import com.ritense.authorization.request.AuthorizationRequest;
import com.ritense.authorization.request.EntityAuthorizationRequest;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.operaton.authorization.OperatonTaskActionProvider;
import com.ritense.valtimo.operaton.domain.OperatonExecution;
import com.ritense.valtimo.operaton.domain.OperatonTask;
import com.ritense.valtimo.security.exceptions.TaskNotFoundException;
import com.ritense.valtimo.service.OperatonTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the authorization contract for the user-task-bound endpoints
 * (preview, retry-form):
 *
 * <ul>
 *   <li>{@code OperatonTask:VIEW} on the supplied {@code taskId}.</li>
 *   <li>The supplied {@code processInstanceId} matches the task's process instance.</li>
 *   <li>The supplied {@code documentId} matches the task's process business key
 *       (the Valtimo case document UUID).</li>
 * </ul>
 */
class EpistolaGenerationResourceAuthorizationTest {

    private static final String TASK_ID = "task-789";
    private static final String PROCESS_INSTANCE_ID = "process-instance-1";
    private static final String DOCUMENT_ID = "doc-1";

    private AuthorizationService authorizationService;
    private OperatonTaskService operatonTaskService;
    private app.epistola.valtimo.service.preview.PreviewService previewService;
    private app.epistola.valtimo.service.form.RetryFormService retryFormService;
    private EpistolaGenerationResource resource;
    private OperatonTask task;
    private OperatonExecution processInstance;

    @BeforeEach
    void setUp() {
        var pluginService = mock(PluginService.class);
        var epistolaService = mock(EpistolaService.class);
        previewService = mock(app.epistola.valtimo.service.preview.PreviewService.class);
        retryFormService = mock(app.epistola.valtimo.service.form.RetryFormService.class);
        var jsonataMappingService = mock(app.epistola.valtimo.mapping.JsonataMappingService.class);
        var documentService = mock(com.ritense.document.service.DocumentService.class);
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        authorizationService = mock(AuthorizationService.class);
        operatonTaskService = mock(OperatonTaskService.class);

        task = mock(OperatonTask.class);
        processInstance = mock(OperatonExecution.class);
        when(task.getProcessInstanceId()).thenReturn(PROCESS_INSTANCE_ID);
        when(task.getProcessInstance()).thenReturn(processInstance);
        when(processInstance.getBusinessKey()).thenReturn(DOCUMENT_ID);
        when(operatonTaskService.findTaskById(TASK_ID)).thenReturn(task);

        resource = new EpistolaGenerationResource(pluginService, epistolaService,
                previewService, retryFormService, jsonataMappingService,
                documentService, objectMapper, authorizationService, operatonTaskService,
                mock(org.operaton.bpm.engine.RuntimeService.class));
    }

    private PreviewRequest validPreviewRequest() {
        return new PreviewRequest(
                TASK_ID, DOCUMENT_ID, "process-key", "activity-1", PROCESS_INSTANCE_ID, null, null);
    }

    @Test
    void preview_succeedsWhenTaskBindingMatches() throws Exception {
        when(previewService.generatePreview(org.mockito.ArgumentMatchers.any(PreviewRequest.class)))
                .thenReturn(new java.io.ByteArrayInputStream(new byte[]{0x25, 0x50, 0x44, 0x46})); // %PDF

        var response = resource.previewDocument(validPreviewRequest());

        var captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorizationService).requirePermission(captor.capture());
        var request = (EntityAuthorizationRequest<?>) captor.getValue();
        assertThat(request.getResourceType()).isEqualTo(OperatonTask.class);
        assertThat(request.getAction()).isEqualTo(OperatonTaskActionProvider.VIEW);
        assertThat(request.getEntities()).hasSize(1);
        assertThat(request.getEntities().get(0)).isSameAs(task);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void preview_returns403WhenProcessInstanceIdDoesNotMatchTask() {
        var request = new PreviewRequest(
                TASK_ID, DOCUMENT_ID, "process-key", "activity-1", "other-process-instance", null, null);

        assertThatThrownBy(() -> resource.previewDocument(request))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void preview_returns403WhenDocumentIdDoesNotMatchTaskBusinessKey() {
        var request = new PreviewRequest(
                TASK_ID, "other-doc", "process-key", "activity-1", PROCESS_INSTANCE_ID, null, null);

        assertThatThrownBy(() -> resource.previewDocument(request))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void preview_returns403WhenTaskBusinessKeyIsNull() {
        when(processInstance.getBusinessKey()).thenReturn(null);

        assertThatThrownBy(() -> resource.previewDocument(validPreviewRequest()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void preview_returns400WhenTaskIdMissing() {
        var request = new PreviewRequest(
                null, DOCUMENT_ID, "process-key", "activity-1", PROCESS_INSTANCE_ID, null, null);

        assertThat(resource.previewDocument(request).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void preview_returns400WhenDocumentIdMissing() {
        var request = new PreviewRequest(
                TASK_ID, null, "process-key", "activity-1", PROCESS_INSTANCE_ID, null, null);

        assertThat(resource.previewDocument(request).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void preview_returns400WhenProcessInstanceIdMissing() {
        var request = new PreviewRequest(
                TASK_ID, DOCUMENT_ID, "process-key", "activity-1", null, null, null);

        assertThat(resource.previewDocument(request).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void preview_returns404WhenTaskNotFound() {
        when(operatonTaskService.findTaskById("missing")).thenThrow(new TaskNotFoundException("missing"));
        var request = new PreviewRequest(
                "missing", DOCUMENT_ID, "process-key", "activity-1", PROCESS_INSTANCE_ID, null, null);

        assertThat(resource.previewDocument(request).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void preview_propagatesAccessDeniedAs403() {
        doThrow(new AccessDeniedException("nope"))
                .when(authorizationService).requirePermission(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> resource.previewDocument(validPreviewRequest()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void retryForm_succeedsWhenTaskBindingMatches() {
        when(retryFormService.generateRetryForm(PROCESS_INSTANCE_ID, DOCUMENT_ID, null))
                .thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());

        var response = resource.getRetryForm(TASK_ID, PROCESS_INSTANCE_ID, DOCUMENT_ID, null);

        var captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorizationService).requirePermission(captor.capture());
        var request = (EntityAuthorizationRequest<?>) captor.getValue();
        assertThat(request.getResourceType()).isEqualTo(OperatonTask.class);
        assertThat(request.getAction()).isEqualTo(OperatonTaskActionProvider.VIEW);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void retryForm_returns403WhenProcessInstanceIdDoesNotMatchTask() {
        assertThatThrownBy(() ->
                resource.getRetryForm(TASK_ID, "other-process-instance", DOCUMENT_ID, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void retryForm_returns403WhenDocumentIdDoesNotMatchTaskBusinessKey() {
        assertThatThrownBy(() ->
                resource.getRetryForm(TASK_ID, PROCESS_INSTANCE_ID, "other-doc", null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void retryForm_returns400WhenAnyParamMissing() {
        assertThat(resource.getRetryForm("", PROCESS_INSTANCE_ID, DOCUMENT_ID, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resource.getRetryForm(TASK_ID, "", DOCUMENT_ID, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resource.getRetryForm(TASK_ID, PROCESS_INSTANCE_ID, "", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void retryForm_returns404WhenTaskNotFound() {
        when(operatonTaskService.findTaskById("missing")).thenThrow(new TaskNotFoundException("missing"));

        assertThat(resource.getRetryForm("missing", PROCESS_INSTANCE_ID, DOCUMENT_ID, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
