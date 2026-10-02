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
import app.epistola.valtimo.service.EpistolaApiException;
import app.epistola.valtimo.service.TemplateDataFindings;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
        when(letterComposerService.prepare(any(), any(), eq("besluit")))
                .thenReturn(new PreparedLetter("besluit", "Besluit", "gemeente",
                        Map.of("naam", "Jansen"), new ObjectMapper().createObjectNode(), true));

        var response = resource.prepare(new EpistolaComposerResource.PrepareRequest(TASK_ID, null, "besluit", COMPONENT_KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<ComposerContext> captor = ArgumentCaptor.forClass(ComposerContext.class);
        verify(letterComposerService).prepare(captor.capture(), any(), eq("besluit"));
        assertThat(captor.getValue()).isEqualTo(
                new ComposerContext("process:1:abc", "choose-letter", "pi-1", "doc-1", COMPONENT_KEY));
    }

    @Test
    void prepare_requiresTaskViewPermission() {
        doThrow(new AccessDeniedException("denied"))
                .when(authorizationService).requirePermission(any(AuthorizationRequest.class));

        assertThatThrownBy(() -> resource.prepare(
                new EpistolaComposerResource.PrepareRequest(TASK_ID, null, "besluit", COMPONENT_KEY)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(letterComposerService);
    }

    @Test
    void prepare_rejectsAMissingTaskOrTemplate() {
        assertThat(resource.prepare(new EpistolaComposerResource.PrepareRequest(null, null, "besluit", COMPONENT_KEY))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resource.prepare(new EpistolaComposerResource.PrepareRequest(TASK_ID, null, " ", COMPONENT_KEY))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(letterComposerService);
    }

    @Test
    void prepare_returns404ForAnUnknownTask() {
        when(operatonTaskService.findTaskById("gone")).thenThrow(new TaskNotFoundException("gone"));

        assertThat(resource.prepare(new EpistolaComposerResource.PrepareRequest("gone", null, "besluit", COMPONENT_KEY))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void prepare_reportsAFormWithoutAComposerAs404() {
        when(letterComposerService.prepare(any(), any(), any()))
                .thenThrow(new ComposerException(ComposerException.Reason.NO_COMPOSER, "none here"));

        assertThat(resource.prepare(new EpistolaComposerResource.PrepareRequest(TASK_ID, null, "besluit", COMPONENT_KEY))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void preview_refusesATemplateTheFormDoesNotOfferWith400() {
        when(letterComposerService.preview(any(), any(), eq("geheime-brief"), any()))
                .thenThrow(new ComposerException(
                        ComposerException.Reason.TEMPLATE_NOT_OFFERED, "not offered"));

        var response = resource.preview(new EpistolaComposerResource.ComposerPreviewRequest(
                TASK_ID, null, "geheime-brief", COMPONENT_KEY, Map.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void preview_reportsARefusedRenderAs422() {
        when(letterComposerService.preview(any(), any(), any(), any()))
                .thenThrow(new ComposerException(ComposerException.Reason.RENDER_FAILED, "nope"));

        var response = resource.preview(new EpistolaComposerResource.ComposerPreviewRequest(
                TASK_ID, null, "besluit", COMPONENT_KEY, Map.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void preview_namesTheFieldsEpistolaRefused() {
        // The half the browser cannot do: most of a letter's data comes from the baseline mapping,
        // which it neither computed nor holds the contract for, so a refusal over one of those
        // fields only exists here. Each entry is a pointer the generated input can be matched to.
        var problem = new EpistolaApiException(
                "Template data invalid",
                new RuntimeException("downstream"),
                400,
                "https://epistola.app/errors/template-data-invalid",
                Map.of(
                        "invalidFields", List.of(Map.of(
                                "path", "/customer/email",
                                "keyword", "format",
                                "message", "must be a valid email address")),
                        "missingFields", List.of(
                                Map.of("path", "/invoiceNumber", "required", true),
                                Map.of("path", "/customer/phone", "required", false))));
        when(letterComposerService.preview(any(), any(), any(), any()))
                .thenThrow(new ComposerException(
                        ComposerException.Reason.RENDER_FAILED, "Epistola refused it", problem));

        var response = resource.preview(new EpistolaComposerResource.ComposerPreviewRequest(
                TASK_ID, null, "besluit", COMPONENT_KEY, Map.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        var body = (Map<String, Object>) response.getBody();
        // The single message stays, so a caller that only knows how to show one still shows one.
        assertThat(body).containsEntry("error", "Epistola refused it");

        var fields = (List<TemplateDataFindings>) body.get("fields");
        // The absent optional field is not reported: complaining about something nobody requires
        // would send the employee looking for a field the letter does not need.
        assertThat(fields).containsExactly(
                new TemplateDataFindings("/customer/email", "format", "must be a valid email address"),
                new TemplateDataFindings("/invoiceNumber", "required", null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void preview_saysNothingFieldByFieldWhenEpistolaDidNot() {
        // A render can fail for reasons that have nothing to do with the data, and a server older
        // than contract 1.4.0 says nothing field by field. The key is left out rather than sent
        // empty, so its absence is unambiguous to the browser.
        when(letterComposerService.preview(any(), any(), any(), any()))
                .thenThrow(new ComposerException(ComposerException.Reason.RENDER_FAILED, "nope"));

        var response = resource.preview(new EpistolaComposerResource.ComposerPreviewRequest(
                TASK_ID, null, "besluit", COMPONENT_KEY, Map.of()));

        assertThat((Map<String, Object>) response.getBody()).doesNotContainKey("fields");
    }

    @Test
    void preview_servesThePdfInline() {
        when(letterComposerService.preview(any(), any(), eq("besluit"), any()))
                .thenReturn(new ByteArrayInputStream("%PDF".getBytes()));

        var response = resource.preview(new EpistolaComposerResource.ComposerPreviewRequest(
                TASK_ID, null, "besluit", COMPONENT_KEY, Map.of("naam", "Jansen")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(response.getHeaders().getContentDisposition().isInline()).isTrue();
    }

    @Test
    void prepareOnStartForm_composesAgainstTheCaseItIsAuthorizedFor() {
        when(startEventAuthorization.require("correspondentie-ad-hoc", "doc-1"))
                .thenReturn(new StartEventAuthorization.StartContext("process:2:def", "process", "doc-1", null));
        when(letterComposerService.prepare(any(), any(), eq("besluit")))
                .thenReturn(new PreparedLetter("besluit", "Besluit", "gemeente",
                        Map.of(), new ObjectMapper().createObjectNode(), true));

        var response = resource.prepareOnStartForm(new EpistolaComposerResource.StartPrepareRequest(
                "correspondentie-ad-hoc", "doc-1", null, "besluit", COMPONENT_KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<ComposerContext> captor = ArgumentCaptor.forClass(ComposerContext.class);
        verify(letterComposerService).prepare(captor.capture(), any(), eq("besluit"));
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
                new EpistolaComposerResource.StartPrepareRequest("correspondentie-ad-hoc", "doc-1", null, "besluit", COMPONENT_KEY)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(letterComposerService);
    }

    @Test
    void prepareOnStartForm_returns404ForAnUnknownProcessOrCase() {
        when(startEventAuthorization.require(any(), any()))
                .thenThrow(new StartEventAuthorization.NotFoundException("gone"));

        assertThat(resource.prepareOnStartForm(new EpistolaComposerResource.StartPrepareRequest(
                "correspondentie-ad-hoc", "doc-1", null, "besluit", COMPONENT_KEY)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void prepareOnStartForm_rejectsAMissingTemplate() {
        // The process is no longer required of the caller — only the template is.
        assertThat(resource.prepareOnStartForm(new EpistolaComposerResource.StartPrepareRequest(
                "correspondentie-ad-hoc", "doc-1", null, " ", COMPONENT_KEY)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(letterComposerService);
    }

    @Test
    void prepareOnStartForm_findsTheProcessWhenTheAuthorNamedNone() {
        // The author should not have to name a process a start form already belongs to.
        // Discovery is narrowed to the case the dossier belongs to, so the document has to name it.
        var definitionId = mock(com.ritense.document.domain.impl.JsonSchemaDocumentDefinitionId.class);
        when(definitionId.name()).thenReturn("correspondentie");
        var document = mock(com.ritense.document.domain.impl.JsonSchemaDocument.class);
        when(document.definitionId()).thenReturn(definitionId);
        when(startEventAuthorization.findDocument("doc-1")).thenReturn(document);
        when(letterComposerService.startEventDefinitionsOffering(
                eq("correspondentie"), eq(COMPONENT_KEY), isNull(), eq("besluit")))
                .thenReturn(java.util.List.of("ad-hoc:1:a"));
        when(startEventAuthorization.permits("ad-hoc:1:a", document)).thenReturn(true);
        when(startEventAuthorization.requireById("ad-hoc:1:a", "doc-1"))
                .thenReturn(new StartEventAuthorization.StartContext("ad-hoc:1:a", "ad-hoc", "doc-1", document));
        when(letterComposerService.prepare(any(), any(), eq("besluit")))
                .thenReturn(new PreparedLetter("besluit", "Besluit", "gemeente", Map.of(), null, true));

        var response = resource.prepareOnStartForm(new EpistolaComposerResource.StartPrepareRequest(
                null, "doc-1", null, "besluit", COMPONENT_KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<ComposerContext> context = ArgumentCaptor.forClass(ComposerContext.class);
        verify(letterComposerService).prepare(context.capture(), any(), eq("besluit"));
        assertThat(context.getValue().processDefinitionId()).isEqualTo("ad-hoc:1:a");
        assertThat(context.getValue().isStartEvent()).isTrue();
        // Discovery does not replace the gates: the survivor still goes through them.
        verify(startEventAuthorization).requireById("ad-hoc:1:a", "doc-1");
    }

    @Test
    void prepareOnStartForm_refusesWhenNoStartFormOffersTheTemplate() {
        when(letterComposerService.startEventDefinitionsOffering(any(), eq(COMPONENT_KEY), isNull(), eq("besluit")))
                .thenReturn(java.util.List.of());

        var response = resource.prepareOnStartForm(new EpistolaComposerResource.StartPrepareRequest(
                null, null, null, "besluit", COMPONENT_KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(letterComposerService, org.mockito.Mockito.never()).prepare(any(), any(), any());
    }

    @Test
    void prepareOnStartForm_skipsAProcessThisCallerMayNotStart() {
        // Narrowing happens before anything is reported, so a caller never learns about — or is
        // blocked by — a process they have no part in.
        when(letterComposerService.startEventDefinitionsOffering(any(), eq(COMPONENT_KEY), isNull(), eq("besluit")))
                .thenReturn(java.util.List.of("theirs:1:a", "mine:1:b"));
        when(startEventAuthorization.permits("theirs:1:a", null)).thenReturn(false);
        when(startEventAuthorization.permits("mine:1:b", null)).thenReturn(true);
        when(startEventAuthorization.requireById("mine:1:b", null))
                .thenReturn(new StartEventAuthorization.StartContext("mine:1:b", "mine", null, null));
        when(letterComposerService.prepare(any(), any(), eq("besluit")))
                .thenReturn(new PreparedLetter("besluit", "Besluit", "gemeente", Map.of(), null, true));

        var response = resource.prepareOnStartForm(new EpistolaComposerResource.StartPrepareRequest(
                null, null, null, "besluit", COMPONENT_KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(startEventAuthorization).requireById("mine:1:b", null);
    }

    @Test
    void prepareOnStartForm_asksTheAuthorToChooseWhenTwoProcessesOfferIt() {
        when(letterComposerService.startEventDefinitionsOffering(any(), eq(COMPONENT_KEY), isNull(), eq("besluit")))
                .thenReturn(java.util.List.of("one:1:a", "two:1:b"));
        when(startEventAuthorization.permits(any(), any())).thenReturn(true);

        var response = resource.prepareOnStartForm(new EpistolaComposerResource.StartPrepareRequest(
                null, null, null, "besluit", COMPONENT_KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().toString()).contains("Name the process");
        verify(letterComposerService, org.mockito.Mockito.never()).prepare(any(), any(), any());
    }

    @Test
    void prepareOnStartForm_stillHonoursAnAuthoredProcessKey() {
        when(startEventAuthorization.require("correspondentie-ad-hoc", "doc-1"))
                .thenReturn(new StartEventAuthorization.StartContext("process:2:def", "process", "doc-1", null));
        when(letterComposerService.prepare(any(), any(), eq("besluit")))
                .thenReturn(new PreparedLetter("besluit", "Besluit", "gemeente", Map.of(), null, true));

        var response = resource.prepareOnStartForm(new EpistolaComposerResource.StartPrepareRequest(
                "correspondentie-ad-hoc", "doc-1", null, "besluit", COMPONENT_KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(startEventAuthorization).require("correspondentie-ad-hoc", "doc-1");
        verify(letterComposerService, org.mockito.Mockito.never())
                .startEventDefinitionsOffering(any(), any(), any(), any());
    }

    @Test
    void previewOnStartForm_servesThePdfInline() {
        when(startEventAuthorization.require("correspondentie-ad-hoc", null))
                .thenReturn(new StartEventAuthorization.StartContext("process:2:def", "process", null, null));
        when(letterComposerService.preview(any(), any(), eq("besluit"), any()))
                .thenReturn(new ByteArrayInputStream("%PDF".getBytes()));

        var response = resource.previewOnStartForm(new EpistolaComposerResource.StartPreviewRequest(
                "correspondentie-ad-hoc", null, null, "besluit", COMPONENT_KEY, Map.of("naam", "Jansen")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
    }

    @Test
    void prepareOnStartForm_handsBackTheProcessItFound() {
        // So the preview, which fires on every edit, can name it instead of making the backend
        // read every deployed definition's process links again.
        when(letterComposerService.startEventDefinitionsOffering(any(), eq(COMPONENT_KEY), isNull(), eq("besluit")))
                .thenReturn(java.util.List.of("ad-hoc:1:a"));
        when(startEventAuthorization.permits("ad-hoc:1:a", null)).thenReturn(true);
        when(startEventAuthorization.requireById("ad-hoc:1:a", null)).thenReturn(
                new StartEventAuthorization.StartContext("ad-hoc:1:a", "correspondentie-ad-hoc", null, null));
        when(letterComposerService.prepare(any(), any(), eq("besluit")))
                .thenReturn(new PreparedLetter("besluit", "Besluit", "gemeente", Map.of(), null, true));

        var response = resource.prepareOnStartForm(new EpistolaComposerResource.StartPrepareRequest(
                null, null, null, "besluit", COMPONENT_KEY));

        assertThat(((EpistolaComposerResource.PrepareResponse) response.getBody()).processDefinitionKey())
                .isEqualTo("correspondentie-ad-hoc");
    }

    @Test
    void previewOnStartForm_namesTheProcessRatherThanSearchingAgain() {
        when(startEventAuthorization.require("correspondentie-ad-hoc", null))
                .thenReturn(new StartEventAuthorization.StartContext(
                        "ad-hoc:1:a", "correspondentie-ad-hoc", null, null));
        when(letterComposerService.preview(any(), any(), eq("besluit"), any()))
                .thenReturn(new ByteArrayInputStream("%PDF".getBytes()));

        resource.previewOnStartForm(new EpistolaComposerResource.StartPreviewRequest(
                "correspondentie-ad-hoc", null, null, "besluit", COMPONENT_KEY, Map.of()));

        verify(startEventAuthorization).require("correspondentie-ad-hoc", null);
        verify(letterComposerService, org.mockito.Mockito.never())
                .startEventDefinitionsOffering(any(), any(), any(), any());
    }

    @Test
    void prepare_onATaskFormNamesNoProcess() {
        when(letterComposerService.prepare(any(), any(), eq("besluit")))
                .thenReturn(new PreparedLetter("besluit", "Besluit", "gemeente", Map.of(), null, true));

        var response = resource.prepare(
                new EpistolaComposerResource.PrepareRequest(TASK_ID, null, "besluit", COMPONENT_KEY));

        assertThat(((EpistolaComposerResource.PrepareResponse) response.getBody()).processDefinitionKey())
                .isNull();
    }
}
