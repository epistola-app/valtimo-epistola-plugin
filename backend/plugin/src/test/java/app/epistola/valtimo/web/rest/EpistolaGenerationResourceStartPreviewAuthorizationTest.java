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
import app.epistola.valtimo.web.rest.dto.StartPreviewRequest;
import com.ritense.authorization.AuthorizationService;
import com.ritense.authorization.request.AuthorizationRequest;
import com.ritense.authorization.request.EntityAuthorizationRequest;
import com.ritense.authorization.request.RelatedEntityAuthorizationRequest;
import com.ritense.document.domain.impl.JsonSchemaDocument;
import com.ritense.document.service.JsonSchemaDocumentActionProvider;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.operaton.authorization.OperatonExecutionActionProvider;
import com.ritense.valtimo.operaton.domain.OperatonExecution;
import com.ritense.valtimo.operaton.domain.OperatonProcessDefinition;
import com.ritense.valtimo.service.OperatonTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.repository.ProcessDefinitionQuery;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;


import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authorization contract for {@code POST /preview/start}.
 *
 * <p>A start event has no task, so this endpoint cannot use {@code OperatonTask:VIEW}. It instead
 * mirrors what Valtimo checks before serving the start form the calling component sits on —
 * {@code OperatonExecution:CREATE} on the process definition — and, when the request names a case
 * document, additionally requires {@code JsonSchemaDocument:VIEW} on it.
 *
 * <p>These tests assert: the execution permission is required and carries the <i>resolved</i>
 * definition id; an unknown key or non-generate activity is a 404; a denied permission propagates
 * as 403; and — the regression test for the {@code 8972c16} bypass class — that document VIEW is
 * required <b>independently</b> of the execution permission.
 */
class EpistolaGenerationResourceStartPreviewAuthorizationTest {

    private static final String PROCESS_DEFINITION_KEY = "permit-confirmation";
    private static final String PROCESS_DEFINITION_ID = "permit-confirmation:1:abc-def";
    private static final String SOURCE_ACTIVITY_ID = "generate-confirmation";
    private static final String DOCUMENT_ID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

    private AuthorizationService authorizationService;
    private app.epistola.valtimo.service.preview.PreviewService previewService;
    private com.ritense.document.service.DocumentService documentService;
    private RepositoryService repositoryService;
    private EpistolaGenerationResource resource;

    @BeforeEach
    void setUp() {
        var pluginService = mock(PluginService.class);
        var epistolaService = mock(EpistolaService.class);
        previewService = mock(app.epistola.valtimo.service.preview.PreviewService.class);
        var retryFormService = mock(app.epistola.valtimo.service.form.RetryFormService.class);
        var jsonataMappingService = mock(app.epistola.valtimo.mapping.JsonataMappingService.class);
        documentService = mock(com.ritense.document.service.DocumentService.class);
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        authorizationService = mock(AuthorizationService.class);
        var operatonTaskService = mock(OperatonTaskService.class);
        repositoryService = mock(RepositoryService.class);

        givenAuthorizationResourceTypesAreSupported();
        givenDeployedProcessDefinition(PROCESS_DEFINITION_ID);

        resource = new EpistolaGenerationResource(pluginService, epistolaService,
                previewService, retryFormService, jsonataMappingService,
                documentService, objectMapper, authorizationService, operatonTaskService,
                mock(org.operaton.bpm.engine.RuntimeService.class), repositoryService);
    }

    /**
     * {@code RelatedEntityAuthorizationRequest}'s constructor calls
     * {@code AuthorizationSupportedHelper.checkSupported}, which asks the Spring context for an
     * {@code AuthorizationSpecificationFactory} bean for the resource type and otherwise throws
     * {@code ResourceNotSupportedException}. That static is normally populated at boot, so a plain
     * unit test has to stand it up — the helper is a singleton with a public setter.
     *
     * <p>This is inherent to the request type, not to our code: {@code EntityAuthorizationRequest}
     * (used by the task-bound endpoints) performs no such check, which is why the sibling test class
     * needs none of this.
     */
    private void givenAuthorizationResourceTypesAreSupported() {
        var applicationContext = mock(org.springframework.context.ApplicationContext.class);
        when(applicationContext.getBeanNamesForType(any(org.springframework.core.ResolvableType.class)))
                .thenReturn(new String[]{"stubAuthorizationSpecificationFactory"});
        com.ritense.authorization.AuthorizationSupportedHelper.INSTANCE
                .setApplicationContext(applicationContext);
    }

