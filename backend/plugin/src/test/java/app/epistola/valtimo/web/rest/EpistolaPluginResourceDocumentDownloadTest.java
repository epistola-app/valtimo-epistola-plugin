package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.service.EpistolaService;
import com.ritense.authorization.AuthorizationService;
import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import com.ritense.valtimo.operaton.domain.OperatonExecution;
import com.ritense.valtimo.operaton.domain.OperatonTask;
import com.ritense.valtimo.security.exceptions.TaskNotFoundException;
import com.ritense.valtimo.service.OperatonTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RuntimeService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the post-redesign download endpoint:
 *
 * <ul>
 *   <li>Authorization binds the supplied taskId + caseDocumentId to the task's
 *       process and case (via {@code requireTaskBoundTo}).</li>
 *   <li>The Epistola PDF id and tenant id are read from named process variables
 *       on the task's process instance — not from the request body — making the
 *       endpoint forge-proof.</li>
 *   <li>Disposition switches between {@code attachment} (default) and {@code inline}.</li>
 * </ul>
 */
class EpistolaPluginResourceDocumentDownloadTest {

    private static final String TASK_ID = "task-123";
    private static final String PROCESS_INSTANCE_ID = "process-instance-1";
    private static final String CASE_DOCUMENT_ID = "case-doc-1";
    private static final String DOC_VAR = "epistolaResult";
    private static final String TENANT_ID_VAR = "epistolaTenantId";

    private PluginService pluginService;
    private EpistolaService epistolaService;
    private AuthorizationService authorizationService;
    private OperatonTaskService operatonTaskService;
    private RuntimeService runtimeService;
    private OperatonTask task;
    private EpistolaGenerationResource resource;

    @BeforeEach
    void setUp() {
        pluginService = mock(PluginService.class);
        epistolaService = mock(EpistolaService.class);
        authorizationService = mock(AuthorizationService.class);
        operatonTaskService = mock(OperatonTaskService.class);
        runtimeService = mock(RuntimeService.class);
        var previewService = mock(app.epistola.valtimo.service.preview.PreviewService.class);
        var retryFormService = mock(app.epistola.valtimo.service.form.RetryFormService.class);
        var jsonataMappingService = mock(app.epistola.valtimo.mapping.JsonataMappingService.class);
        var documentService = mock(com.ritense.document.service.DocumentService.class);
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

        task = mock(OperatonTask.class);
        OperatonExecution processInstance = mock(OperatonExecution.class);
        when(task.getProcessInstanceId()).thenReturn(PROCESS_INSTANCE_ID);
        when(task.getProcessInstance()).thenReturn(processInstance);
        when(processInstance.getBusinessKey()).thenReturn(CASE_DOCUMENT_ID);
        when(operatonTaskService.findTaskById(TASK_ID)).thenReturn(task);

        resource = new EpistolaGenerationResource(pluginService, epistolaService,
                previewService, retryFormService, jsonataMappingService,
                documentService, objectMapper, authorizationService, operatonTaskService,
                runtimeService);
    }

