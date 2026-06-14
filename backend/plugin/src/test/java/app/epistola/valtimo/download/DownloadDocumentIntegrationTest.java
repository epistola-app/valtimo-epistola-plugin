package app.epistola.valtimo.download;

import app.epistola.valtimo.BaseIntegrationTest;
import app.epistola.valtimo.domain.DocumentStorageTarget;
import app.epistola.valtimo.service.download.DocumentStorageStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.repository.Deployment;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.variable.value.BytesValue;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Full Valtimo integration tests for the {@code download-document} {@code storageTarget} feature —
 * see {@code docs/adr/0001-download-document-content-storage.md}. Everything runs in the single
 * shared {@link BaseIntegrationTest} context (Testcontainers Postgres, the real Operaton engine, the
 * real plugin wiring; {@code EpistolaService} mocked in the base; result collector disabled in the
 * shared test config — so no per-class annotations fork the context cache).
 *
 * <p>Covers: the conditional strategy wiring; the real {@code download-document} action for both
 * targets (resolved via {@link PluginService}); and the BPMN {@code asyncAfter} catch-event flow with
 * task-variable serialization — the two bugs this work fixed (catch-event stall + the FileValue
 * serialization failure on task open).
 */
class DownloadDocumentIntegrationTest extends BaseIntegrationTest {

    private static final String PROCESS_KEY = "epistola-download-flow-test";
    private static final String MESSAGE = "EpistolaDocumentGenerated";
    private static final String DOCUMENT_VARIABLE = "epistolaResult";

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs_dl_test">
              <bpmn:message id="msg_docgen" name="EpistolaDocumentGenerated" />
              <bpmn:process id="epistola-download-flow-test" isExecutable="true" camunda:historyTimeToLive="P1D">
                <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait" />
                <bpmn:intermediateCatchEvent id="wait" name="Wait" camunda:asyncAfter="true">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                  <bpmn:messageEventDefinition id="med" messageRef="msg_docgen" />
                </bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="download" />
                <bpmn:serviceTask id="download" name="Download" camunda:class="app.epistola.valtimo.download.EpistolaTestStoreDelegate">
                  <bpmn:incoming>f2</bpmn:incoming>
                  <bpmn:outgoing>f3</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="f3" sourceRef="download" targetRef="end" />
                <bpmn:endEvent id="end"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    @Autowired
    private PluginService pluginService;
    @Autowired
    private List<DocumentStorageStrategy> storageStrategies;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private HistoryService historyService;
    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private ObjectMapper objectMapper;

    private String deploymentId;

    @BeforeEach
    void deploy() {
        Deployment deployment = repositoryService.createDeployment()
                .addString("epistola-download-flow-test.bpmn", BPMN)
                .deploy();
        deploymentId = deployment.getId();
    }

    @AfterEach
    void undeploy() {
        if (deploymentId != null) {
            repositoryService.deleteDeployment(deploymentId, true);
        }
    }

    @Test
    void bothStorageStrategiesAreWired() {
        assertThat(storageStrategies)
                .extracting(DocumentStorageStrategy::target)
                .contains(DocumentStorageTarget.TEMPORARY_RESOURCE, DocumentStorageTarget.PROCESS_VARIABLE);
    }

    @Test
    void downloadAction_temporaryResource_storesOnlyAResourceId() throws Exception {
        EpistolaPlugin plugin = resolvePlugin();
        when(epistolaService.downloadDocument(any(), any(), any(), eq("doc-1")))
                .thenReturn(new byte[]{0x25, 0x50, 0x44, 0x46});
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getVariable(DOCUMENT_VARIABLE)).thenReturn("doc-1");

        plugin.downloadDocument(execution, DOCUMENT_VARIABLE,
                DocumentStorageTarget.TEMPORARY_RESOURCE, "documentResourceId", null);

