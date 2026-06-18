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

    /**
     * Event-based gateway racing the completion message against a timer. The token can't be pinned by
     * the catch event's own listener here (it isn't entered until the message arrives), so it is pinned
     * UPSTREAM on the generate output (camunda:outputParameter) — the example's mechanism.
     */
    private static final String EVENT_GATEWAY_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs_evt">
              <bpmn:message id="msg" name="EpistolaDocumentGenerated" />
              <bpmn:process id="auto-event-gateway" isExecutable="true" camunda:historyTimeToLive="P1D">
                <bpmn:startEvent id="estart"><bpmn:outgoing>ef0</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="ef0" sourceRef="estart" targetRef="submit-a" />
                <bpmn:serviceTask id="submit-a" camunda:class="app.epistola.valtimo.service.completion.EpistolaAutoWiringCorrelationIntegrationTest$SubmitDelegate">
                  <bpmn:extensionElements><camunda:inputOutput>
                    <camunda:inputParameter name="testRequestId">req-a</camunda:inputParameter>
                    <camunda:inputParameter name="testResultVar">resultA</camunda:inputParameter>
                    <camunda:outputParameter name="epistolaWaitFor">${resultA.jobPath}</camunda:outputParameter>
                  </camunda:inputOutput></bpmn:extensionElements>
                  <bpmn:incoming>ef0</bpmn:incoming><bpmn:outgoing>ef1</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="ef1" sourceRef="submit-a" targetRef="egw" />
                <bpmn:eventBasedGateway id="egw">
                  <bpmn:incoming>ef1</bpmn:incoming><bpmn:outgoing>ef_msg</bpmn:outgoing><bpmn:outgoing>ef_timer</bpmn:outgoing>
                </bpmn:eventBasedGateway>
                <bpmn:sequenceFlow id="ef_msg" sourceRef="egw" targetRef="ewait" />
                <bpmn:intermediateCatchEvent id="ewait">
                  <bpmn:incoming>ef_msg</bpmn:incoming><bpmn:outgoing>ef_ok</bpmn:outgoing>
                  <bpmn:messageEventDefinition messageRef="msg" />
                </bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="ef_ok" sourceRef="ewait" targetRef="eok" />
                <bpmn:endEvent id="eok"><bpmn:incoming>ef_ok</bpmn:incoming></bpmn:endEvent>
                <bpmn:sequenceFlow id="ef_timer" sourceRef="egw" targetRef="etimer" />
                <bpmn:intermediateCatchEvent id="etimer">
                  <bpmn:incoming>ef_timer</bpmn:incoming><bpmn:outgoing>ef_to</bpmn:outgoing>
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT30M</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="ef_to" sourceRef="etimer" targetRef="eto" />
                <bpmn:endEvent id="eto"><bpmn:incoming>ef_to</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    /**
     * Exclusive split → two generates with DIFFERENT result variables → merge → ONE shared catch event.
     * The resolver can pin only one result variable to that catch event, so the other branch stalls
     * (the letter-by-type anti-pattern). Branch order [a,b,c] means the catch event resolves to resultB.
     */
    private static final String AMBIGUOUS_MERGE_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs_amb">
              <bpmn:message id="msg" name="EpistolaDocumentGenerated" />
              <bpmn:process id="auto-ambiguous" isExecutable="true" camunda:historyTimeToLive="P1D">
                <bpmn:startEvent id="astart"><bpmn:outgoing>af0</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="af0" sourceRef="astart" targetRef="axor" />
                <bpmn:exclusiveGateway id="axor" default="af_b"><bpmn:incoming>af0</bpmn:incoming>
                  <bpmn:outgoing>af_a</bpmn:outgoing><bpmn:outgoing>af_b</bpmn:outgoing></bpmn:exclusiveGateway>
                <bpmn:sequenceFlow id="af_a" sourceRef="axor" targetRef="submit-a">
                  <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${branch == 'a'}</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="af_b" sourceRef="axor" targetRef="submit-b" />
                <bpmn:serviceTask id="submit-a" camunda:class="app.epistola.valtimo.service.completion.EpistolaAutoWiringCorrelationIntegrationTest$SubmitDelegate">
                  <bpmn:extensionElements><camunda:inputOutput>
                    <camunda:inputParameter name="testRequestId">req-a</camunda:inputParameter>
                    <camunda:inputParameter name="testResultVar">resultA</camunda:inputParameter>
                  </camunda:inputOutput></bpmn:extensionElements>
                  <bpmn:incoming>af_a</bpmn:incoming><bpmn:outgoing>af_a2</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:serviceTask id="submit-b" camunda:class="app.epistola.valtimo.service.completion.EpistolaAutoWiringCorrelationIntegrationTest$SubmitDelegate">
                  <bpmn:extensionElements><camunda:inputOutput>
                    <camunda:inputParameter name="testRequestId">req-b</camunda:inputParameter>
                    <camunda:inputParameter name="testResultVar">resultB</camunda:inputParameter>
                  </camunda:inputOutput></bpmn:extensionElements>
                  <bpmn:incoming>af_b</bpmn:incoming><bpmn:outgoing>af_b2</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="af_a2" sourceRef="submit-a" targetRef="amerge" />
                <bpmn:sequenceFlow id="af_b2" sourceRef="submit-b" targetRef="amerge" />
                <bpmn:exclusiveGateway id="amerge">
                  <bpmn:incoming>af_a2</bpmn:incoming><bpmn:incoming>af_b2</bpmn:incoming><bpmn:outgoing>af_w</bpmn:outgoing>
                </bpmn:exclusiveGateway>
                <bpmn:sequenceFlow id="af_w" sourceRef="amerge" targetRef="await" />
                <bpmn:intermediateCatchEvent id="await">
                  <bpmn:incoming>af_w</bpmn:incoming><bpmn:outgoing>af_e</bpmn:outgoing>
                  <bpmn:messageEventDefinition messageRef="msg" />
                </bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="af_e" sourceRef="await" targetRef="aend" />
                <bpmn:endEvent id="aend"><bpmn:incoming>af_e</bpmn:incoming></bpmn:endEvent>
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

    @Test
    void eventBasedGatewayWaitCannotBeCorrelated_subscriptionLivesOnAChildExecution() {
        // Documents a hard limitation: an event-based gateway places its message subscription on a
        // transient CHILD execution, while any token pinned upstream (here via the generate output
        // mapping) sits on the PARENT execution. Correlation requires the subscription and the
        // epistolaWaitFor token on the SAME execution, so they never meet — the branch cannot be woken.
        // The correct "wait with a timeout" is a receive task / catch event with an interrupting boundary
        // timer (see single-document-receive-task), NOT an event-based gateway.
        processEngine.getRepositoryService().createDeployment()
                .addString("auto-event-gateway.bpmn", EVENT_GATEWAY_BPMN)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("auto-event-gateway");

        // The message subscription exists behind the gateway, and the token IS pinned upstream...
        assertThat(messageSubscriptionCount(pi.getId())).isEqualTo(1);
        assertThat(runtimeService.createVariableInstanceQuery().processInstanceIdIn(pi.getId())
                .variableName(EpistolaProcessVariables.WAIT_FOR).list()).hasSize(1);

        // ...but they are on different executions, so the targeted correlation query matches nothing
        // and the wait stays stuck (in a real process the PT30M timer would eventually fire).
        assertThat(correlationService.correlateCompletion(TENANT, "req-a", "COMPLETED", "doc-a", null))
                .as("event-gateway wait cannot be correlated").isEqualTo(0);
        assertThat(messageSubscriptionCount(pi.getId())).as("still stuck at the gateway").isEqualTo(1);
    }

    @Test
    void ambiguousMergedCatchEventLeavesTheMispairedBranchUncorrelated() {
        processEngine.getRepositoryService().createDeployment()
                .addString("auto-ambiguous.bpmn", AMBIGUOUS_MERGE_BPMN)
                .deploy();

        // Branch order [a,b,c] in the stubbed links means the shared catch event resolves to resultB.
        // Run branch A (writes resultA): the catch event reads ${resultB.jobPath} = null, so no token is
        // pinned, and branch A's completion can never be correlated — it is stuck (the customer's bug).
        ProcessInstance piA = runtimeService.startProcessInstanceByKey("auto-ambiguous", Map.of("branch", "a"));
        assertThat(messageSubscriptionCount(piA.getId())).isEqualTo(1);
        assertThat(runtimeService.createVariableInstanceQuery()
                .processInstanceIdIn(piA.getId()).variableName(EpistolaProcessVariables.WAIT_FOR).list())
                .as("branch A's catch event got no token (the resolver pinned it to resultB)").isEmpty();
        assertThat(correlationService.correlateCompletion(TENANT, "req-a", "COMPLETED", "doc-a", null))
                .as("branch A cannot correlate — stuck").isEqualTo(0);
        assertThat(messageSubscriptionCount(piA.getId())).as("branch A still waiting").isEqualTo(1);

        // Run branch B (writes resultB, the variable the catch event resolved to): it correlates fine.
        ProcessInstance piB = runtimeService.startProcessInstanceByKey("auto-ambiguous", Map.of("branch", "b"));
        assertThat(correlationService.correlateCompletion(TENANT, "req-b", "COMPLETED", "doc-b", null))
                .as("branch B correlates — its variable is the one pinned").isEqualTo(1);
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