    @Test
    void downloadDocument_streamsPdfWhenVariablesResolveAndTaskIsBound() {
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, DOC_VAR)).thenReturn("doc-123");
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, TENANT_ID_VAR)).thenReturn("tenant-a");
        EpistolaPlugin plugin = mockPlugin("https://api.epistola.app", "api-key", "tenant-a");
        registerPlugin(plugin);
        byte[] pdfContent = new byte[]{0x25, 0x50, 0x44, 0x46};
        when(epistolaService.downloadDocument("https://api.epistola.app", "api-key", "tenant-a", "doc-123"))
                .thenReturn(pdfContent);

        ResponseEntity<byte[]> response = resource.downloadDocument(
                TASK_ID, CASE_DOCUMENT_ID, DOC_VAR, TENANT_ID_VAR, "bevestigingsbrief.pdf", "attachment");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(pdfContent);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getContentDisposition())
                .isEqualTo(ContentDisposition.attachment().filename("bevestigingsbrief.pdf").build());
    }

    @Test
    void downloadDocument_setsInlineDispositionWhenRequested() {
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, DOC_VAR)).thenReturn("doc-123");
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, TENANT_ID_VAR)).thenReturn("tenant-a");
        registerPlugin(mockPlugin("https://api.epistola.app", "api-key", "tenant-a"));
        when(epistolaService.downloadDocument(any(), any(), any(), any())).thenReturn(new byte[]{0x25});

        ResponseEntity<byte[]> response = resource.downloadDocument(
                TASK_ID, CASE_DOCUMENT_ID, DOC_VAR, TENANT_ID_VAR, "preview.pdf", "inline");

        assertThat(response.getHeaders().getContentDisposition())
                .isEqualTo(ContentDisposition.inline().filename("preview.pdf").build());
    }

    @Test
    void downloadDocument_resolvesCustomVariableNames() {
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, "myDoc")).thenReturn("doc-456");
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, "myTenant")).thenReturn("tenant-b");
        registerPlugin(mockPlugin("https://b.epistola.app", "key-b", "tenant-b"));
        byte[] pdf = new byte[]{0x25};
        when(epistolaService.downloadDocument("https://b.epistola.app", "key-b", "tenant-b", "doc-456"))
                .thenReturn(pdf);

        ResponseEntity<byte[]> response = resource.downloadDocument(
                TASK_ID, CASE_DOCUMENT_ID, "myDoc", "myTenant", "out.pdf", "attachment");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(pdf);
    }

    @Test
    void downloadDocument_resolvesDocumentIdFromRichResultObject() {
        // The configured documentIdVariable points at a rich result object (the canonical
        // pattern after the resultProcessVariable shape change). The resolver digs out the
        // "documentId" key.
        java.util.Map<String, Object> richResult = new java.util.LinkedHashMap<>();
        richResult.put("requestId", "req-123");
        richResult.put("status", "COMPLETED");
        richResult.put("documentId", "doc-from-map");
        richResult.put("errorMessage", null);
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, "epistolaResult")).thenReturn(richResult);
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, TENANT_ID_VAR)).thenReturn("tenant-a");
        registerPlugin(mockPlugin("https://api.epistola.app", "api-key", "tenant-a"));
        byte[] pdf = new byte[]{0x25, 0x50, 0x44, 0x46};
        when(epistolaService.downloadDocument("https://api.epistola.app", "api-key", "tenant-a", "doc-from-map"))
                .thenReturn(pdf);

        ResponseEntity<byte[]> response = resource.downloadDocument(
                TASK_ID, CASE_DOCUMENT_ID, "epistolaResult", TENANT_ID_VAR, "out.pdf", "attachment");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(pdf);
    }

    @Test
    void downloadDocument_returns404WhenDocumentIdVariableIsNull() {
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, DOC_VAR)).thenReturn(null);
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, TENANT_ID_VAR)).thenReturn("tenant-a");

        ResponseEntity<byte[]> response = resource.downloadDocument(
                TASK_ID, CASE_DOCUMENT_ID, DOC_VAR, TENANT_ID_VAR, "out.pdf", "attachment");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void downloadDocument_returns404WhenTenantIdVariableIsNull() {
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, DOC_VAR)).thenReturn("doc-123");
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, TENANT_ID_VAR)).thenReturn(null);

        ResponseEntity<byte[]> response = resource.downloadDocument(
                TASK_ID, CASE_DOCUMENT_ID, DOC_VAR, TENANT_ID_VAR, "out.pdf", "attachment");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void downloadDocument_returns404WhenNoPluginConfigForResolvedTenant() {
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, DOC_VAR)).thenReturn("doc-123");
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, TENANT_ID_VAR)).thenReturn("ghost-tenant");
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                .thenReturn(Collections.emptyList());

        ResponseEntity<byte[]> response = resource.downloadDocument(
                TASK_ID, CASE_DOCUMENT_ID, DOC_VAR, TENANT_ID_VAR, "out.pdf", "attachment");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void downloadDocument_returns404WhenEpistolaReturns404ForResolvedId() {
        // Variables resolve and the plugin config matches, but Epistola itself says
        // it has no such document (stale id from a wiped/deleted upstream record).
        // The controller must translate the upstream 404 into a clean 404 — never
        // bubble up the HttpClientErrorException as a 500.
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, DOC_VAR)).thenReturn("stale-doc");
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, TENANT_ID_VAR)).thenReturn("tenant-a");
        registerPlugin(mockPlugin("https://api.epistola.app", "api-key", "tenant-a"));
        when(epistolaService.downloadDocument(any(), any(), any(), eq("stale-doc")))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null));

        ResponseEntity<byte[]> response = resource.downloadDocument(
                TASK_ID, CASE_DOCUMENT_ID, DOC_VAR, TENANT_ID_VAR, "out.pdf", "attachment");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void downloadDocument_returns403WhenCaseDocumentIdMismatchesBusinessKey() {
        assertThatThrownBy(() -> resource.downloadDocument(
                TASK_ID, "other-case-doc", DOC_VAR, TENANT_ID_VAR, "out.pdf", "attachment"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void downloadDocument_returns400WhenAnyRequiredParamMissing() {
        assertThat(resource.downloadDocument("", CASE_DOCUMENT_ID, DOC_VAR, TENANT_ID_VAR, "x", "attachment").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resource.downloadDocument(TASK_ID, "", DOC_VAR, TENANT_ID_VAR, "x", "attachment").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resource.downloadDocument(TASK_ID, CASE_DOCUMENT_ID, "", TENANT_ID_VAR, "x", "attachment").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resource.downloadDocument(TASK_ID, CASE_DOCUMENT_ID, DOC_VAR, "", "x", "attachment").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void downloadDocument_returns404WhenTaskNotFound() {
        when(operatonTaskService.findTaskById("missing")).thenThrow(new TaskNotFoundException("missing"));

        ResponseEntity<byte[]> response = resource.downloadDocument(
                "missing", CASE_DOCUMENT_ID, DOC_VAR, TENANT_ID_VAR, "out.pdf", "attachment");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void downloadDocument_propagatesAccessDeniedFromAuthorizationService() {
        doThrow(new AccessDeniedException("nope"))
                .when(authorizationService).requirePermission(any());

        assertThatThrownBy(() -> resource.downloadDocument(
                TASK_ID, CASE_DOCUMENT_ID, DOC_VAR, TENANT_ID_VAR, "out.pdf", "attachment"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void downloadDocument_selectsCorrectPluginAmongMultiple() {
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, DOC_VAR)).thenReturn("doc-456");
        when(runtimeService.getVariable(PROCESS_INSTANCE_ID, TENANT_ID_VAR)).thenReturn("tenant-b");

        EpistolaPlugin pluginA = mockPlugin("https://a.epistola.app", "key-a", "tenant-a");
        EpistolaPlugin pluginB = mockPlugin("https://b.epistola.app", "key-b", "tenant-b");
        PluginConfiguration configA = mock(PluginConfiguration.class);
        PluginConfiguration configB = mock(PluginConfiguration.class);
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                .thenReturn(List.of(configA, configB));
        when(pluginService.createInstance(configA)).thenReturn(pluginA);
        when(pluginService.createInstance(configB)).thenReturn(pluginB);

        byte[] pdf = new byte[]{0x25};
        when(epistolaService.downloadDocument("https://b.epistola.app", "key-b", "tenant-b", "doc-456")).thenReturn(pdf);

        ResponseEntity<byte[]> response = resource.downloadDocument(
                TASK_ID, CASE_DOCUMENT_ID, DOC_VAR, TENANT_ID_VAR, "out.pdf", "attachment");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(pdf);
    }

    private void registerPlugin(EpistolaPlugin plugin) {
        PluginConfiguration config = mock(PluginConfiguration.class);
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                .thenReturn(List.of(config));
        when(pluginService.createInstance(config)).thenReturn(plugin);
    }

    private EpistolaPlugin mockPlugin(String baseUrl, String apiKey, String tenantId) {
        EpistolaPlugin plugin = mock(EpistolaPlugin.class);
        when(plugin.getBaseUrl()).thenReturn(baseUrl);
        when(plugin.getApiKey()).thenReturn(apiKey);
        when(plugin.getTenantId()).thenReturn(tenantId);
        return plugin;
    }
}
