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
package app.epistola.valtimo.composer.web;

import app.epistola.valtimo.composer.ComposerException;
import app.epistola.valtimo.composer.LetterComposerService;
import app.epistola.valtimo.composer.LetterComposerService.ComposerContext;
import app.epistola.valtimo.composer.LetterComposerService.PreparedLetter;
import app.epistola.valtimo.web.rest.StartEventAuthorization;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.authorization.AuthorizationService;
import com.ritense.authorization.request.AuthorizationRequest;
import com.ritense.valtimo.operaton.domain.OperatonExecution;
import com.ritense.valtimo.operaton.domain.OperatonTask;
import com.ritense.valtimo.security.exceptions.TaskNotFoundException;
import com.ritense.valtimo.service.OperatonTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import java.io.ByteArrayInputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The composer endpoints authorize on {@code OperatonTask:VIEW} and then derive the process
 * definition, activity, process instance and case document from that task. The caller supplies a
 * template id only, and a template the task's form does not offer is refused by the service —
 * so there is nothing here to forge beyond a task the caller may already open.
 */
class EpistolaComposerResourceTest {

    private static final String TASK_ID = "task-1";
    private static final String COMPONENT_KEY = "pv:epistolaLetter";

    private AuthorizationService authorizationService;
    private OperatonTaskService operatonTaskService;
    private LetterComposerService letterComposerService;
    private StartEventAuthorization startEventAuthorization;
    private EpistolaComposerResource resource;
    private OperatonTask task;

    @BeforeEach
    void setUp() {
        authorizationService = mock(AuthorizationService.class);
        operatonTaskService = mock(OperatonTaskService.class);
        letterComposerService = mock(LetterComposerService.class);
        startEventAuthorization = mock(StartEventAuthorization.class);
        resource = new EpistolaComposerResource(
                letterComposerService, authorizationService, operatonTaskService,
                startEventAuthorization);

        OperatonExecution processInstance = mock(OperatonExecution.class);
        when(processInstance.getBusinessKey()).thenReturn("doc-1");
        task = mock(OperatonTask.class);
        when(task.getProcessDefinitionId()).thenReturn("process:1:abc");
        when(task.getTaskDefinitionKey()).thenReturn("choose-letter");
        when(task.getProcessInstanceId()).thenReturn("pi-1");
        when(task.getProcessInstance()).thenReturn(processInstance);
        when(operatonTaskService.findTaskById(TASK_ID)).thenReturn(task);
    }

