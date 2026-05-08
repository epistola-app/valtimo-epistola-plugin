package app.epistola.valtimo.deployment;

import app.epistola.valtimo.domain.EpistolaProcessVariables;
import app.epistola.valtimo.web.rest.dto.BpmnValidationViolation;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.BusinessRuleTask;
import org.operaton.bpm.model.bpmn.instance.CatchEvent;
import org.operaton.bpm.model.bpmn.instance.EventDefinition;
import org.operaton.bpm.model.bpmn.instance.FlowNode;
import org.operaton.bpm.model.bpmn.instance.Gateway;
import org.operaton.bpm.model.bpmn.instance.IntermediateCatchEvent;
import org.operaton.bpm.model.bpmn.instance.Message;
import org.operaton.bpm.model.bpmn.instance.MessageEventDefinition;
import org.operaton.bpm.model.bpmn.instance.ReceiveTask;
import org.operaton.bpm.model.bpmn.instance.ScriptTask;
import org.operaton.bpm.model.bpmn.instance.SequenceFlow;
import org.operaton.bpm.model.bpmn.instance.ServiceTask;
import org.operaton.bpm.model.bpmn.instance.UserTask;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Validates that every BPMN process definition using the {@code generate-document}
 * Epistola plugin action is structured to keep the result-correlation race-safe.
 *
 * <p>What the validator does (and explicitly does not do):
 * <ul>
 *   <li>It only emits warnings when the BPMN uses the catch-event pattern — the
 *       {@code generate-document} service task's forward graph reaches an
 *       {@link EpistolaProcessVariables#MESSAGE_NAME} {@link IntermediateCatchEvent}
 *       before any other wait state. The forward walk follows direct sequence flows
 *       and traverses through all gateway types (exclusive, inclusive, parallel,
 *       complex, event-based) without evaluating their conditions.</li>
 *   <li>If no such catch event is reachable, the validator is silent — that BPMN is
 *       using the variable pattern (read the result from a process variable later)
 *       and has no race exposure.</li>
 *   <li>It never modifies the BPMN. Operators remediate by editing the source model
 *       in their authoring tool.</li>
 * </ul>
 *
 * <p>Two violations:
 * <ul>
 *   <li>{@link BpmnValidationViolation#CODE_PLATFORM_ASYNC_AFTER_ON_SERVICE_TASK} —
 *       the deployed service task has the platform-injected signature
 *       ({@code expression="${null}"} + {@code asyncAfter="true"}, no other handler),
 *       which Valtimo applies whenever the user didn't author a {@code camunda:expression}.
 *       The asyncAfter widens the commit-vs-collector race; remediation is to add
 *       any {@code camunda:expression} to the service task.</li>
 *   <li>{@link BpmnValidationViolation#CODE_ASYNC_BEFORE_ON_CATCH_EVENT} — the catch
 *       event has {@code camunda:asyncBefore} set; same race-widening effect.</li>
 * </ul>
 *
 * <p>Violations are logged at WARN and surfaced via {@link #getViolations()} for the
 * admin UI. Validation does not fail deployment — Valtimo redeploys many definitions
 * on every boot and a hard fail would be operationally hostile.
 *
 * <p>Scanning runs on a fixed-delay schedule (default 10 minutes) with a small initial
 * delay so the BPMN engine is ready by the first tick. The result is a point-in-time
 * snapshot stored in an {@link AtomicReference} for lock-free reads.
 */
@Slf4j
@RequiredArgsConstructor
public class EpistolaProcessDefinitionValidator {

    private static final String GENERATE_DOCUMENT_ACTION_KEY = "generate-document";

    private final RepositoryService repositoryService;
    private final ProcessLinkService processLinkService;

    private final AtomicReference<List<BpmnValidationViolation>> violations =
            new AtomicReference<>(Collections.emptyList());

    public List<BpmnValidationViolation> getViolations() {
        return violations.get();
    }

    @Scheduled(
            initialDelayString = "${epistola.validator.initial-delay-ms:30000}",
            fixedDelayString = "${epistola.validator.interval-ms:600000}"
    )
    public void scan() {
        List<BpmnValidationViolation> found = new ArrayList<>();
        try {
            List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                    .latestVersion()
                    .list();
            for (ProcessDefinition definition : definitions) {
                try {
                    found.addAll(validate(definition));
                } catch (Exception e) {
                    log.debug("Skipped validation for process definition {}: {}",
                            definition.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to enumerate process definitions for race-safety validation: {}", e.getMessage());
            return;
        }

        violations.set(Collections.unmodifiableList(found));
        if (!found.isEmpty()) {
            log.warn("Epistola race-safety validation found {} violation(s):", found.size());
            for (BpmnValidationViolation v : found) {
                log.warn("  [{}] processDefinition={} activity={} — {}",
                        v.code(), v.processDefinitionKey(), v.activityId(), v.message());
            }
        } else {
            log.debug("Epistola race-safety validation: no violations");
        }
    }

    private List<BpmnValidationViolation> validate(ProcessDefinition definition) {
        List<PluginProcessLink> links = processLinkService.getProcessLinks(definition.getId()).stream()
                .filter(PluginProcessLink.class::isInstance)
                .map(PluginProcessLink.class::cast)
                .filter(link -> GENERATE_DOCUMENT_ACTION_KEY.equals(link.getPluginActionDefinitionKey()))
                .toList();
        if (links.isEmpty()) {
            return List.of();
        }

        BpmnModelInstance model = repositoryService.getBpmnModelInstance(definition.getId());
        if (model == null) {
            return List.of();
        }

        String displayName = definition.getName() != null ? definition.getName() : definition.getKey();
        List<BpmnValidationViolation> result = new ArrayList<>();

        for (PluginProcessLink link : links) {
            String activityId = link.getActivityId();
            ServiceTask serviceTask = model.getModelElementById(activityId) instanceof ServiceTask st ? st : null;
            if (serviceTask == null) {
                continue;
            }

            IntermediateCatchEvent reachableCatchEvent = findReachableEpistolaCatchEvent(serviceTask);
            if (reachableCatchEvent == null) {
                // Variable-pattern BPMN — no catch event in the immediate forward graph.
                // Race exposure is N/A. Validator stays silent.
                continue;
            }

            // Platform-injected asyncAfter check: signature is expression="${null}"
            // AND asyncAfter=true AND no other handler (class/type/delegateExpression).
            // If the user authored their own camunda:expression, none of these match —
            // even if they wrote "${null}" themselves, that's a deliberate opt-out and
            // we don't second-guess it (the if-condition skips silently because the
            // user-set value is identical to what we'd flag, but the *reason* it's
            // there is intent, not platform default — there's no way to tell apart
            // and that's fine: either way Valtimo's auto-injector ran or didn't, and
            // the resulting deployed model is what runs).
            if (isPlatformInjectedAsyncAfter(serviceTask)) {
                result.add(violation(definition.getKey(), displayName, activityId,
                        BpmnValidationViolation.CODE_PLATFORM_ASYNC_AFTER_ON_SERVICE_TASK,
                        "this service task flows into the " + EpistolaProcessVariables.MESSAGE_NAME
                                + " catch event but has no camunda:expression set — Valtimo auto-enabled "
                                + "camunda:asyncAfter=\"true\" on it, opening a transactional race that can "
                                + "drop result correlations. Add camunda:expression=\"${null}\" "
                                + "(or any other expression) to the service task in your BPMN authoring "
                                + "tool to opt out."));
            }

            if (reachableCatchEvent.isOperatonAsyncBefore()) {
                result.add(violation(definition.getKey(), displayName, reachableCatchEvent.getId(),
                        BpmnValidationViolation.CODE_ASYNC_BEFORE_ON_CATCH_EVENT,
                        "remove camunda:asyncBefore on the " + EpistolaProcessVariables.MESSAGE_NAME
                                + " catch event — it must subscribe in the same transaction as the service-task "
                                + "execution to keep the result-collector race-safe."));
            }
        }

        return result;
    }

    /**
     * Walk the BPMN forward graph from {@code start}, following sequence flows and
     * traversing through gateways. Returns the first reachable {@link IntermediateCatchEvent}
     * with our message ref, or {@code null} if a wait state breaks every branch first.
     *
     * <p>Conditions on gateway outflows are NOT evaluated — if any branch from any
     * gateway can lead to our catch event, it counts. Conservative on purpose: we'd
     * rather warn on a path that's unreachable at runtime than silently miss a real race.
     */
    private static IntermediateCatchEvent findReachableEpistolaCatchEvent(ServiceTask start) {
        Set<String> visited = new HashSet<>();
        Deque<FlowNode> queue = new ArrayDeque<>();
        for (SequenceFlow sf : start.getOutgoing()) {
            queue.add(sf.getTarget());
        }

        while (!queue.isEmpty()) {
            FlowNode node = queue.pop();
            if (node == null || !visited.add(node.getId())) {
                continue;
            }

            if (node instanceof IntermediateCatchEvent ice) {
                if (matchesEpistolaMessage(ice)) {
                    return ice;
                }
                // Different message / timer / signal — also a wait state, break this branch.
                continue;
            }

            if (node instanceof Gateway) {
                for (SequenceFlow sf : node.getOutgoing()) {
                    queue.add(sf.getTarget());
                }
                continue;
            }

            // Wait states (other than the catch event we're hunting for): break this branch.
            if (node instanceof UserTask || node instanceof ReceiveTask) {
                continue;
            }

            // Synchronous activities also break: the wait happens after them, race semantics
            // shift. We don't follow past them.
            if (node instanceof ServiceTask
                    || node instanceof ScriptTask
                    || node instanceof BusinessRuleTask) {
                continue;
            }

            // Anything else (subprocess, end event, throw event, etc.) — don't traverse.
        }

        return null;
    }

    private static boolean isPlatformInjectedAsyncAfter(ServiceTask task) {
        return task.isOperatonAsyncAfter()
                && "${null}".equals(task.getOperatonExpression())
                && task.getOperatonClass() == null
                && task.getOperatonType() == null
                && task.getOperatonDelegateExpression() == null;
    }

    private static boolean matchesEpistolaMessage(CatchEvent catchEvent) {
        for (EventDefinition def : catchEvent.getEventDefinitions()) {
            if (def instanceof MessageEventDefinition med) {
                Message message = med.getMessage();
                if (message != null && EpistolaProcessVariables.MESSAGE_NAME.equals(message.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BpmnValidationViolation violation(String key, String name, String activityId,
                                                     String code, String message) {
        return new BpmnValidationViolation(key, name, activityId, code, message);
    }
}
