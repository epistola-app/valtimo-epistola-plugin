package app.epistola.valtimo.deployment;

import app.epistola.valtimo.web.rest.dto.BpmnValidationViolation;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.processlink.domain.ProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.repository.ProcessDefinitionQuery;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.IntermediateCatchEvent;
import org.operaton.bpm.model.bpmn.instance.ServiceTask;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EpistolaProcessDefinitionValidatorTest {

    private static final String PROCESS_KEY = "permit-confirmation";
    private static final String DEFINITION_ID = "permit-confirmation:1:abc";
    private static final String EVERY_10_MIN_CRON = "0 */10 * * * *";
    private static final long INTERVAL_MS = 600_000L;

    private RepositoryService repositoryService;
    private ProcessLinkService processLinkService;
    private TaskScheduler taskScheduler;
    private ProcessDefinitionQuery query;
    private EpistolaProcessDefinitionValidator validator;

    @BeforeEach
    void setUp() {
        repositoryService = mock(RepositoryService.class);
        processLinkService = mock(ProcessLinkService.class);
        taskScheduler = mock(TaskScheduler.class);
        validator = new EpistolaProcessDefinitionValidator(
                repositoryService, processLinkService, taskScheduler, EVERY_10_MIN_CRON, "UTC");

        ProcessDefinition def = mock(ProcessDefinition.class);
        lenient().when(def.getId()).thenReturn(DEFINITION_ID);
        lenient().when(def.getKey()).thenReturn(PROCESS_KEY);
        lenient().when(def.getName()).thenReturn("Permit Confirmation");

        query = mock(ProcessDefinitionQuery.class);
        lenient().when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
        lenient().when(query.latestVersion()).thenReturn(query);
        lenient().when(query.list()).thenReturn(List.of(def));
    }

    @Test
    void userSetExpression_andNoAsyncBefore_producesNoViolations() {
        BpmnModelInstance model = simpleModel("EpistolaDocumentGenerated");
        // User authored a camunda:expression — Valtimo's auto-injector won't fire,
        // and our validator won't see the platform signature.
        model.<ServiceTask>getModelElementById("generate-confirmation").setOperatonExpression("${null}");
        installBpmn(model);
        installLink("generate-confirmation");

        validator.scan();

        assertThat(validator.getViolations()).isEmpty();
    }

    @Test
    void platformInjectedAsyncAfter_isFlagged() {
        // Mimic Valtimo's deploy-time injection: expression="${null}" + asyncAfter=true,
        // no other handler. This is the actionable signature.
        BpmnModelInstance model = simpleModel("EpistolaDocumentGenerated");
        ServiceTask task = model.getModelElementById("generate-confirmation");
        task.setOperatonExpression("${null}");
        task.setOperatonAsyncAfter(true);
        installBpmn(model);
        installLink("generate-confirmation");

        validator.scan();

        assertThat(validator.getViolations()).singleElement().satisfies(v -> {
            assertThat(v.code()).isEqualTo(BpmnValidationViolation.CODE_PLATFORM_ASYNC_AFTER_ON_SERVICE_TASK);
            assertThat(v.activityId()).isEqualTo("generate-confirmation");
            assertThat(v.processDefinitionKey()).isEqualTo(PROCESS_KEY);
            assertThat(v.message()).contains("camunda:expression");
        });
    }

    @Test
    void asyncAfterAlone_withoutPlatformSignature_isNotFlagged() {
        // asyncAfter=true but expression is null (or anything else) — that's NOT the
        // platform signature; we don't second-guess hand-authored async semantics.
        BpmnModelInstance model = simpleModel("EpistolaDocumentGenerated");
        model.<ServiceTask>getModelElementById("generate-confirmation").setOperatonAsyncAfter(true);
        installBpmn(model);
        installLink("generate-confirmation");

        validator.scan();

        assertThat(validator.getViolations()).isEmpty();
    }

    @Test
    void asyncBeforeOnCatchEvent_isFlagged() {
        BpmnModelInstance model = simpleModel("EpistolaDocumentGenerated");
        model.<ServiceTask>getModelElementById("generate-confirmation").setOperatonExpression("${null}");
        model.<IntermediateCatchEvent>getModelElementById("wait-for-generation").setOperatonAsyncBefore(true);
        installBpmn(model);
        installLink("generate-confirmation");

        validator.scan();

        assertThat(validator.getViolations()).singleElement().satisfies(v -> {
            assertThat(v.code()).isEqualTo(BpmnValidationViolation.CODE_ASYNC_BEFORE_ON_CATCH_EVENT);
            assertThat(v.activityId()).isEqualTo("wait-for-generation");
        });
    }

    @Test
    void noCatchEventReachable_validatorIsSilent() {
        // Variable pattern: the user reads ${epistolaResult.documentId} from a
        // process variable later. No catch event in the immediate forward graph.
        BpmnModelInstance model = Bpmn.createExecutableProcess(PROCESS_KEY)
                .startEvent("start")
                .serviceTask("generate-confirmation")
                .userTask("review-data")
                .endEvent("end")
                .done();
        ServiceTask task = model.getModelElementById("generate-confirmation");
        task.setOperatonExpression("${null}");
        task.setOperatonAsyncAfter(true); // platform-injected
        installBpmn(model);
        installLink("generate-confirmation");

        validator.scan();

        assertThat(validator.getViolations()).isEmpty();
    }

    @Test
    void wrongMessageRef_validatorIsSilent() {
        // The user wrote a catch event for a different message. From the validator's
        // perspective there's no Epistola catch event reachable, so this is just the
        // variable pattern with extra activities.
        BpmnModelInstance model = simpleModel("SomeOtherMessage");
        ServiceTask task = model.getModelElementById("generate-confirmation");
        task.setOperatonExpression("${null}");
        task.setOperatonAsyncAfter(true);
        installBpmn(model);
        installLink("generate-confirmation");

        validator.scan();

        assertThat(validator.getViolations()).isEmpty();
    }

    @Test
    void catchEventBehindGateway_isStillValidated() {
        // generate-document → exclusive-gateway → catch-event. The validator should
        // walk through the gateway and still flag the platform signature.
        BpmnModelInstance model = Bpmn.createExecutableProcess(PROCESS_KEY)
                .startEvent("start")
                .serviceTask("generate-confirmation")
                .exclusiveGateway("split")
                .intermediateCatchEvent("wait-for-generation")
                .message("EpistolaDocumentGenerated")
                .endEvent("end")
                .done();
        ServiceTask task = model.getModelElementById("generate-confirmation");
        task.setOperatonExpression("${null}");
        task.setOperatonAsyncAfter(true);
        installBpmn(model);
        installLink("generate-confirmation");

        validator.scan();

        assertThat(validator.getViolations()).singleElement().satisfies(v ->
                assertThat(v.code()).isEqualTo(BpmnValidationViolation.CODE_PLATFORM_ASYNC_AFTER_ON_SERVICE_TASK));
    }

    @Test
    void userTaskBeforeCatchEvent_breaksTheImmediatePattern() {
        // generate-document → user-task → catch-event. The user task is a wait state
        // that breaks the race — the catch event subscribes long before the result
        // could possibly arrive. Validator stays silent.
        BpmnModelInstance model = Bpmn.createExecutableProcess(PROCESS_KEY)
                .startEvent("start")
                .serviceTask("generate-confirmation")
                .userTask("review-data")
                .intermediateCatchEvent("wait-for-generation")
                .message("EpistolaDocumentGenerated")
                .endEvent("end")
                .done();
        ServiceTask task = model.getModelElementById("generate-confirmation");
        task.setOperatonExpression("${null}");
        task.setOperatonAsyncAfter(true);
        installBpmn(model);
        installLink("generate-confirmation");

        validator.scan();

        assertThat(validator.getViolations()).isEmpty();
    }

    @Test
    void twoGenerateDocumentsReachingOneCatchEventWithDifferentResultVariables_isFlaggedAsAmbiguous() {
        // fork → gen-a → merge → wait ; fork → gen-b → merge → wait. Both generate-documents
        // forward-reach the same catch event, with DIFFERENT result variables — the auto-wiring can
        // pin only one, so the other branch stalls. This is the reproduced customer bug.
        installBpmn(twoSourcesOneCatchEvent());
        installGenerateLinks(new String[]{"gen-a", "gen-b"}, new String[]{"resultA", "resultB"});

        validator.scan();

        assertThat(validator.getViolations()).singleElement().satisfies(v -> {
            assertThat(v.code()).isEqualTo(BpmnValidationViolation.CODE_AMBIGUOUS_CATCH_EVENT);
            assertThat(v.activityId()).isEqualTo("wait-for-generation");
            assertThat(v.message()).contains("gen-a").contains("gen-b")
                    .contains("resultA").contains("resultB")
                    .contains("SAME resultProcessVariable");
        });
    }

    @Test
    void twoGenerateDocumentsReachingOneCatchEventWithTheSameResultVariable_isNotFlagged() {
        // The fix for an exclusive split that merges: both branches write the SAME result variable, so
        // the auto-wiring resolves the shared catch event unambiguously regardless of which branch ran.
        installBpmn(twoSourcesOneCatchEvent());
        installGenerateLinks(new String[]{"gen-a", "gen-b"}, new String[]{"epistolaResult", "epistolaResult"});

        validator.scan();

        assertThat(validator.getViolations()).isEmpty();
    }

    @Test
    void noMatchingLink_skipsModel() {
        installBpmn(simpleModel("EpistolaDocumentGenerated"));
        when(processLinkService.getProcessLinks(DEFINITION_ID)).thenReturn(List.<ProcessLink>of());

        validator.scan();

        assertThat(validator.getViolations()).isEmpty();
    }

    @Test
    void lastCheckedAt_isNullBeforeFirstScan_andSetAfterwards() {
        assertThat(validator.getLastCheckedAt()).isNull();
        assertThat(validator.getRefreshIntervalMs()).isEqualTo(INTERVAL_MS);

        installBpmn(simpleModel("EpistolaDocumentGenerated"));
        installLink("generate-confirmation");
        validator.scan();

        assertThat(validator.getLastCheckedAt()).isNotNull();
    }

    @Test
    void refreshIntervalMs_isDerivedFromTheCronSchedule() {
        EpistolaProcessDefinitionValidator fiveMin = new EpistolaProcessDefinitionValidator(
                repositoryService, processLinkService, taskScheduler, "0 */5 * * * *", "UTC");
        assertThat(fiveMin.getRefreshIntervalMs()).isEqualTo(300_000L);
    }

    @Test
    void invalidCron_fallsBackToTenMinutes() {
        EpistolaProcessDefinitionValidator bad = new EpistolaProcessDefinitionValidator(
                repositoryService, processLinkService, taskScheduler, "not-a-cron", "UTC");
        assertThat(bad.getRefreshIntervalMs()).isEqualTo(INTERVAL_MS);
    }

    @Test
    void scanOnStartup_runsAScanImmediatelyWithoutJitter() {
        installBpmn(simpleModel("EpistolaDocumentGenerated"));
        installLink("generate-confirmation");

        validator.scanOnStartup();

        assertThat(validator.getLastCheckedAt()).isNotNull();
        verifyNoInteractions(taskScheduler);
    }

    @Test
    void nextJitterMs_isWithinOneToTwentyFiveSeconds() {
        for (int i = 0; i < 1000; i++) {
            assertThat(validator.nextJitterMs()).isBetween(1_000L, 25_000L);
        }
    }

    @Test
    void scheduledScan_defersTheScanViaTheSchedulerWithJitter() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> runAt = ArgumentCaptor.forClass(Instant.class);
        Instant before = Instant.now();

        validator.scheduledScan();

        // The cron tick does not scan inline — it schedules the work 1–25s out.
        verify(taskScheduler).schedule(task.capture(), runAt.capture());
        long delayMs = Duration.between(before, runAt.getValue()).toMillis();
        assertThat(delayMs).isBetween(1_000L, 26_000L);

        // Running the deferred task performs the actual scan.
        installBpmn(simpleModel("EpistolaDocumentGenerated"));
        installLink("generate-confirmation");
        task.getValue().run();
        assertThat(validator.getLastCheckedAt()).isNotNull();
    }

    @Test
    void unchangedVersionAndLinks_parsesBpmnModelOnceAcrossScans() {
        installBpmn(simpleModel("EpistolaDocumentGenerated"));
        installLink("generate-confirmation");

        validator.scan();
        validator.scan();

        // Second scan is a cache hit — the expensive model parse must not run again.
        verify(repositoryService, times(1)).getBpmnModelInstance(DEFINITION_ID);
    }

    @Test
    void newDeployedVersion_reparsesUnderNewDefinitionId() {
        installBpmn(simpleModel("EpistolaDocumentGenerated"));
        installLink("generate-confirmation");
        validator.scan();

        // Redeploy: latestVersion() now returns a new version with a fresh id.
        String newId = "permit-confirmation:2:def";
        ProcessDefinition def2 = mock(ProcessDefinition.class);
        lenient().when(def2.getId()).thenReturn(newId);
        lenient().when(def2.getKey()).thenReturn(PROCESS_KEY);
        lenient().when(def2.getName()).thenReturn("Permit Confirmation");
        when(query.list()).thenReturn(List.of(def2));
        when(repositoryService.getBpmnModelInstance(newId))
                .thenReturn(simpleModel("EpistolaDocumentGenerated"));
        PluginProcessLink link = mock(PluginProcessLink.class);
        lenient().when(link.getId()).thenReturn(UUID.randomUUID());
        lenient().when(link.getActivityId()).thenReturn("generate-confirmation");
        lenient().when(link.getPluginActionDefinitionKey()).thenReturn("epistola-generate-document");
        when(processLinkService.getProcessLinks(newId)).thenReturn(List.<ProcessLink>of(link));

        validator.scan();

        verify(repositoryService, times(1)).getBpmnModelInstance(newId);
    }

    @Test
    void changedLinksOnSameVersion_reparsesBpmnModel() {
        installBpmn(simpleModel("EpistolaDocumentGenerated"));
        installLink("generate-confirmation");
        validator.scan();

        // Same deployed version id, but the generate-document links changed — the cached
        // result is no longer valid, so the model is re-parsed.
        installLink("some-other-activity");
        validator.scan();

        verify(repositoryService, times(2)).getBpmnModelInstance(DEFINITION_ID);
    }

    private void installBpmn(BpmnModelInstance model) {
        when(repositoryService.getBpmnModelInstance(DEFINITION_ID)).thenReturn(model);
    }

    private void installLink(String activityId) {
        PluginProcessLink link = mock(PluginProcessLink.class);
        lenient().when(link.getId()).thenReturn(UUID.randomUUID());
        lenient().when(link.getActivityId()).thenReturn(activityId);
        lenient().when(link.getPluginActionDefinitionKey()).thenReturn("epistola-generate-document");
        when(processLinkService.getProcessLinks(DEFINITION_ID)).thenReturn(List.<ProcessLink>of(link));
    }

    private void installLinks(String... activityIds) {
        List<ProcessLink> links = Arrays.stream(activityIds).map(activityId -> {
            PluginProcessLink link = mock(PluginProcessLink.class);
            lenient().when(link.getId()).thenReturn(UUID.randomUUID());
            lenient().when(link.getActivityId()).thenReturn(activityId);
            lenient().when(link.getPluginActionDefinitionKey()).thenReturn("epistola-generate-document");
            return (ProcessLink) link;
        }).toList();
        when(processLinkService.getProcessLinks(DEFINITION_ID)).thenReturn(links);
    }

    /** Install generate-document links, each carrying its own {@code resultProcessVariable}. */
    private void installGenerateLinks(String[] activityIds, String[] resultVars) {
        List<ProcessLink> links = new java.util.ArrayList<>();
        for (int i = 0; i < activityIds.length; i++) {
            PluginProcessLink link = mock(PluginProcessLink.class);
            lenient().when(link.getId()).thenReturn(UUID.randomUUID());
            lenient().when(link.getActivityId()).thenReturn(activityIds[i]);
            lenient().when(link.getPluginActionDefinitionKey()).thenReturn("epistola-generate-document");
            ObjectNode props = JsonNodeFactory.instance.objectNode();
            props.put("resultProcessVariable", resultVars[i]);
            lenient().when(link.getActionProperties()).thenReturn(props);
            links.add(link);
        }
        when(processLinkService.getProcessLinks(DEFINITION_ID)).thenReturn(links);
    }

    /** fork → gen-a → merge → wait ; fork → gen-b → merge → wait (two sources, one shared catch event). */
    private static BpmnModelInstance twoSourcesOneCatchEvent() {
        return Bpmn.createExecutableProcess(PROCESS_KEY)
                .startEvent("start")
                .parallelGateway("fork")
                .serviceTask("gen-a")
                .parallelGateway("merge")
                .intermediateCatchEvent("wait-for-generation")
                .message("EpistolaDocumentGenerated")
                .endEvent("end")
                .moveToNode("fork")
                .serviceTask("gen-b")
                .connectTo("merge")
                .done();
    }

    private static BpmnModelInstance simpleModel(String messageName) {
        return Bpmn.createExecutableProcess(PROCESS_KEY)
                .startEvent("start")
                .serviceTask("generate-confirmation")
                .intermediateCatchEvent("wait-for-generation")
                .message(messageName)
                .endEvent("end")
                .done();
    }
}
