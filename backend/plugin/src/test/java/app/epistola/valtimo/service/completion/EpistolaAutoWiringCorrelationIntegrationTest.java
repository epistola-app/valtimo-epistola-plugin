package app.epistola.valtimo.service.completion;

import app.epistola.valtimo.deployment.EpistolaCatchEventLinkResolver;
import app.epistola.valtimo.deployment.EpistolaCatchEventParseListener;
import app.epistola.valtimo.domain.EpistolaProcessVariables;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.processlink.domain.ProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.VariableInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end guard for the <strong>auto-wiring</strong> path that the rest of the suite cannot reach:
 * the real {@link EpistolaCatchEventParseListener} (Operaton's sanctioned parse SPI) attaching the real
 * {@link EpistolaCatchEventStartListener}, which uses the real {@link EpistolaCatchEventLinkResolver}
 * (backed by a stub {@link ProcessLinkService}) to auto-pin each branch's {@code epistolaWaitFor} token
 * — with <em>no</em> declarative {@code camunda:inputParameter} in the BPMN — and the real
 * {@link EpistolaMessageCorrelationService} waking exactly the matching branch.
 *
 * <p>This runs against a standalone in-memory engine (no Testcontainers), so it executes in CI where the
 * full-app E2E does not. It is the regression net for an Operaton bump silently breaking the parse SPI
 * or the runtime resolver/listener wiring. The full Spring after-commit self-heal still belongs to the
 * full-app E2E; here {@code armSelfHeal} no-ops because there is no active Spring transaction.
 */
class EpistolaAutoWiringCorrelationIntegrationTest {

    private static final String MESSAGE = EpistolaProcessVariables.MESSAGE_NAME;
    private static final String TENANT = "demo";

    /** Parallel generation WITHOUT any declarative epistolaWaitFor mapping — the token must be auto-pinned. */
    private static final String PARALLEL_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs_auto">
              <bpmn:message id="msg" name="EpistolaDocumentGenerated" />
              <bpmn:process id="auto-parallel-generation" isExecutable="true" camunda:historyTimeToLive="P1D">
                <bpmn:startEvent id="start"><bpmn:outgoing>f_s</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f_s" sourceRef="start" targetRef="fork" />
                <bpmn:parallelGateway id="fork">
                  <bpmn:incoming>f_s</bpmn:incoming>
                  <bpmn:outgoing>f_a1</bpmn:outgoing><bpmn:outgoing>f_b1</bpmn:outgoing><bpmn:outgoing>f_c1</bpmn:outgoing>
                </bpmn:parallelGateway>
                %s
                %s
                %s
                <bpmn:parallelGateway id="join">
                  <bpmn:incoming>f_a3</bpmn:incoming><bpmn:incoming>f_b3</bpmn:incoming><bpmn:incoming>f_c3</bpmn:incoming>
                  <bpmn:outgoing>f_e</bpmn:outgoing>
                </bpmn:parallelGateway>
                <bpmn:sequenceFlow id="f_e" sourceRef="join" targetRef="end" />
                <bpmn:endEvent id="end"><bpmn:incoming>f_e</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """.formatted(
            branch("a", "req-a", "resultA"),
            branch("b", "req-b", "resultB"),
            branch("c", "req-c", "resultC"));

    private static String branch(String suffix, String requestId, String resultVar) {
        return """
                <bpmn:sequenceFlow id="f_%1$s1" sourceRef="fork" targetRef="submit-%1$s" />
                <bpmn:serviceTask id="submit-%1$s" camunda:class="app.epistola.valtimo.service.completion.EpistolaAutoWiringCorrelationIntegrationTest$SubmitDelegate">
                  <bpmn:extensionElements><camunda:inputOutput>
                    <camunda:inputParameter name="testRequestId">%2$s</camunda:inputParameter>
                    <camunda:inputParameter name="testResultVar">%3$s</camunda:inputParameter>
                  </camunda:inputOutput></bpmn:extensionElements>
                  <bpmn:incoming>f_%1$s1</bpmn:incoming><bpmn:outgoing>f_%1$s2</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="f_%1$s2" sourceRef="submit-%1$s" targetRef="wait-%1$s" />
                <bpmn:intermediateCatchEvent id="wait-%1$s" camunda:asyncAfter="true">
                  <bpmn:incoming>f_%1$s2</bpmn:incoming><bpmn:outgoing>f_%1$s3</bpmn:outgoing>
                  <bpmn:messageEventDefinition messageRef="msg" />
                </bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="f_%1$s3" sourceRef="wait-%1$s" targetRef="join" />
                """.formatted(suffix, requestId, resultVar);
    }

    /** Receive-task wait WITHOUT any declarative epistolaWaitFor mapping — the token must be auto-pinned. */
    private static final String RECEIVE_TASK_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs_auto_rt">
              <bpmn:message id="msg" name="EpistolaDocumentGenerated" />
              <bpmn:process id="auto-receive-task" isExecutable="true" camunda:historyTimeToLive="P1D">
                <bpmn:startEvent id="rstart"><bpmn:outgoing>rf0</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="rf0" sourceRef="rstart" targetRef="submit-a" />
                <bpmn:serviceTask id="submit-a" camunda:class="app.epistola.valtimo.service.completion.EpistolaAutoWiringCorrelationIntegrationTest$SubmitDelegate">
                  <bpmn:extensionElements><camunda:inputOutput>
                    <camunda:inputParameter name="testRequestId">req-a</camunda:inputParameter>
                    <camunda:inputParameter name="testResultVar">resultA</camunda:inputParameter>
                  </camunda:inputOutput></bpmn:extensionElements>
                  <bpmn:incoming>rf0</bpmn:incoming><bpmn:outgoing>rf1</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="rf1" sourceRef="submit-a" targetRef="recv-a" />
                <bpmn:receiveTask id="recv-a" messageRef="msg">
                  <bpmn:incoming>rf1</bpmn:incoming><bpmn:outgoing>rf2</bpmn:outgoing>
                </bpmn:receiveTask>
                <bpmn:sequenceFlow id="rf2" sourceRef="recv-a" targetRef="rend" />
                <bpmn:endEvent id="rend"><bpmn:incoming>rf2</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private ProcessEngine processEngine;
    private RuntimeService runtimeService;
    private ManagementService managementService;
    private EpistolaMessageCorrelationService correlationService;

    @BeforeEach
    void setUp() {
        // The parse listener is attached to the engine config BEFORE build (parse time), but the start
        // listener's collaborators (resolver/correlation) need engine services that exist only AFTER
        // build — the same bootstrap ordering the app solves with @Lazy. Bridge with a deferred listener
        // that resolves to the real one at runtime (catch-event entry, well after build).
        AtomicReference<EpistolaCatchEventStartListener> startRef = new AtomicReference<>();
        ExecutionListener deferred = execution -> {
            EpistolaCatchEventStartListener real = startRef.get();
            if (real != null) {
                real.notify(execution);
            }
        };

        ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        configuration.setJobExecutorActivate(false);
        configuration.setEnforceHistoryTimeToLive(false);
        configuration.setCustomPostBPMNParseListeners(new ArrayList<>(List.of(new EpistolaCatchEventParseListener(deferred))));
        processEngine = configuration.buildProcessEngine();

        runtimeService = processEngine.getRuntimeService();
        managementService = processEngine.getManagementService();
        correlationService = new EpistolaMessageCorrelationService(runtimeService);

        // Stub the process links the resolver pairs against — one generate-document per branch.
        // Build the links before the outer when(...) to avoid Mockito's UnfinishedStubbingException.
        PluginProcessLink linkA = generateDocumentLink("submit-a", "resultA");
        PluginProcessLink linkB = generateDocumentLink("submit-b", "resultB");
        PluginProcessLink linkC = generateDocumentLink("submit-c", "resultC");
        ProcessLinkService processLinkService = mock(ProcessLinkService.class);
        when(processLinkService.getProcessLinks(anyString())).thenReturn(List.<ProcessLink>of(linkA, linkB, linkC));
        EpistolaCatchEventLinkResolver resolver =
                new EpistolaCatchEventLinkResolver(processEngine.getRepositoryService(), processLinkService);

        startRef.set(new EpistolaCatchEventStartListener(resolver, correlationService));

        processEngine.getRepositoryService().createDeployment()
                .addString("auto-parallel.bpmn", PARALLEL_BPMN)
                .deploy();
    }

    @AfterEach
    void tearDown() {
        if (processEngine != null) {
            processEngine.close();
        }
    }

    @Test
    void autoPinsEachBranchTokenAndCorrelatesIndependentlyWithoutDeclarativeMapping() {
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("auto-parallel-generation");
        assertThat(messageSubscriptionCount(pi.getId())).isEqualTo(3);

        // Prove the AUTO path actually pinned the token (no input mapping in the BPMN did this).
        List<String> pinned = runtimeService.createVariableInstanceQuery()
                .processInstanceIdIn(pi.getId())
                .variableName(EpistolaProcessVariables.WAIT_FOR)
                .list().stream().map(VariableInstance::getValue).map(String::valueOf).sorted().toList();
        assertThat(pinned).containsExactly(
                EpistolaMessageCorrelationService.buildJobPath(TENANT, "req-a"),
                EpistolaMessageCorrelationService.buildJobPath(TENANT, "req-b"),
                EpistolaMessageCorrelationService.buildJobPath(TENANT, "req-c"));

        // Correlate ONLY branch A — must wake exactly that branch.
        assertThat(correlationService.correlateCompletion(TENANT, "req-a", "COMPLETED", "doc-a", null)).isEqualTo(1);
        assertThat(messageSubscriptionCount(pi.getId())).isEqualTo(2);
        assertThat(documentIdOf(runtimeService.getVariable(pi.getId(), "resultA"))).isEqualTo("doc-a");
        assertThat(documentIdOf(runtimeService.getVariable(pi.getId(), "resultB")))
                .as("branch B untouched by branch A's result").isNull();
        assertThat(documentIdOf(runtimeService.getVariable(pi.getId(), "resultC")))
                .as("branch C untouched by branch A's result").isNull();

        assertThat(correlationService.correlateCompletion(TENANT, "req-b", "COMPLETED", "doc-b", null)).isEqualTo(1);
        assertThat(correlationService.correlateCompletion(TENANT, "req-c", "COMPLETED", "doc-c", null)).isEqualTo(1);
        executeAllJobs();

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).singleResult())
                .as("all three branches joined and the process completed").isNull();
    }

    @Test
    void autoPinsTheTokenForAReceiveTaskWaitAndCorrelatesWithoutDeclarativeMapping() {
        processEngine.getRepositoryService().createDeployment()
                .addString("auto-receive.bpmn", RECEIVE_TASK_BPMN)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("auto-receive-task");

        // The receive task is parked on the message, and the token was auto-pinned (no input mapping
        // in the BPMN did this — receive tasks are now wired exactly like round catch events).
        assertThat(messageSubscriptionCount(pi.getId())).isEqualTo(1);
        List<String> pinned = runtimeService.createVariableInstanceQuery()
                .processInstanceIdIn(pi.getId())
                .variableName(EpistolaProcessVariables.WAIT_FOR)
                .list().stream().map(VariableInstance::getValue).map(String::valueOf).toList();
        assertThat(pinned).containsExactly(EpistolaMessageCorrelationService.buildJobPath(TENANT, "req-a"));

        // The completion correlates the receive task (waking exactly one execution); the single-branch
        // process then runs straight to completion. (We don't read resultA afterwards — the instance has
        // already ended, so its runtime variables are gone; correlated==1 + completion is the proof.)
        assertThat(correlationService.correlateCompletion(TENANT, "req-a", "COMPLETED", "doc-a", null)).isEqualTo(1);
        executeAllJobs();
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).singleResult())
                .as("receive-task process completed after correlation").isNull();
    }

    private void executeAllJobs() {
        for (int guard = 0; guard < 50; guard++) {
            List<Job> jobs = managementService.createJobQuery().list();
            if (jobs.isEmpty()) {
                return;
            }
            jobs.forEach(job -> managementService.executeJob(job.getId()));
        }
    }

    private long messageSubscriptionCount(String processInstanceId) {
        return runtimeService.createEventSubscriptionQuery()
                .processInstanceId(processInstanceId).eventName(MESSAGE).count();
    }

    private Object documentIdOf(Object richResult) {
        return richResult instanceof Map<?, ?> map ? map.get(EpistolaProcessVariables.RESULT_KEY_DOCUMENT_ID) : null;
    }

    private static PluginProcessLink generateDocumentLink(String activityId, String resultProcessVariable) {
        ObjectNode props = JsonNodeFactory.instance.objectNode();
        props.put("resultProcessVariable", resultProcessVariable);
        PluginProcessLink link = mock(PluginProcessLink.class);
        when(link.getActivityId()).thenReturn(activityId);
        when(link.getPluginActionDefinitionKey()).thenReturn("epistola-generate-document");
        when(link.getActionProperties()).thenReturn(props);
        return link;
    }

    /** Mirrors {@code EpistolaPlugin.generateDocument}: rich result (incl. jobPath) + jobPath→resultVar locator. */
    public static class SubmitDelegate implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            String requestId = (String) execution.getVariable("testRequestId");
            String resultVar = (String) execution.getVariable("testResultVar");
            String jobPath = EpistolaMessageCorrelationService.buildJobPath(TENANT, requestId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(EpistolaProcessVariables.RESULT_KEY_REQUEST_ID, requestId);
            result.put(EpistolaProcessVariables.RESULT_KEY_STATUS, "PENDING");
            result.put(EpistolaProcessVariables.RESULT_KEY_DOCUMENT_ID, null);
            result.put(EpistolaProcessVariables.RESULT_KEY_ERROR_MESSAGE, null);
            result.put(EpistolaProcessVariables.RESULT_KEY_JOB_PATH, jobPath);
            execution.setVariable(resultVar, result);
            execution.setVariable(jobPath, resultVar); // locator: jobPath -> result variable name
        }
    }
}