    /** Stubs the key -> latest version -> id lookup; pass null to simulate an undeployed key. */
    private void givenDeployedProcessDefinition(String processDefinitionId) {
        var query = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
        when(query.processDefinitionKey(any())).thenReturn(query);
        when(query.latestVersion()).thenReturn(query);
        if (processDefinitionId == null) {
            when(query.singleResult()).thenReturn(null);
        } else {
            var definition = mock(ProcessDefinition.class);
            when(definition.getId()).thenReturn(processDefinitionId);
            when(query.singleResult()).thenReturn(definition);
        }
    }

    private StartPreviewRequest validRequest() {
        return new StartPreviewRequest(PROCESS_DEFINITION_KEY, SOURCE_ACTIVITY_ID, null, null);
    }

    private StartPreviewRequest requestWithDocument() {
        return new StartPreviewRequest(PROCESS_DEFINITION_KEY, SOURCE_ACTIVITY_ID, DOCUMENT_ID, null);
    }

    private void givenPreviewRenders() {
        when(previewService.generateStartPreview(any(), any(), any(), any()))
                .thenReturn(new java.io.ByteArrayInputStream(new byte[]{0x25, 0x50, 0x44, 0x46})); // %PDF
    }

    // ---- primary gate: may this caller start this process? ----

    @Test
    void startPreview_requiresExecutionCreateOnTheResolvedProcessDefinition() {
        givenPreviewRenders();

        var response = resource.previewStartDocument(validRequest());

        var captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorizationService).requirePermission(captor.capture());
        var request = (RelatedEntityAuthorizationRequest<?>) captor.getValue();

