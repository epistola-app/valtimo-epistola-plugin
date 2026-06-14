package app.epistola.valtimo.download;

import app.epistola.valtimo.service.download.ProcessVariableStorageStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * End-to-end engine test of the download flow that reproduces the two bugs this work fixed: the
 * {@code EpistolaDocumentGenerated} catch event stalling, and the task-variable serialization failure
 * on task open. See {@code docs/adr/0001-download-document-content-storage.md}.
 *
 * <p>Runs a real Operaton engine (standalone, in-memory H2 — deliberately NOT the full Valtimo
 * Spring context, which has an unrelated repository-boot issue). It deploys a minimal
 * {@code start → message catch (asyncAfter) → service task (stores via a storage strategy) → user
 * task} process and asserts: (1) the process waits at the catch event; (2) correlation commits at the
 * {@code asyncAfter} boundary — a job is created and the downstream task has not run yet, so a
 * downstream failure can no longer roll the correlation back; (3) after the job runs, the user task
 * is reached with the output variable set; (4) the task variables serialize cleanly via Jackson
 * (exactly what Valtimo's task-detail endpoint does on task open).
 */
class DownloadDocumentFlowIntegrationTest {

    private static final String PROCESS_KEY = "epistola-download-flow-test";
    private static final String MESSAGE = "EpistolaDocumentGenerated";
    private static final String OUTPUT_VARIABLE = "documentContent";

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs_dl_test">
              <bpmn:message id="msg_docgen" name="EpistolaDocumentGenerated" />
              <bpmn:process id="epistola-download-flow-test" isExecutable="true">
                <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait" />
                <bpmn:intermediateCatchEvent id="wait" camunda:asyncAfter="true">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                  <bpmn:messageEventDefinition id="med" messageRef="msg_docgen" />
                </bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="download" />
                <bpmn:serviceTask id="download" camunda:delegateExpression="${epistolaTestStoreDelegate}">
                  <bpmn:incoming>f2</bpmn:incoming>
                  <bpmn:outgoing>f3</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="f3" sourceRef="download" targetRef="review" />
                <bpmn:userTask id="review">
                  <bpmn:incoming>f3</bpmn:incoming>
                  <bpmn:outgoing>f4</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:sequenceFlow id="f4" sourceRef="review" targetRef="end" />
                <bpmn:endEvent id="end"><bpmn:incoming>f4</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private ProcessEngine processEngine;
    private RuntimeService runtimeService;
    private TaskService taskService;
    private ManagementService managementService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Stands in for the download-document service task: stores an 8 KB document (its Base64 would
        // exceed Operaton's varchar(4000)) via the real ProcessVariableStorageStrategy.
        byte[] content = new byte[8192];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 256);
        }
        ProcessVariableStorageStrategy strategy = new ProcessVariableStorageStrategy();
        JavaDelegate storeDelegate = execution -> strategy.store(execution, "test-doc", content, OUTPUT_VARIABLE);

        ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        configuration.setJobExecutorActivate(false); // execute jobs manually for determinism
        configuration.setEnforceHistoryTimeToLive(false); // no TTL needed for an in-memory test model
        configuration.setBeans(Map.of("epistolaTestStoreDelegate", storeDelegate));
        processEngine = configuration.buildProcessEngine();

        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();
        managementService = processEngine.getManagementService();

        processEngine.getRepositoryService().createDeployment()
                .addString("epistola-download-flow-test.bpmn", BPMN)
                .deploy();
    }

    @AfterEach
    void tearDown() {
        if (processEngine != null) {
            processEngine.close();
        }
    }

    @Test
    void catchEventCompletesViaAsyncJob_andTaskVariablesSerialize() throws Exception {
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                PROCESS_KEY, Map.of("epistolaResult", "test-doc"));

        // (1) The process is parked at the message catch event.
        assertThat(messageSubscriptionExists(pi)).as("waits at the EpistolaDocumentGenerated catch event").isTrue();
        assertThat(reviewTask(pi)).isNull();

        // (2) Correlation commits at the asyncAfter boundary: a job exists and the download task has
        // NOT run yet — so a failure downstream cannot roll the correlation back.
        runtimeService.createMessageCorrelation(MESSAGE).processInstanceId(pi.getId()).correlate();
        Job job = managementService.createJobQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(job).as("asyncAfter on the catch event creates a transaction boundary (job)").isNotNull();
        assertThat(reviewTask(pi)).as("continuation has not run before the job executes").isNull();
        assertThat(messageSubscriptionExists(pi)).as("the message was consumed").isFalse();

        // (3) Run the async continuation: download (stores the output variable) → user task.
        managementService.executeJob(job.getId());
        Task review = reviewTask(pi);
        assertThat(review).as("reaches the review user task").isNotNull();

        Map<String, Object> variables = taskService.getVariables(review.getId());
        assertThat(variables).containsKey(OUTPUT_VARIABLE);
        assertThat(variables.get(OUTPUT_VARIABLE)).isInstanceOf(byte[].class);

        // (4) Regression: the variable map serializes cleanly (this is what the task-detail endpoint
        // does on task open). A byte[] becomes Base64; the old FileValue produced an unserializable
        // ByteArrayInputStream here.
        assertThatCode(() -> objectMapper.writeValueAsString(variables)).doesNotThrowAnyException();
    }

    private boolean messageSubscriptionExists(ProcessInstance pi) {
        return runtimeService.createEventSubscriptionQuery()
                .processInstanceId(pi.getId()).eventName(MESSAGE).count() > 0;
    }

    private Task reviewTask(ProcessInstance pi) {
        return taskService.createTaskQuery()
                .processInstanceId(pi.getId()).taskDefinitionKey("review").singleResult();
    }
}