    @Test
    void prepare_derivesEveryContextValueFromTheAuthorizedTask() {
        when(letterComposerService.prepare(any(), eq("besluit")))
                .thenReturn(new PreparedLetter("besluit", "Besluit", "gemeente",
                        Map.of("naam", "Jansen"), new ObjectMapper().createObjectNode(), true));

        var response = resource.prepare(new EpistolaComposerResource.PrepareRequest(TASK_ID, "besluit", COMPONENT_KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<ComposerContext> captor = ArgumentCaptor.forClass(ComposerContext.class);
        verify(letterComposerService).prepare(captor.capture(), eq("besluit"));
        assertThat(captor.getValue()).isEqualTo(
                new ComposerContext("process:1:abc", "choose-letter", "pi-1", "doc-1", COMPONENT_KEY));
    }

    @Test
    void prepare_requiresTaskViewPermission() {
        doThrow(new AccessDeniedException("denied"))
                .when(authorizationService).requirePermission(any(AuthorizationRequest.class));

        assertThatThrownBy(() -> resource.prepare(
                new EpistolaComposerResource.PrepareRequest(TASK_ID, "besluit", COMPONENT_KEY)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(letterComposerService);
    }

    @Test
    void prepare_rejectsAMissingTaskOrTemplate() {
        assertThat(resource.prepare(new EpistolaComposerResource.PrepareRequest(null, "besluit", COMPONENT_KEY))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resource.prepare(new EpistolaComposerResource.PrepareRequest(TASK_ID, " ", COMPONENT_KEY))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(letterComposerService);
    }

    @Test
    void prepare_returns404ForAnUnknownTask() {
        when(operatonTaskService.findTaskById("gone")).thenThrow(new TaskNotFoundException("gone"));

        assertThat(resource.prepare(new EpistolaComposerResource.PrepareRequest("gone", "besluit", COMPONENT_KEY))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void prepare_reportsAFormWithoutAComposerAs404() {
        when(letterComposerService.prepare(any(), any()))
                .thenThrow(new ComposerException(ComposerException.Reason.NO_COMPOSER, "none here"));

        assertThat(resource.prepare(new EpistolaComposerResource.PrepareRequest(TASK_ID, "besluit", COMPONENT_KEY))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void preview_refusesATemplateTheFormDoesNotOfferWith400() {
        when(letterComposerService.preview(any(), eq("geheime-brief"), any()))
                .thenThrow(new ComposerException(
                        ComposerException.Reason.TEMPLATE_NOT_OFFERED, "not offered"));

        var response = resource.preview(new EpistolaComposerResource.ComposerPreviewRequest(
                TASK_ID, "geheime-brief", COMPONENT_KEY, Map.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void preview_reportsARefusedRenderAs422() {
        when(letterComposerService.preview(any(), any(), any()))
                .thenThrow(new ComposerException(ComposerException.Reason.RENDER_FAILED, "nope"));

        var response = resource.preview(new EpistolaComposerResource.ComposerPreviewRequest(
                TASK_ID, "besluit", COMPONENT_KEY, Map.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void preview_servesThePdfInline() {
        when(letterComposerService.preview(any(), eq("besluit"), any()))
                .thenReturn(new ByteArrayInputStream("%PDF".getBytes()));

        var response = resource.preview(new EpistolaComposerResource.ComposerPreviewRequest(
                TASK_ID, "besluit", COMPONENT_KEY, Map.of("naam", "Jansen")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(response.getHeaders().getContentDisposition().isInline()).isTrue();
    }

    @Test
    void prepareOnStartForm_composesAgainstTheCaseItIsAuthorizedFor() {
        when(startEventAuthorization.require("correspondentie-ad-hoc", "doc-1"))
                .thenReturn(new StartEventAuthorization.StartContext("process:2:def", "doc-1", null));
        when(letterComposerService.prepare(any(), eq("besluit")))
                .thenReturn(new PreparedLetter("besluit", "Besluit", "gemeente",
                        Map.of(), new ObjectMapper().createObjectNode(), true));

        var response = resource.prepareOnStartForm(new EpistolaComposerResource.StartPrepareRequest(
                "correspondentie-ad-hoc", "doc-1", "besluit", COMPONENT_KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<ComposerContext> captor = ArgumentCaptor.forClass(ComposerContext.class);
        verify(letterComposerService).prepare(captor.capture(), eq("besluit"));
        // No task, so no activity and no process instance: the configuration comes from the
        // definition's start form and the mapping reads the case alone.
        assertThat(captor.getValue()).isEqualTo(
                new ComposerContext("process:2:def", null, null, "doc-1", COMPONENT_KEY));
        assertThat(captor.getValue().isStartEvent()).isTrue();
    }

    @Test
    void prepareOnStartForm_propagatesADeniedStart() {
        when(startEventAuthorization.require(any(), any()))
                .thenThrow(new AccessDeniedException("denied"));

        assertThatThrownBy(() -> resource.prepareOnStartForm(
                new EpistolaComposerResource.StartPrepareRequest("correspondentie-ad-hoc", "doc-1", "besluit", COMPONENT_KEY)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(letterComposerService);
    }

    @Test
    void prepareOnStartForm_returns404ForAnUnknownProcessOrCase() {
        when(startEventAuthorization.require(any(), any()))
                .thenThrow(new StartEventAuthorization.NotFoundException("gone"));

        assertThat(resource.prepareOnStartForm(new EpistolaComposerResource.StartPrepareRequest(
                "correspondentie-ad-hoc", "doc-1", "besluit", COMPONENT_KEY)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void prepareOnStartForm_rejectsAMissingProcessOrTemplate() {
        assertThat(resource.prepareOnStartForm(new EpistolaComposerResource.StartPrepareRequest(
                null, "doc-1", "besluit", COMPONENT_KEY)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resource.prepareOnStartForm(new EpistolaComposerResource.StartPrepareRequest(
                "correspondentie-ad-hoc", "doc-1", " ", COMPONENT_KEY)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(letterComposerService);
    }

    @Test
    void previewOnStartForm_servesThePdfInline() {
        when(startEventAuthorization.require("correspondentie-ad-hoc", null))
                .thenReturn(new StartEventAuthorization.StartContext("process:2:def", null, null));
        when(letterComposerService.preview(any(), eq("besluit"), any()))
                .thenReturn(new ByteArrayInputStream("%PDF".getBytes()));

        var response = resource.previewOnStartForm(new EpistolaComposerResource.StartPreviewRequest(
                "correspondentie-ad-hoc", null, "besluit", COMPONENT_KEY, Map.of("naam", "Jansen")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
    }
}
