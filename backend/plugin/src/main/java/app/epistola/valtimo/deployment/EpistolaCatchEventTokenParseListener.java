package app.epistola.valtimo.deployment;

import app.epistola.valtimo.domain.EpistolaProcessVariables;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.impl.pvm.PvmActivity;
import org.operaton.bpm.engine.impl.pvm.delegate.ActivityBehavior;
import org.operaton.bpm.engine.impl.pvm.PvmTransition;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.util.xml.Element;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Makes every {@code EpistolaDocumentGenerated} message intermediate catch event self-identify which
 * generation it is waiting for, so the result collector can correlate a completion to exactly that
 * branch — independent of the execution-tree shape (the root cause of the parallel-correlation bug).
 *
 * <p>After a process is parsed (when the flow-node graph is fully wired), for each message catch event
 * it finds the generating service task that flows into it (nearest preceding service task in the BPMN
 * graph — the {@code generate-document}) and attaches a start {@link ExecutionListener} baked with that
 * source activity id. At runtime, when the branch subscribes, the listener pins the branch's jobPath —
 * read from the activity-named {@code <sourceActivityId>_epistolaJobPath} variable that
 * {@code generate-document} wrote — as the execution-local {@link EpistolaProcessVariables#WAIT_FOR}
 * token on the subscription execution.
 *
 * <p>Scoping is by construction: the listener only acts when {@code <sourceActivityId>_epistolaJobPath}
 * exists at runtime, which only an Epistola {@code generate-document} ever writes; other message catch
 * events get a harmless no-op. A process author can override the auto-resolution by defining
 * {@code epistolaWaitFor} themselves (e.g. a {@code camunda:inputParameter}) — the listener never
 * overwrites an existing value.
 */
@Slf4j
public class EpistolaCatchEventTokenParseListener implements BpmnParseListener {

    private static final String TYPE_INTERMEDIATE_MESSAGE_CATCH = "intermediateMessageCatch";
    private static final String TYPE_SERVICE_TASK = "serviceTask";

    @Override
    public void parseProcess(Element processElement, ProcessDefinitionEntity processDefinition) {
        // Done here (not in parseIntermediateMessageCatchEventDefinition) because the activity graph's
        // sequence-flow transitions are not yet wired while individual elements are being parsed.
        attachToMessageCatchEvents(processDefinition.getActivities());
    }

    private void attachToMessageCatchEvents(List<ActivityImpl> activities) {
        if (activities == null) {
            return;
        }
        for (ActivityImpl activity : activities) {
            if (TYPE_INTERMEDIATE_MESSAGE_CATCH.equals(String.valueOf(activity.getProperty("type")))) {
                String sourceActivityId = findSourceServiceTask(activity);
                if (sourceActivityId != null) {
                    activity.addListener(ExecutionListener.EVENTNAME_START, new PinWaitTokenListener(sourceActivityId));
                    wrapWithSelfHealing(activity);
                }
            }
            // Recurse into embedded subprocesses / multi-instance bodies.
            attachToMessageCatchEvents(activity.getActivities());
        }
    }

    /**
     * The nearest service task reachable by walking the BPMN graph backwards from {@code catchEvent}
     * through gateways. That is the {@code generate-document} whose jobPath this catch event waits for.
     * Returns {@code null} if none is found before another activity/wait state breaks every path.
     */
    private static String findSourceServiceTask(ActivityImpl catchEvent) {
        Set<String> visited = new HashSet<>();
        Deque<PvmActivity> queue = new ArrayDeque<>();
        enqueuePredecessors(catchEvent, queue);

        while (!queue.isEmpty()) {
            PvmActivity node = queue.poll();
            if (node == null || !visited.add(node.getId())) {
                continue;
            }
            String type = String.valueOf(node.getProperty("type"));
            if (TYPE_SERVICE_TASK.equals(type)) {
                return node.getId();
            }
            if (type.toLowerCase().contains("gateway")) {
                enqueuePredecessors(node, queue);
            }
            // Any other node type (task, event, subprocess, …) breaks this path.
        }
        return null;
    }

    /**
     * Wrap the catch event's behavior so it self-heals against a result that arrived before it
     * subscribed (see {@link EpistolaSelfHealingCatchBehavior}). Idempotent: re-parsing an already
     * wrapped definition does not double-wrap.
     */
    private static void wrapWithSelfHealing(ActivityImpl activity) {
        ActivityBehavior current = activity.getActivityBehavior();
        if (!(current instanceof EpistolaSelfHealingCatchBehavior)) {
            activity.setActivityBehavior(new EpistolaSelfHealingCatchBehavior(current));
        }
    }

    private static void enqueuePredecessors(PvmActivity activity, Deque<PvmActivity> queue) {
        for (PvmTransition incoming : activity.getIncomingTransitions()) {
            if (incoming.getSource() != null) {
                queue.add(incoming.getSource());
            }
        }
    }

    /**
     * Pins the branch's jobPath as the execution-local {@link EpistolaProcessVariables#WAIT_FOR} token
     * on the subscribing catch-event execution, unless a value is already present (author override) or
     * the source activity didn't write a jobPath (non-Epistola flow).
     */
    private record PinWaitTokenListener(String sourceActivityId) implements ExecutionListener {
        @Override
        public void notify(DelegateExecution execution) {
            if (execution.getVariableLocal(EpistolaProcessVariables.WAIT_FOR) != null) {
                return; // author override — don't touch it
            }
            Object jobPath = execution.getVariable(EpistolaProcessVariables.activityJobPathVariable(sourceActivityId));
            if (jobPath == null) {
                return; // not an Epistola generate-document flow (or not generated yet)
            }
            execution.setVariableLocal(EpistolaProcessVariables.WAIT_FOR, jobPath);
            log.debug("Pinned {}={} on catch-event execution {} (source activity {})",
                    EpistolaProcessVariables.WAIT_FOR, jobPath, execution.getId(), sourceActivityId);
        }
    }
}
