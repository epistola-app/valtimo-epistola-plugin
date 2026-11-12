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
import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GenerationTargetProblem;
import app.epistola.valtimo.domain.GenerationTargetProblem.Reason;
import app.epistola.valtimo.mapping.EvaluationContext;
import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.service.EpistolaApiException;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.EpistolaTargetNotFoundException;
import app.epistola.valtimo.service.download.DocumentStorageStrategy;
import app.epistola.valtimo.domain.DocumentStorageTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.document.service.DocumentService;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.processlink.domain.ProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A generation that cannot start — Epistola refused it, or an expression produced nothing — raises
 * an incident at the step: nothing is lost, it is visible, and once the cause is fixed (the
 * configuration or the case's data) retrying it runs the step again with what was corrected (ADR
 * 0009, third addendum). Driven through the real {@code generate-document} action in a real engine,
 * behind the async boundary Valtimo deploys, because what matters is what the engine does with it.
 *
 * <p>The incident's own message is Valtimo's, not the step's: Valtimo calls the action by reflection
 * and does not unwrap what it throws (see {@link GenerateDelegate}), so the summary line reads
 * {@value #VALTIMO_WRAPPER} and the step's diagnosis is in the incident's stack trace. That is a
 * Valtimo defect, written down in {@code docs/valtimo-upstream-issues.md}; these tests pin both
 * halves, so they say so when Valtimo fixes it.
 */
class EpistolaRefusalIncidentIntegrationTest {

    private static final String TENANT = "demo";

    /** What Spring's event-listener adapter says around the action's exception. */
    private static final String VALTIMO_WRAPPER = "Failed to invoke event listener method";

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs_refused">
              <bpmn:message id="msg" name="EpistolaDocumentGenerated" />
              <bpmn:process id="refused-generation" isExecutable="true" camunda:historyTimeToLive="P1D">
                <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="generate" />
                <bpmn:serviceTask id="generate" camunda:asyncBefore="true"
                    camunda:class="app.epistola.valtimo.service.completion.EpistolaRefusalIncidentIntegrationTest$GenerateDelegate">
                  <bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="f2" sourceRef="generate" targetRef="wait" />
                <bpmn:intermediateCatchEvent id="wait">
                  <bpmn:incoming>f2</bpmn:incoming><bpmn:outgoing>f3</bpmn:outgoing>
                  <bpmn:messageEventDefinition messageRef="msg" />
                </bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="f3" sourceRef="wait" targetRef="outcome" />
                <bpmn:exclusiveGateway id="outcome" default="toDone">
                  <bpmn:incoming>f3</bpmn:incoming><bpmn:outgoing>toFailed</bpmn:outgoing><bpmn:outgoing>toDone</bpmn:outgoing>
                </bpmn:exclusiveGateway>
                <bpmn:sequenceFlow id="toFailed" sourceRef="outcome" targetRef="failed">
                  <bpmn:conditionExpression>${letter.status == 'FAILED'}</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="toDone" sourceRef="outcome" targetRef="done" />
                <bpmn:userTask id="failed" name="Brief mislukt"><bpmn:incoming>toFailed</bpmn:incoming></bpmn:userTask>
                <bpmn:userTask id="done" name="Brief klaar"><bpmn:incoming>toDone</bpmn:incoming></bpmn:userTask>
              </bpmn:process>
            </bpmn:definitions>
            """;

    /** The plugin the BPMN's delegate calls; set per test. */
    static EpistolaPlugin plugin;

    private ProcessEngine processEngine;
    private RuntimeService runtimeService;
    private EpistolaService epistolaService;

    @BeforeEach
    void setUp() {
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
        configuration.setJdbcUrl("jdbc:h2:mem:refused" + System.nanoTime());
        configuration.setCustomPostBPMNParseListeners(new ArrayList<>(List.of(new EpistolaCatchEventParseListener(deferred))));
        processEngine = configuration.buildProcessEngine();
        runtimeService = processEngine.getRuntimeService();

        ObjectNode props = JsonNodeFactory.instance.objectNode();
        props.put("resultProcessVariable", "letter");
        PluginProcessLink link = mock(PluginProcessLink.class);
        when(link.getActivityId()).thenReturn("generate");
        when(link.getPluginActionDefinitionKey()).thenReturn("epistola-generate-document");
        when(link.getActionProperties()).thenReturn(props);
        ProcessLinkService processLinkService = mock(ProcessLinkService.class);
        when(processLinkService.getProcessLinks(anyString())).thenReturn(List.<ProcessLink>of(link));
        startRef.set(new EpistolaCatchEventStartListener(
                new EpistolaCatchEventLinkResolver(processEngine.getRepositoryService(), processLinkService),
                new EpistolaMessageCorrelationService(runtimeService)));

        epistolaService = mock(EpistolaService.class);
        JsonataMappingService mappings = mock(JsonataMappingService.class);
        when(mappings.evaluate(any())).thenReturn(Map.of());
        when(mappings.evaluateScalar(any())).thenAnswer(invocation -> {
            EvaluationContext context = invocation.getArgument(0);
            return switch (context.getExpression()) {
                case "\"PDF\"" -> "PDF";
                case "\"besluit.pdf\"" -> "besluit.pdf";
                default -> null;
            };
        });
        plugin = new EpistolaPlugin(epistolaService, mock(ObjectMapper.class), mappings, mock(DocumentService.class),
                mock(EpistolaResultCollectorRunner.class), new EnumMap<DocumentStorageTarget, DocumentStorageStrategy>(DocumentStorageTarget.class));
        ReflectionTestUtils.setField(plugin, "baseUrl", "http://epistola");
        ReflectionTestUtils.setField(plugin, "apiKey", "key");
        ReflectionTestUtils.setField(plugin, "tenantId", TENANT);

        processEngine.getRepositoryService().createDeployment().addString("refused.bpmn", BPMN).deploy();
    }

    @AfterEach
    void tearDown() {
        plugin = null;
        GenerateDelegate.environment = null;
        if (processEngine != null) {
            processEngine.close();
        }
    }

    @Test
    void aRefusal_raisesAnIncidentAtTheStep_sayingWhatIsMissing() {
        refuseWith(new EpistolaTargetNotFoundException(new GenerationTargetProblem(Reason.TEMPLATE_NOT_IN_RELEASE,
                "local/demo/brieven", "besluit", null, "staging", "^2.0.0", "2.0.0"),
                "Release brieven@2.0.0 does not contain template 'besluit' (case of Jansen)",
                "https://epistola.app/errors/not-found"));

        ProcessInstance pi = startAsTheAppDoes();
        org.operaton.bpm.engine.runtime.Incident incident = failUntilIncident(pi);

        assertThat(incident.getActivityId()).isEqualTo("generate");
        assertThat(incident.getIncidentMessage()).isEqualTo(VALTIMO_WRAPPER);
        assertThat(diagnosisOf(pi))
                .contains("Template 'besluit' is not in catalog 'local/demo/brieven' in release 2.0.0")
                .doesNotContain("Jansen");
        assertThat(runtimeService.getActiveActivityIds(pi.getId())).containsExactly("generate");
        assertThat(runtimeService.getVariable(pi.getId(), "letter")).isNull();
    }

    @Test
    void onceTheCauseIsFixed_retryingTheIncident_runsTheStepAgain() {
        refuseWith(new EpistolaTargetNotFoundException(new GenerationTargetProblem(Reason.NOT_DEPLOYED,
                "local/demo/brieven", "besluit", null, "staging", null, null), null, null));
        ProcessInstance pi = startAsTheAppDoes();
        failUntilIncident(pi);

        // Someone deploys the catalog to staging; the admin retries the incident.
        org.mockito.Mockito.reset(epistolaService);
        when(epistolaService.submitGenerationJob(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any(FileFormat.class), any(), any(), any()))
                .thenReturn(app.epistola.valtimo.domain.GenerationJobResult.builder()
                        .requestId("01a1c60d-0000-0000-0000-000000000001").status("PENDING").build());
        org.operaton.bpm.engine.runtime.Job job = jobOf(pi);
        processEngine.getManagementService().setJobRetries(job.getId(), 1);
        processEngine.getManagementService().executeJob(job.getId());

        assertThat(runtimeService.getActiveActivityIds(pi.getId())).containsExactly("wait");
        assertThat(runtimeService.createIncidentQuery().processInstanceId(pi.getId()).count()).isZero();
        @SuppressWarnings("unchecked")
        Map<String, Object> letter = (Map<String, Object>) runtimeService.getVariable(pi.getId(), "letter");
        assertThat(letter).containsEntry("status", "PENDING");
    }

    @Test
    void anEnvironmentExpressionProducingNothing_raisesAnIncident_withoutAskingEpistola() {
        // `someEnv` without quotes: a path to nothing, which once rendered in the default unnoticed.
        GenerateDelegate.environment = "someEnv";

        ProcessInstance pi = startAsTheAppDoes();
        failUntilIncident(pi);

        assertThat(diagnosisOf(pi)).contains("expression='someEnv'").contains("produced no value");
        org.mockito.Mockito.verifyNoInteractions(epistolaService);
    }

    @Test
    void aTransientFailure_isRetried_asAnyFailingStepIs() {
        refuseWith(new EpistolaApiException("Service Unavailable", null, 503, null, Map.of()));

        ProcessInstance pi = startAsTheAppDoes();
        failUntilIncident(pi);

        assertThat(diagnosisOf(pi)).contains("Failed to submit document generation request to Epistola");
    }

    /** Run the step's job until the engine gives up and raises an incident, as the job executor would. */
    private org.operaton.bpm.engine.runtime.Incident failUntilIncident(ProcessInstance pi) {
        for (int attempt = 0; attempt < 5 && runtimeService.createIncidentQuery()
                .processInstanceId(pi.getId()).count() == 0; attempt++) {
            org.operaton.bpm.engine.runtime.Job job = jobOf(pi);
            try {
                processEngine.getManagementService().executeJob(job.getId());
            } catch (RuntimeException expected) {
                // The step failed; the engine records it on the job.
            }
        }
        return runtimeService.createIncidentQuery().processInstanceId(pi.getId()).singleResult();
    }

    /** The failed job's stack trace: what Cockpit shows under the incident. */
    private String diagnosisOf(ProcessInstance pi) {
        return processEngine.getManagementService().getJobExceptionStacktrace(jobOf(pi).getId());
    }

    private org.operaton.bpm.engine.runtime.Job jobOf(ProcessInstance pi) {
        return processEngine.getManagementService().createJobQuery().processInstanceId(pi.getId()).singleResult();
    }

    private ProcessInstance startAsTheAppDoes() {
        return runtimeService.startProcessInstanceByKey("refused-generation");
    }

    private void refuseWith(RuntimeException refusal) {
        when(epistolaService.submitGenerationJob(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any(FileFormat.class), any(), any(), any()))
                .thenThrow(refusal);
    }

    private String activeTaskName(ProcessInstance pi) {
        return processEngine.getTaskService().createTaskQuery().processInstanceId(pi.getId()).singleResult().getName();
    }

    /**
     * The real generate-document action, called the way Valtimo calls a process link's action: by
     * reflection ({@code PluginService.invoke}), from a Spring event listener
     * ({@code ProcessLinkServiceTaskStartListener}). Neither unwraps, so what the action throws reaches
     * the engine as an {@link InvocationTargetException} inside an {@link UndeclaredThrowableException}
     * — which is why every incident reads "Failed to invoke event listener method". Calling the
     * action directly here is how that went unseen.
     */
    public static class GenerateDelegate implements JavaDelegate {
        /** The link's environment expression; none by default. */
        static String environment;

        @Override
        public void execute(DelegateExecution execution) throws Exception {
            Method action = Arrays.stream(EpistolaPlugin.class.getMethods())
                    .filter(method -> method.getName().equals("generateDocument"))
                    .findFirst().orElseThrow();
            try {
                action.invoke(plugin, execution, 1, "local/demo/brieven", "besluit", null, null, environment, null,
                        "{}", "\"PDF\"", "\"besluit.pdf\"", null, "letter");
            } catch (InvocationTargetException e) {
                // What Spring's ApplicationListenerMethodAdapter does with a checked exception.
                throw new UndeclaredThrowableException(e, "Failed to invoke event listener method");
            }
        }
    }
}