        assertThat(request.getResourceType()).isEqualTo(OperatonExecution.class);
        assertThat(request.getAction()).isEqualTo(OperatonExecutionActionProvider.CREATE);
        assertThat(request.getRelatedResourceType()).isEqualTo(OperatonProcessDefinition.class);
        // The RESOLVED id, never the caller's key — a future refactor must not authorize against
        // one definition and render another.
        assertThat(request.getRelatedResourceId()).isEqualTo(PROCESS_DEFINITION_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void startPreview_passesResolvedDefinitionIdAndNoProcessInstanceToTheService() {
        givenPreviewRenders();

        resource.previewStartDocument(validRequest());

        verify(previewService).generateStartPreview(
                eq(PROCESS_DEFINITION_ID), isNull(), eq(SOURCE_ACTIVITY_ID), isNull());
    }

    @Test
    void startPreview_propagatesDeniedExecutionPermissionAs403() {
        doThrow(new AccessDeniedException("denied"))
                .when(authorizationService).requirePermission(any());

        assertThatThrownBy(() -> resource.previewStartDocument(validRequest()))
                .isInstanceOf(AccessDeniedException.class);

        verify(previewService, never()).generateStartPreview(any(), any(), any(), any());
    }

    // ---- secondary gate: CREATE on a process must not confer READ on a case ----

    @Test
    void startPreview_withDocumentId_alsoRequiresDocumentView() {
        var document = mock(JsonSchemaDocument.class);
        org.mockito.Mockito.doReturn(java.util.Optional.of(document)).when(documentService).findBy(any());
        givenPreviewRenders();

        resource.previewStartDocument(requestWithDocument());

        var captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorizationService, org.mockito.Mockito.times(2)).requirePermission(captor.capture());

        var documentRequest = captor.getAllValues().stream()
                .filter(EntityAuthorizationRequest.class::isInstance)
                .map(EntityAuthorizationRequest.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no document permission was checked"));

        assertThat(documentRequest.getResourceType()).isEqualTo(JsonSchemaDocument.class);
        assertThat(documentRequest.getAction()).isEqualTo(JsonSchemaDocumentActionProvider.VIEW);
    }

    /**
     * Regression test for the bypass class fixed in {@code 8972c16}: a caller who may start a
     * process must not thereby be able to read an arbitrary case's content through the mapping.
     * Document VIEW is checked independently, so denying it must block the render even when the
     * execution permission was granted.
     */
    @Test
    void startPreview_withDocumentId_deniedDocumentViewBlocksRenderEvenWhenExecutionCreateGranted() {
        var document = mock(JsonSchemaDocument.class);
        org.mockito.Mockito.doReturn(java.util.Optional.of(document)).when(documentService).findBy(any());
        doThrow(new AccessDeniedException("no view on this case"))
                .when(authorizationService)
                .requirePermission(any(EntityAuthorizationRequest.class));

        assertThatThrownBy(() -> resource.previewStartDocument(requestWithDocument()))
                .isInstanceOf(AccessDeniedException.class);

        verify(previewService, never()).generateStartPreview(any(), any(), any(), any());
    }

    @Test
    void startPreview_withoutDocumentId_neverTouchesTheDocumentService() {
        givenPreviewRenders();

        resource.previewStartDocument(validRequest());

        org.mockito.Mockito.verifyNoInteractions(documentService);
        // Exactly one permission check: the execution gate. No document, nothing else to authorize.
        verify(authorizationService, org.mockito.Mockito.times(1)).requirePermission(any());
    }

    @Test
    void startPreview_returns404WhenDocumentIdIsUnknown() {
        org.mockito.Mockito.doReturn(java.util.Optional.empty()).when(documentService).findBy(any());

        var response = resource.previewStartDocument(requestWithDocument());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(authorizationService, never()).requirePermission(any());
    }

    @Test
    void startPreview_returns404WhenDocumentIdIsNotAUuid() {
        var response = resource.previewStartDocument(
                new StartPreviewRequest(PROCESS_DEFINITION_KEY, SOURCE_ACTIVITY_ID, "not-a-uuid", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- shape ----

    @Test
    void startPreview_returns400WhenProcessDefinitionKeyIsBlank() {
        var response = resource.previewStartDocument(
                new StartPreviewRequest("  ", SOURCE_ACTIVITY_ID, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(authorizationService, never()).requirePermission(any());
    }

    @Test
    void startPreview_returns400WhenSourceActivityIdIsBlank() {
        var response = resource.previewStartDocument(
                new StartPreviewRequest(PROCESS_DEFINITION_KEY, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(authorizationService, never()).requirePermission(any());
    }

    @Test
    void startPreview_returns404WhenProcessDefinitionIsNotDeployed() {
        givenDeployedProcessDefinition(null);

        var response = resource.previewStartDocument(validRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Nothing to authorize against — the check must not be attempted on a null definition.
        verify(authorizationService, never()).requirePermission(any());
    }

    // ---- error mapping parity with /preview ----

    @Test
    void startPreview_mapsLinkNotFoundTo404() {
        when(previewService.generateStartPreview(any(), any(), any(), any()))
                .thenThrow(new app.epistola.valtimo.service.preview.PreviewService.PreviewException(
                        app.epistola.valtimo.service.preview.PreviewService.PreviewException.Reason.LINK_NOT_FOUND,
                        "no generate-document link for activity"));

        var response = resource.previewStartDocument(validRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void startPreview_mapsRenderFailedTo422() {
        when(previewService.generateStartPreview(any(), any(), any(), any()))
                .thenThrow(new app.epistola.valtimo.service.preview.PreviewService.PreviewException(
                        app.epistola.valtimo.service.preview.PreviewService.PreviewException.Reason.RENDER_FAILED,
                        "template requires a field the start form does not supply"));

        var response = resource.previewStartDocument(validRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) response.getBody();
        assertThat(body).containsKeys("error", "details");
    }

    @Test
    void startPreview_mapsMissingTemplateTo400() {
        when(previewService.generateStartPreview(any(), any(), any(), any()))
                .thenThrow(new app.epistola.valtimo.service.preview.PreviewService.PreviewException(
                        app.epistola.valtimo.service.preview.PreviewService.PreviewException.Reason.MISSING_TEMPLATE,
                        "no templateId"));

        var response = resource.previewStartDocument(validRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- the wire cannot carry a task ----

    /**
     * A smuggled {@code taskId} is inert because the record has nowhere to put it — not because it
     * is rejected. Do not "improve" this into an exception assertion: it would pass here (a bare
     * ObjectMapper enables FAIL_ON_UNKNOWN_PROPERTIES) and be false in the application, where
     * Spring Boot disables it and the field is silently dropped. {@code StartFormPreviewE2ETest}
     * pins the real-application behaviour.
     */
    @Test
    void startPreviewRequest_hasNowhereToPutATaskOrProcessInstance() {
        var componentNames = java.util.Arrays.stream(StartPreviewRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(componentNames).doesNotContain("taskId", "processInstanceId");
    }

    @Test
    void startPreviewRequest_acceptsTheDocumentedShape() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String body = """
                {"processDefinitionKey":"permit-confirmation",
                 "sourceActivityId":"generate-confirmation",
                 "documentId":"3f2504e0-4f89-11d3-9a0c-0305e82c3301",
                 "inputOverrides":{"doc":{"applicant":{"firstName":"Jan"}}}}
                """;

        var parsed = mapper.readValue(body, StartPreviewRequest.class);

        assertThat(parsed.processDefinitionKey()).isEqualTo("permit-confirmation");
        assertThat(parsed.documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(parsed.inputOverrides()).containsKey("doc");
        assertThat(parsed.sourceActivityId()).isEqualTo(SOURCE_ACTIVITY_ID);
    }
}
