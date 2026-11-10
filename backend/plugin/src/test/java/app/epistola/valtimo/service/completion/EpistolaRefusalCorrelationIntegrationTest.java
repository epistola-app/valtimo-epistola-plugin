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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
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
 * A generation Epistola refuses — nothing accepted, no job — is recorded as a failed one, and the
 * process takes the path it has for a failed letter: the catch event after the step finds the result
 * already FAILED when it subscribes and self-heals (ADR 0009, addendum). Driven through the real
 * {@code generate-document} action in a real engine, because the point is what the engine does with
 * it: previously the step threw, was retried, became an incident, and the FAILED variable it had
 * written was rolled back with it.
 */
class EpistolaRefusalCorrelationIntegrationTest {

    private static final String TENANT = "demo";

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs_refused">
              <bpmn:message id="msg" name="EpistolaDocumentGenerated" />
              <bpmn:process id="refused-generation" isExecutable="true" camunda:historyTimeToLive="P1D">
                <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="generate" />
                <bpmn:serviceTask id="generate" camunda:expression="${null}"
                    camunda:class="app.epistola.valtimo.service.completion.EpistolaRefusalCorrelationIntegrationTest$GenerateDelegate">
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
        if (processEngine != null) {
            processEngine.close();
        }
    }

    @Test
    void aRefusedGeneration_takesTheFailurePath_withTheReasonOnTheCase() {
        refuseWith(new EpistolaTargetNotFoundException(new GenerationTargetProblem(Reason.TEMPLATE_NOT_IN_RELEASE,
                "local/demo/brieven", "besluit", null, "staging", "^2.0.0", "2.0.0"),
                "Release brieven@2.0.0 does not contain template 'besluit'", "https://epistola.app/errors/not-found"));

        ProcessInstance pi = startAsTheAppDoes();

        assertThat(activeTaskName(pi)).isEqualTo("Brief mislukt");
        @SuppressWarnings("unchecked")
        Map<String, Object> letter = (Map<String, Object>) runtimeService.getVariable(pi.getId(), "letter");
        assertThat(letter)
                .containsEntry("status", "FAILED")
                .containsEntry("errorReason", "TEMPLATE_NOT_IN_RELEASE")
                .containsEntry("documentId", null);
        assertThat((String) letter.get("errorMessage"))
                .startsWith("Template 'besluit' is not in catalog 'local/demo/brieven' in release 2.0.0")
                .endsWith("Epistola: Release brieven@2.0.0 does not contain template 'besluit'");
        assertThat((String) letter.get("requestId")).startsWith("refused-");
        // Nothing to retry, and nothing left in the engine as a failing job.
        assertThat(processEngine.getManagementService().createJobQuery().count()).isZero();
        assertThat(runtimeService.createIncidentQuery().count()).isZero();
    }

    @Test
    void anyOtherRefusal_isFailedToo_asRequestRefused() {
        refuseWith(new EpistolaApiException("API key has no access to tenant 'demo'", null, 403,
                "https://epistola.app/errors/forbidden", Map.of()));

        ProcessInstance pi = startAsTheAppDoes();

        assertThat(activeTaskName(pi)).isEqualTo("Brief mislukt");
        @SuppressWarnings("unchecked")
        Map<String, Object> letter = (Map<String, Object>) runtimeService.getVariable(pi.getId(), "letter");
        assertThat(letter).containsEntry("errorReason", "REQUEST_REFUSED");
        assertThat((String) letter.get("errorMessage")).contains("HTTP 403").contains("no access");
    }

    @Test
    void aTransientFailure_stillFailsTheStep_soTheEngineRetriesIt() {
        refuseWith(new EpistolaApiException("Service Unavailable", null, 503, null, Map.of()));

        assertThatThrownBy(this::startAsTheAppDoes)
                .hasMessageContaining("Failed to submit document generation request to Epistola");
        assertThat(runtimeService.createProcessInstanceQuery().count()).isZero();
    }

    /**
     * Start the process inside a Spring transaction synchronization scope and run its after-commit
     * callbacks once it returns, as Spring does when the engine's transaction commits. The catch
     * event's self-heal is armed through exactly that hook ({@code EpistolaCatchEventStartListener}),
     * and the standalone engine has no Spring transaction of its own, so without this it would be
     * skipped — and the test would prove nothing about what the app does.
     */
    private ProcessInstance startAsTheAppDoes() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            ProcessInstance pi = runtimeService.startProcessInstanceByKey("refused-generation");
            List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
            TransactionSynchronizationManager.clearSynchronization();
            callbacks.forEach(TransactionSynchronization::afterCommit);
            return pi;
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    private void refuseWith(RuntimeException refusal) {
        when(epistolaService.submitGenerationJob(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any(FileFormat.class), any(), any(), any()))
                .thenThrow(refusal);
    }

    private String activeTaskName(ProcessInstance pi) {
        return processEngine.getTaskService().createTaskQuery().processInstanceId(pi.getId()).singleResult().getName();
    }

    /** The real generate-document action, as a process link would call it. */
    public static class GenerateDelegate implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            plugin.generateDocument(execution, 1, "local/demo/brieven", "besluit", null, null, null, null,
                    "{}", "\"PDF\"", "\"besluit.pdf\"", null, "letter");
        }
    }
}
