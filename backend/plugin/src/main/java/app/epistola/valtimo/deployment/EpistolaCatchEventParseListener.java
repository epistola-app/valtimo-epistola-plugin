package app.epistola.valtimo.deployment;

import lombok.RequiredArgsConstructor;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.util.xml.Element;

/**
 * Auto-wires Epistola's catch-event correlation: at parse time it attaches a single start
 * {@link ExecutionListener} to every message intermediate catch event, so process authors don't have
 * to configure anything per catch event. All the logic lives in that (public-API) listener; this
 * class only registers it.
 *
 * <p>This is the plugin's one use of Camunda's sanctioned parse SPI ({@link BpmnParseListener}, the
 * {@code parseIntermediateMessageCatchEventDefinition} hook, and {@code ActivityImpl#addListener}).
 * It deliberately does <strong>not</strong> walk the PVM graph or wrap activity behavior — the listener
 * resolves everything at runtime via public APIs and no-ops for non-Epistola catch events.
 */
@RequiredArgsConstructor
public class EpistolaCatchEventParseListener implements BpmnParseListener {

    private final ExecutionListener catchEventStartListener;

    @Override
    public void parseIntermediateMessageCatchEventDefinition(Element messageEventDefinition, ActivityImpl activity) {
        activity.addListener(ExecutionListener.EVENTNAME_START, catchEventStartListener);
    }
}
