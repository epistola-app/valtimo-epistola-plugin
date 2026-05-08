package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.web.rest.dto.PreviewRequest;
import com.ritense.authorization.AuthorizationService;
import com.ritense.authorization.request.AuthorizationRequest;
import com.ritense.authorization.request.EntityAuthorizationRequest;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.operaton.authorization.OperatonTaskActionProvider;
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
 * Verifies that the user-task-bound endpoints (preview, preview-sources, retry-form)
 * resolve the supplied taskId and authorize via {@code OperatonTask:VIEW}.
 */
class EpistolaGenerationResourceAuthorizationTest {

    private static final String TASK_ID = "task-789";

    private AuthorizationService authorizationService;
    private OperatonTaskService operatonTaskService;
    private app.epistola.valtimo.service.preview.PreviewService previewService;
    private app.epistola.valtimo.service.form.RetryFormService retryFormService;
    private EpistolaGenerationResource resource;
    private OperatonTask task;

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
        when(operatonTaskService.findTaskById(TASK_ID)).thenReturn(task);

        resource = new EpistolaGenerationResource(pluginService, epistolaService,
                previewService, retryFormService, jsonataMappingService,
                documentService, objectMapper, authorizationService, operatonTaskService);
    }

    @Test
    void previewSources_requiresOperatonTaskView() {
        ResponseEntity<?> response = resource.getPreviewSources("doc-1", TASK_ID);

        // Permission was checked with the right resource type, action, and entity
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
    void previewSources_returns400WhenTaskIdBlank() {
        ResponseEntity<?> response = resource.getPreviewSources("doc-1", "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void previewSources_returns404WhenTaskNotFound() {
        when(operatonTaskService.findTaskById("missing")).thenThrow(new TaskNotFoundException("missing"));

        ResponseEntity<?> response = resource.getPreviewSources("doc-1", "missing");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void previewSources_propagatesAccessDeniedAs403() {
        doThrow(new AccessDeniedException("nope"))
                .when(authorizationService).requirePermission(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> resource.getPreviewSources("doc-1", TASK_ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void preview_returns400WhenTaskIdMissing() {
        var request = new PreviewRequest(null, "doc-1", "process-key", "activity-1", "process-instance-1", null, null);

        ResponseEntity<?> response = resource.previewDocument(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void preview_returns400WhenDocumentIdMissing() {
        var request = new PreviewRequest(TASK_ID, null, "process-key", "activity-1", "process-instance-1", null, null);

        ResponseEntity<?> response = resource.previewDocument(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void retryForm_requiresOperatonTaskView() {
        when(retryFormService.generateRetryForm("process-instance-1", null, null))
                .thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());

        ResponseEntity<?> response = resource.getRetryForm(TASK_ID, "process-instance-1", null, null);

        var captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorizationService).requirePermission(captor.capture());
        var request = (EntityAuthorizationRequest<?>) captor.getValue();
        assertThat(request.getResourceType()).isEqualTo(OperatonTask.class);
        assertThat(request.getAction()).isEqualTo(OperatonTaskActionProvider.VIEW);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void retryForm_returns400WhenTaskIdMissing() {
        ResponseEntity<?> response = resource.getRetryForm("", "process-instance-1", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void retryForm_returns404WhenTaskNotFound() {
        when(operatonTaskService.findTaskById("missing")).thenThrow(new TaskNotFoundException("missing"));

        ResponseEntity<?> response = resource.getRetryForm("missing", "process-instance-1", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
