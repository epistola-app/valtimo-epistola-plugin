package app.epistola.valtimo.deployment;

import app.epistola.valtimo.domain.EpistolaProcessVariables;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.impl.bpmn.behavior.AbstractBpmnActivityBehavior;
import org.operaton.bpm.engine.impl.pvm.delegate.ActivityBehavior;
import org.operaton.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.operaton.bpm.engine.impl.pvm.delegate.SignallableActivityBehavior;

import java.util.Map;

/**
 * Wraps an {@code EpistolaDocumentGenerated} catch event's behavior so the branch does not stall if
 * the generation result arrived <em>before</em> the catch event subscribed.
 *
 * <p>That window only opens when there is an async boundary between {@code generate-document} and the
 * catch event (which the plugin advises against — see the BPMN race-safety validator). In that case
 * the result collector finds no subscription yet, updates the result variable in place, and acks the
 * result. Without this wrapper the catch event would later subscribe and wait forever. With it, on
 * entry the catch event checks whether its job's result is already terminal and, if so, continues
 * immediately instead of subscribing.
 *
 * <p>In the normal (synchronous) flow the result is still {@code PENDING} on entry, so the wrapper
 * delegates to the original behavior unchanged; message delivery is delegated via {@link #signal}.
 */
@Slf4j
public class EpistolaSelfHealingCatchBehavior extends AbstractBpmnActivityBehavior {

    private final ActivityBehavior delegate;

    public EpistolaSelfHealingCatchBehavior(ActivityBehavior delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(ActivityExecution execution) throws Exception {
        if (resultAlreadyArrived(execution)) {
            log.debug("EpistolaDocumentGenerated result already present on entry for execution {} — "
                    + "completing the catch event without waiting (self-heal)", execution.getId());
            doLeave(execution);
        } else {
            delegate.execute(execution);
        }
    }

    @Override
    public void signal(ActivityExecution execution, String signalName, Object signalData) throws Exception {
        if (delegate instanceof SignallableActivityBehavior signallable) {
            signallable.signal(execution, signalName, signalData);
        } else {
            super.signal(execution, signalName, signalData);
        }
    }

    /**
     * Whether this branch's generation result is already terminal. Reads the pinned
     * {@link EpistolaProcessVariables#WAIT_FOR} token (the jobPath), resolves the result-variable name
     * via the jobPath-named locator, and inspects that variable's {@code status}. Conservative: any
     * missing piece (non-Epistola catch event, token not pinned, result not yet present) returns
     * {@code false} so the catch event waits as normal.
     */
    private boolean resultAlreadyArrived(ActivityExecution execution) {
        if (!(execution.getVariable(EpistolaProcessVariables.WAIT_FOR) instanceof String jobPath)) {
            return false;
        }
        if (!(execution.getVariable(jobPath) instanceof String resultVariableName)) {
            return false;
        }
        return execution.getVariable(resultVariableName) instanceof Map<?, ?> result
                && EpistolaProcessVariables.isTerminalStatus(result.get(EpistolaProcessVariables.RESULT_KEY_STATUS));
    }
}