        var captor = forClass(Object.class);
        verify(execution).setVariable(eq("documentResourceId"), captor.capture());
        assertThat(captor.getValue())
                .as("temporary-resource target stores a small resource id, not the document")
                .isInstanceOf(String.class);
        assertThatCode(() -> objectMapper.writeValueAsString(captor.getValue())).doesNotThrowAnyException();
    }

    @Test
    void downloadAction_processVariable_storesInlineBytes() {
        EpistolaPlugin plugin = resolvePlugin();
        byte[] pdf = new byte[8192]; // Base64 would exceed varchar(4000)
        when(epistolaService.downloadDocument(any(), any(), any(), eq("doc-1"))).thenReturn(pdf);
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getVariable(DOCUMENT_VARIABLE)).thenReturn("doc-1");

        plugin.downloadDocument(execution, DOCUMENT_VARIABLE,
                DocumentStorageTarget.PROCESS_VARIABLE, null, "documentContent");

        var captor = forClass(Object.class);
        verify(execution).setVariable(eq("documentContent"), captor.capture());
        assertThat(captor.getValue())
                .as("process-variable target stores a byte variable, not a String")
                .isInstanceOf(BytesValue.class);
        assertThat(((BytesValue) captor.getValue()).getValue()).isEqualTo(pdf);
    }

    @Test
    void downloadAction_failsFastWhenOutputVariableForTargetIsMissing() {
        EpistolaPlugin plugin = resolvePlugin();
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getVariable(DOCUMENT_VARIABLE)).thenReturn("doc-1");

        // TEMPORARY_RESOURCE selected but no resourceIdVariable configured.
        assertThatThrownBy(() -> plugin.downloadDocument(execution, DOCUMENT_VARIABLE,
                DocumentStorageTarget.TEMPORARY_RESOURCE, "  ", "documentContent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output variable");
    }

    @Test
    void catchEventCompletesViaAsyncContinuation_andStoredVariableSerializes() {
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                PROCESS_KEY, Map.of(DOCUMENT_VARIABLE, "test-doc"));

        // (1) Parked at the message catch event.
        assertThat(messageSubscriptionExists(pi)).as("waits at the EpistolaDocumentGenerated catch event").isTrue();

        // (2) Correlate. With asyncAfter the catch event is consumed in this transaction and the
        // continuation (download → end) runs separately via the job executor — a downstream failure
        // can no longer roll the correlation back. Await the continuation to complete.
        runtimeService.createMessageCorrelation(MESSAGE).processInstanceId(pi.getId()).correlate();
        assertThat(messageSubscriptionExists(pi)).as("the message was consumed").isFalse();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).singleResult())
                        .as("the asyncAfter continuation completed").isNull());

        // (3) The download stored the document variable, and (4) it serializes cleanly via Jackson —
        // what the task-detail endpoint does on task open (the old FileValue produced an
        // unserializable ByteArrayInputStream).
        Object value = storedValue(pi);
        assertThat(value).isInstanceOf(byte[].class);
        assertThatCode(() -> objectMapper.writeValueAsString(value)).doesNotThrowAnyException();
    }

    private EpistolaPlugin resolvePlugin() {
        List<?> configurations = pluginService.findPluginConfigurations(EpistolaPlugin.class, props -> true);
        assertThat(configurations).as("the test Epistola plugin configuration is deployed").isNotEmpty();
        return (EpistolaPlugin) pluginService.createInstance((PluginConfiguration) configurations.get(0));
    }

    private boolean messageSubscriptionExists(ProcessInstance pi) {
        return runtimeService.createEventSubscriptionQuery()
                .processInstanceId(pi.getId()).eventName(MESSAGE).count() > 0;
    }

    private Object storedValue(ProcessInstance pi) {
        var historic = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(pi.getId()).variableName(EpistolaTestStoreDelegate.OUTPUT_VARIABLE).singleResult();
        return historic == null ? null : historic.getValue();
    }
}
