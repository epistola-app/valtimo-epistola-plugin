/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
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
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the authorization contract for the user-task-bound endpoints (preview, retry-form).
 *
 * <p>Both require {@code OperatonTask:VIEW} on the supplied {@code taskId} and derive the process
 * instance + case document <i>from that task</i> — the caller never supplies them, so there is no
 * forgery vector to cross-check. These tests assert: the {@code VIEW} permission is required; the
 * derived ids are what the service is called with; missing {@code taskId}/{@code sourceActivityId}
 * → 400; an unknown task → 404; a denied permission propagates as 403.
 */
class EpistolaGenerationResourceAuthorizationTest {

    private static final String TASK_ID = "task-789";
    private static final String PROCESS_INSTANCE_ID = "process-instance-1";
    private static final String DOCUMENT_ID = "doc-1";
    private static final String SOURCE_ACTIVITY_ID = "activity-1";

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
        return new PreviewRequest(TASK_ID, SOURCE_ACTIVITY_ID, null, null);
    }

    // ---- preview ----

    @Test
    void preview_requiresViewAndDerivesContextFromTask() throws Exception {
        when(previewService.generatePreview(any(PreviewRequest.class), eq(DOCUMENT_ID), eq(PROCESS_INSTANCE_ID)))
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
        // The service is called with the task-derived document + process instance, not wire values.
        verify(previewService).generatePreview(any(PreviewRequest.class), eq(DOCUMENT_ID), eq(PROCESS_INSTANCE_ID));
    }

    @Test
    void preview_returns400WhenTaskIdMissing() {
        var request = new PreviewRequest(null, SOURCE_ACTIVITY_ID, null, null);

        assertThat(resource.previewDocument(request).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void preview_allowsBlankSourceActivityId_forAutoDiscovery() {
        // sourceActivityId is optional — PreviewService auto-discovers the generate-document
        // link when it is blank (used by the retry form's embedded preview).
        when(previewService.generatePreview(any(PreviewRequest.class), eq(DOCUMENT_ID), eq(PROCESS_INSTANCE_ID)))
                .thenReturn(new java.io.ByteArrayInputStream(new byte[]{0x25, 0x50, 0x44, 0x46}));

        var response = resource.previewDocument(new PreviewRequest(TASK_ID, null, null, null));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void preview_returns404WhenTaskNotFound() {
        when(operatonTaskService.findTaskById("missing")).thenThrow(new TaskNotFoundException("missing"));
        var request = new PreviewRequest("missing", SOURCE_ACTIVITY_ID, null, null);

        assertThat(resource.previewDocument(request).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void preview_propagatesAccessDeniedAs403() {
        doThrow(new AccessDeniedException("nope"))
                .when(authorizationService).requirePermission(any());

        assertThatThrownBy(() -> resource.previewDocument(validPreviewRequest()))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- retry-form ----

    @Test
    void retryForm_requiresViewAndDerivesContextFromTask() {
        when(retryFormService.generateRetryForm(PROCESS_INSTANCE_ID, DOCUMENT_ID, null))
                .thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());

        var response = resource.getRetryForm(TASK_ID, null);

        var captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorizationService).requirePermission(captor.capture());
        var request = (EntityAuthorizationRequest<?>) captor.getValue();
        assertThat(request.getResourceType()).isEqualTo(OperatonTask.class);
        assertThat(request.getAction()).isEqualTo(OperatonTaskActionProvider.VIEW);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // Built against the task-derived process instance + case document.
        verify(retryFormService).generateRetryForm(PROCESS_INSTANCE_ID, DOCUMENT_ID, null);
    }

    @Test
    void retryForm_returns400WhenTaskIdMissing() {
        assertThat(resource.getRetryForm("", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void retryForm_returns404WhenTaskNotFound() {
        when(operatonTaskService.findTaskById("missing")).thenThrow(new TaskNotFoundException("missing"));

        assertThat(resource.getRetryForm("missing", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void retryForm_propagatesAccessDeniedAs403() {
        doThrow(new AccessDeniedException("nope"))
                .when(authorizationService).requirePermission(any());

        assertThatThrownBy(() -> resource.getRetryForm(TASK_ID, null))
                .isInstanceOf(AccessDeniedException.class);
    }
}
