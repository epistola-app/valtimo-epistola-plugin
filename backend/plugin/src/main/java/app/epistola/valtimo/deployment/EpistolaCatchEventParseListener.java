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
package app.epistola.valtimo.deployment;

import lombok.RequiredArgsConstructor;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl;
import org.operaton.bpm.engine.impl.util.xml.Element;

/**
 * Auto-wires Epistola's correlation: at parse time it attaches a single start
 * {@link ExecutionListener} to every Epistola wait state — a message intermediate catch event AND a
 * receive task — so process authors don't have to configure anything per wait. Both wait kinds are
 * correlated the same way; pinning the token on the wait's own execution at entry keeps it co-located
 * with the subscription (robust under parallel/scoped topologies). All the logic lives in that
 * (public-API) listener, which resolves everything at runtime and no-ops for non-Epistola activities;
 * this class only registers it.
 *
 * <p>This is the plugin's one use of Camunda's sanctioned parse SPI ({@link BpmnParseListener} hooks and
 * {@code ActivityImpl#addListener}). It deliberately does <strong>not</strong> walk the PVM graph or
 * wrap activity behavior.
 */
@RequiredArgsConstructor
public class EpistolaCatchEventParseListener implements BpmnParseListener {

    private final ExecutionListener catchEventStartListener;

    @Override
    public void parseIntermediateMessageCatchEventDefinition(Element messageEventDefinition, ActivityImpl activity) {
        activity.addListener(ExecutionListener.EVENTNAME_START, catchEventStartListener);
    }

    @Override
    public void parseReceiveTask(Element receiveTaskElement, ScopeImpl scope, ActivityImpl activity) {
        // A receive task is the task-shaped equivalent of a round message catch event. Like the catch
        // event, its start listener fires on entry — on the execution that creates the message
        // subscription — so the token is pinned co-located with the subscription. The listener no-ops
        // for non-Epistola receive tasks (the resolver returns no result variable for them).
        activity.addListener(ExecutionListener.EVENTNAME_START, catchEventStartListener);
    }
}
