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
import app.epistola.valtimo.domain.EpistolaProcessVariables;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * Start listener attached to every {@code EpistolaDocumentGenerated} message catch event (by
 * {@link app.epistola.valtimo.deployment.EpistolaCatchEventParseListener}). On entry it does two
 * things, both with public APIs:
 *
 * <ol>
 *   <li><b>Pins the correlation token.</b> Resolves this branch's result variable
 *       ({@link EpistolaCatchEventLinkResolver}, public BPMN model + process links), reads the jobPath
 *       out of its rich result value, and pins it as the execution-local
 *       {@link EpistolaProcessVariables#WAIT_FOR} — so a result wakes exactly this branch. It does
 *       <em>not</em> overwrite an existing value, so a process author can override by setting
 *       {@code epistolaWaitFor} themselves (e.g. a {@code camunda:inputParameter}).</li>
 *   <li><b>Arms self-heal.</b> Registers an after-commit callback; once the subscription is committed,
 *       {@link EpistolaMessageCorrelationService#selfHeal} delivers an already-terminal result so the
 *       branch doesn't stall if the result arrived before it subscribed (only possible behind an async
 *       boundary). In the normal synchronous flow the result is still PENDING, so this is a no-op.</li>
 * </ol>
 *
 * <p>For non-Epistola catch events the resolver returns {@code null} and this is a fast no-op.
 */
@Slf4j
@RequiredArgsConstructor
public class EpistolaCatchEventStartListener implements ExecutionListener {

    private final EpistolaCatchEventLinkResolver linkResolver;
    private final EpistolaMessageCorrelationService correlationService;

    @Override
    public void notify(DelegateExecution execution) {
        // This listener is attached to EVERY message catch event in the whole application (Epistola or
        // not), so a failure here must never break an unrelated process's catch-event entry. Swallow and
        // log: the worst case degrades to "token not auto-pinned" (visible via this WARN, the validator,
        // and the admin reconcile) rather than a broken engine transaction.
        try {
            String resultVariableName =
                    linkResolver.resultVariableFor(execution.getProcessDefinitionId(), execution.getCurrentActivityId());
            if (resultVariableName == null) {
                return; // not an Epistola generate-document → catch event pattern
            }

            pinWaitToken(execution, resultVariableName);
            armSelfHeal(execution.getId());
        } catch (Exception e) {
            log.warn("Epistola catch-event auto-wiring failed for execution {} (activity {}); continuing "
                            + "without an auto-pinned token: {}",
                    execution.getId(), execution.getCurrentActivityId(), e.getMessage());
        }
    }

    /** Pin the branch's jobPath as {@link EpistolaProcessVariables#WAIT_FOR}, unless already set (author override). */
    private void pinWaitToken(DelegateExecution execution, String resultVariableName) {
        if (execution.getVariableLocal(EpistolaProcessVariables.WAIT_FOR) != null) {
            return; // author override — leave it
        }
        if (execution.getVariable(resultVariableName) instanceof Map<?, ?> result
                && result.get(EpistolaProcessVariables.RESULT_KEY_JOB_PATH) instanceof String jobPath
                && !jobPath.isBlank()) {
            execution.setVariableLocal(EpistolaProcessVariables.WAIT_FOR, jobPath);
        }
    }

    /** Deliver an already-terminal result once the subscription is committed (after-commit, public Spring tx hook). */
    private void armSelfHeal(String executionId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return; // no Spring tx (e.g. standalone engine in tests); selfHeal() is exercised directly there
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    correlationService.selfHeal(executionId);
                } catch (Exception e) {
                    log.warn("Self-heal check failed for catch-event execution {}: {}", executionId, e.getMessage());
                }
            }
        });
    }
}
