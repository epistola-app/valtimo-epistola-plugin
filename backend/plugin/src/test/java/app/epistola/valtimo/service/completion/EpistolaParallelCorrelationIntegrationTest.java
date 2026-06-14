package app.epistola.valtimo.service.completion;

import app.epistola.valtimo.deployment.EpistolaCatchEventTokenParseListener;
import app.epistola.valtimo.domain.EpistolaProcessVariables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.runtime.ProcessInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces — and guards against — the parallel-generation correlation bug against a real
 * (standalone, in-memory H2) Operaton engine, deploying the <em>actual</em> production shape: a
 * parallel gateway whose branches each run {@code generate-document} → an {@code asyncAfter}
 * {@code EpistolaDocumentGenerated} message catch event → join. The {@link
 * EpistolaCatchEventTokenParseListener} is registered exactly as it is in the running app (via the
 * engine's custom post-BPMN parse listeners), so the test exercises the real auto-pinning of the
 * per-branch {@code epistolaWaitFor} token and the token-based {@link
 * EpistolaMessageCorrelationService#correlateCompletion correlation}.
 *
 * <p>{@link SubmitDelegate} mirrors {@code EpistolaPlugin.generateDocument}: it writes the
 * activity-named jobPath variable and the jobPath-named locator, plus a PENDING rich result on the
 * branch's uniquely-named result variable. The assertions prove each completion wakes exactly its own
 * branch and updates exactly its own result variable — no cross-contamination, no "wake all".
 */
class EpistolaParallelCorrelationIntegrationTest {

    private static final String MESSAGE = EpistolaProcessVariables.MESSAGE_NAME;
    private static final String TENANT = "demo";

    /** Parallel gateway, 3 branches, each: submit (generate-document mimic) → asyncAfter catch → join. */
    private static final String PARALLEL_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs_par">
              <bpmn:message id="msg" name="EpistolaDocumentGenerated" />
              <bpmn:process id="parallel-generation" isExecutable="true" camunda:historyTimeToLive="P1D">
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
                <bpmn:serviceTask id="submit-%1$s" camunda:class="app.epistola.valtimo.service.completion.EpistolaParallelCorrelationIntegrationTest$SubmitDelegate">
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

    /** Multi-instance (parallel) subprocess: each instance submits → asyncAfter catch → end. */
    private static final String MULTI_INSTANCE_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs_mi">
              <bpmn:message id="msg_mi" name="EpistolaDocumentGenerated" />
              <bpmn:process id="mi-generation" isExecutable="true" camunda:historyTimeToLive="P1D">
                <bpmn:startEvent id="mi_start"><bpmn:outgoing>mf1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="mf1" sourceRef="mi_start" targetRef="mi-sub" />
                <bpmn:subProcess id="mi-sub">
                  <bpmn:incoming>mf1</bpmn:incoming><bpmn:outgoing>mf2</bpmn:outgoing>
                  <bpmn:multiInstanceLoopCharacteristics isSequential="false"
                       camunda:collection="requestIds" camunda:elementVariable="req" />
                  <bpmn:startEvent id="sub_start"><bpmn:outgoing>sf1</bpmn:outgoing></bpmn:startEvent>
                  <bpmn:sequenceFlow id="sf1" sourceRef="sub_start" targetRef="submit-mi" />
                  <bpmn:serviceTask id="submit-mi" camunda:class="app.epistola.valtimo.service.completion.EpistolaParallelCorrelationIntegrationTest$MiSubmitDelegate">
                    <bpmn:incoming>sf1</bpmn:incoming><bpmn:outgoing>sf2</bpmn:outgoing>
                  </bpmn:serviceTask>
                  <bpmn:sequenceFlow id="sf2" sourceRef="submit-mi" targetRef="wait-mi" />
                  <bpmn:intermediateCatchEvent id="wait-mi" camunda:asyncAfter="true">
                    <bpmn:incoming>sf2</bpmn:incoming><bpmn:outgoing>sf3</bpmn:outgoing>
                    <bpmn:messageEventDefinition messageRef="msg_mi" />
                  </bpmn:intermediateCatchEvent>
                  <bpmn:sequenceFlow id="sf3" sourceRef="wait-mi" targetRef="sub_end" />
                  <bpmn:endEvent id="sub_end"><bpmn:incoming>sf3</bpmn:incoming></bpmn:endEvent>
                </bpmn:subProcess>
                <bpmn:sequenceFlow id="mf2" sourceRef="mi-sub" targetRef="mi_end" />
                <bpmn:endEvent id="mi_end"><bpmn:incoming>mf2</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    /** Single sequential branch — regression guard for non-parallel flows. */
    private static final String SEQUENTIAL_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs_seq">
              <bpmn:message id="msg_seq" name="EpistolaDocumentGenerated" />
              <bpmn:process id="sequential-generation" isExecutable="true" camunda:historyTimeToLive="P1D">
                <bpmn:startEvent id="s_start"><bpmn:outgoing>sq1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="sq1" sourceRef="s_start" targetRef="submit-s" />
                <bpmn:serviceTask id="submit-s" camunda:class="app.epistola.valtimo.service.completion.EpistolaParallelCorrelationIntegrationTest$SubmitDelegate">
                  <bpmn:extensionElements><camunda:inputOutput>
                    <camunda:inputParameter name="testRequestId">req-seq</camunda:inputParameter>
                    <camunda:inputParameter name="testResultVar">resultSeq</camunda:inputParameter>
                  </camunda:inputOutput></bpmn:extensionElements>
                  <bpmn:incoming>sq1</bpmn:incoming><bpmn:outgoing>sq2</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="sq2" sourceRef="submit-s" targetRef="wait-s" />
                <bpmn:intermediateCatchEvent id="wait-s" camunda:asyncAfter="true">
                  <bpmn:incoming>sq2</bpmn:incoming><bpmn:outgoing>sq3</bpmn:outgoing>
                  <bpmn:messageEventDefinition messageRef="msg_seq" />
                </bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="sq3" sourceRef="wait-s" targetRef="s_end" />
                <bpmn:endEvent id="s_end"><bpmn:incoming>sq3</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    /** Catch event with an author-supplied {@code epistolaWaitFor} input mapping (override). */
    private static final String OVERRIDE_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs_ovr">
              <bpmn:message id="msg_ovr" name="EpistolaDocumentGenerated" />
              <bpmn:process id="override-generation" isExecutable="true" camunda:historyTimeToLive="P1D">
                <bpmn:startEvent id="o_start"><bpmn:outgoing>oq1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="oq1" sourceRef="o_start" targetRef="submit-o" />
                <bpmn:serviceTask id="submit-o" camunda:class="app.epistola.valtimo.service.completion.EpistolaParallelCorrelationIntegrationTest$SubmitDelegate">
                  <bpmn:extensionElements><camunda:inputOutput>
                    <camunda:inputParameter name="testRequestId">req-auto</camunda:inputParameter>
                    <camunda:inputParameter name="testResultVar">resultO</camunda:inputParameter>
                  </camunda:inputOutput></bpmn:extensionElements>
                  <bpmn:incoming>oq1</bpmn:incoming><bpmn:outgoing>oq2</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="oq2" sourceRef="submit-o" targetRef="wait-o" />
                <bpmn:intermediateCatchEvent id="wait-o" camunda:asyncAfter="true">
                  <bpmn:extensionElements><camunda:inputOutput>
                    <camunda:inputParameter name="epistolaWaitFor">epistola:job:demo/req-override</camunda:inputParameter>
                  </camunda:inputOutput></bpmn:extensionElements>
                  <bpmn:incoming>oq2</bpmn:incoming><bpmn:outgoing>oq3</bpmn:outgoing>
                  <bpmn:messageEventDefinition messageRef="msg_ovr" />
                </bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="oq3" sourceRef="wait-o" targetRef="o_end" />
                <bpmn:endEvent id="o_end"><bpmn:incoming>oq3</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    /** Async boundary BEFORE the catch event: lets a result arrive before the branch subscribes. */
    private static final String ASYNC_BOUNDARY_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs_async">
              <bpmn:message id="msg_async" name="EpistolaDocumentGenerated" />
              <bpmn:process id="async-generation" isExecutable="true" camunda:historyTimeToLive="P1D">
                <bpmn:startEvent id="a_start"><bpmn:outgoing>aq1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="aq1" sourceRef="a_start" targetRef="submit-async" />
                <bpmn:serviceTask id="submit-async" camunda:class="app.epistola.valtimo.service.completion.EpistolaParallelCorrelationIntegrationTest$SubmitDelegate">
                  <bpmn:extensionElements><camunda:inputOutput>
                    <camunda:inputParameter name="testRequestId">req-async</camunda:inputParameter>
                    <camunda:inputParameter name="testResultVar">resultAsync</camunda:inputParameter>
                  </camunda:inputOutput></bpmn:extensionElements>
                  <bpmn:incoming>aq1</bpmn:incoming><bpmn:outgoing>aq2</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="aq2" sourceRef="submit-async" targetRef="wait-async" />
                <bpmn:intermediateCatchEvent id="wait-async" camunda:asyncBefore="true">
                  <bpmn:incoming>aq2</bpmn:incoming><bpmn:outgoing>aq3</bpmn:outgoing>
                  <bpmn:messageEventDefinition messageRef="msg_async" />
                </bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="aq3" sourceRef="wait-async" targetRef="a_end" />
                <bpmn:endEvent id="a_end"><bpmn:incoming>aq3</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private ProcessEngine processEngine;
    private RuntimeService runtimeService;
    private ManagementService managementService;
    private EpistolaMessageCorrelationService correlationService;

    @BeforeEach
    void setUp() {
        ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        configuration.setJobExecutorActivate(false);
        configuration.setEnforceHistoryTimeToLive(false);
        // Register the catch-event token parse listener exactly as the running app does.
        // Mutable list: the engine appends its own listeners during bootstrap.
        configuration.setCustomPostBPMNParseListeners(new ArrayList<>(List.of(new EpistolaCatchEventTokenParseListener())));
        processEngine = configuration.buildProcessEngine();
        runtimeService = processEngine.getRuntimeService();
        managementService = processEngine.getManagementService();
        correlationService = new EpistolaMessageCorrelationService(runtimeService);
        processEngine.getRepositoryService().createDeployment()
                .addString("parallel.bpmn", PARALLEL_BPMN)
                .addString("mi.bpmn", MULTI_INSTANCE_BPMN)
                .addString("sequential.bpmn", SEQUENTIAL_BPMN)
                .addString("override.bpmn", OVERRIDE_BPMN)
                .addString("async.bpmn", ASYNC_BOUNDARY_BPMN)
                .deploy();
    }

    @AfterEach
    void tearDown() {
        if (processEngine != null) {
            processEngine.close();
        }
    }

    @Test
    void eachParallelBranchCorrelatesToItsOwnResultIndependently() {
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("parallel-generation");
        assertThat(messageSubscriptionCount(pi.getId())).isEqualTo(3);

        // Correlate ONLY branch A — must wake exactly that branch, not its siblings.
        assertThat(correlationService.correlateCompletion(TENANT, "req-a", "COMPLETED", "doc-a", null))
                .isEqualTo(1);
        assertThat(messageSubscriptionCount(pi.getId())).isEqualTo(2);
        assertThat(documentIdOf(runtimeService.getVariable(pi.getId(), "resultA"))).isEqualTo("doc-a");
        assertThat(documentIdOf(runtimeService.getVariable(pi.getId(), "resultB")))
                .as("branch B must be untouched by branch A's result").isNull();
        assertThat(documentIdOf(runtimeService.getVariable(pi.getId(), "resultC")))
                .as("branch C must be untouched by branch A's result").isNull();

        assertThat(correlationService.correlateCompletion(TENANT, "req-b", "COMPLETED", "doc-b", null)).isEqualTo(1);
        assertThat(correlationService.correlateCompletion(TENANT, "req-c", "COMPLETED", "doc-c", null)).isEqualTo(1);

        // asyncAfter on the catch events defers the continuation to jobs; run them to reach the join.
        executeAllJobs();

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).singleResult())
                .as("all three branches joined and the process completed").isNull();
        assertThat(documentIdOf(historicValue(pi.getId(), "resultA"))).isEqualTo("doc-a");
        assertThat(documentIdOf(historicValue(pi.getId(), "resultB"))).isEqualTo("doc-b");
        assertThat(documentIdOf(historicValue(pi.getId(), "resultC"))).isEqualTo("doc-c");
    }

    @Test
    void multiInstanceBranchesCorrelateIndependently() {
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("mi-generation",
                Map.of("requestIds", List.of("mi-1", "mi-2", "mi-3")));
        assertThat(messageSubscriptionCount(pi.getId())).isEqualTo(3);

        assertThat(correlationService.correlateCompletion(TENANT, "mi-2", "COMPLETED", "doc-2", null)).isEqualTo(1);
        assertThat(messageSubscriptionCount(pi.getId())).isEqualTo(2);

        assertThat(correlationService.correlateCompletion(TENANT, "mi-1", "COMPLETED", "doc-1", null)).isEqualTo(1);
        assertThat(correlationService.correlateCompletion(TENANT, "mi-3", "COMPLETED", "doc-3", null)).isEqualTo(1);
        executeAllJobs();

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).singleResult())
                .as("all multi-instance iterations joined and the process completed").isNull();
    }

    @Test
    void sequentialGenerationCorrelatesAndCompletes() {
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("sequential-generation");
        assertThat(messageSubscriptionCount(pi.getId())).isEqualTo(1);

        assertThat(correlationService.correlateCompletion(TENANT, "req-seq", "COMPLETED", "doc-seq", null)).isEqualTo(1);
        executeAllJobs();

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).singleResult())
                .as("the single branch completed").isNull();
        assertThat(documentIdOf(historicValue(pi.getId(), "resultSeq"))).isEqualTo("doc-seq");
    }

    @Test
    void authorSuppliedWaitForOverrideIsNotClobbered() {
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("override-generation");
        // The catch event author pinned epistolaWaitFor explicitly — the listener must not overwrite it,
        // so a result for the auto-resolved job does NOT match, but the override job does.
        assertThat(correlationService.correlateCompletion(TENANT, "req-auto", "COMPLETED", "doc-auto", null))
                .as("auto job must not match an overridden catch event").isZero();
        assertThat(correlationService.correlateCompletion(TENANT, "req-override", "COMPLETED", "doc-ovr", null))
                .as("the overridden token wakes the catch event").isEqualTo(1);
    }

    @Test
    void resultArrivingBeforeTheCatchEventSubscribesDoesNotStall() {
        // asyncBefore on the catch event: after submit, the process parks at the async job and the
        // catch event has NOT subscribed yet — the window where a fast result would otherwise be lost.
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("async-generation");
        assertThat(messageSubscriptionCount(pi.getId()))
                .as("catch event has not subscribed yet (parked at async boundary)").isZero();

        // Result lands before the subscription exists: no subscription to wake, result var updated.
        assertThat(correlationService.correlateCompletion(TENANT, "req-async", "COMPLETED", "doc-async", null))
                .isZero();

        // Now the branch advances into the catch event. Self-heal: it sees the terminal result on
        // entry and continues instead of subscribing and stalling forever.
        executeAllJobs();

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).singleResult())
                .as("self-heal completed the catch event instead of stalling").isNull();
        assertThat(documentIdOf(historicValue(pi.getId(), "resultAsync"))).isEqualTo("doc-async");
    }

    @Test
    void correlatingAnUnknownJobWakesNothing() {
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("parallel-generation");
        assertThat(correlationService.correlateCompletion(TENANT, "req-unknown", "COMPLETED", "doc-x", null))
                .isZero();
        assertThat(messageSubscriptionCount(pi.getId())).isEqualTo(3);
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

    private Object historicValue(String processInstanceId, String variableName) {
        var historic = processEngine.getHistoryService().createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId).variableName(variableName).singleResult();
        return historic == null ? null : historic.getValue();
    }

    /** Mirrors {@code EpistolaPlugin.generateDocument}: activity-named jobPath + jobPath locator + PENDING result. */
    public static class SubmitDelegate implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            String requestId = (String) execution.getVariable("testRequestId");
            String resultVar = (String) execution.getVariable("testResultVar");
            String activityId = execution.getCurrentActivityId();
            String jobPath = EpistolaMessageCorrelationService.buildJobPath(TENANT, requestId);

            execution.setVariable(EpistolaProcessVariables.activityJobPathVariable(activityId), jobPath);
            execution.setVariable(jobPath, resultVar);

            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put(EpistolaProcessVariables.RESULT_KEY_REQUEST_ID, requestId);
            pending.put(EpistolaProcessVariables.RESULT_KEY_STATUS, "PENDING");
            pending.put(EpistolaProcessVariables.RESULT_KEY_DOCUMENT_ID, null);
            pending.put(EpistolaProcessVariables.RESULT_KEY_ERROR_MESSAGE, null);
            execution.setVariable(resultVar, pending);
        }
    }

    /** Multi-instance variant: requestId comes from the loop element variable {@code req}; one shared result var name. */
    public static class MiSubmitDelegate implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            String requestId = (String) execution.getVariable("req");
            String resultVar = "miResult";
            String activityId = execution.getCurrentActivityId();
            String jobPath = EpistolaMessageCorrelationService.buildJobPath(TENANT, requestId);

            execution.setVariable(EpistolaProcessVariables.activityJobPathVariable(activityId), jobPath);
            execution.setVariable(jobPath, resultVar);

            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put(EpistolaProcessVariables.RESULT_KEY_REQUEST_ID, requestId);
            pending.put(EpistolaProcessVariables.RESULT_KEY_STATUS, "PENDING");
            pending.put(EpistolaProcessVariables.RESULT_KEY_DOCUMENT_ID, null);
            pending.put(EpistolaProcessVariables.RESULT_KEY_ERROR_MESSAGE, null);
            execution.setVariable(resultVar, pending);
        }
    }
}
