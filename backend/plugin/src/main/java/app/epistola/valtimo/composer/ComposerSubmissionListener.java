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
package app.epistola.valtimo.composer;

import com.ritense.valtimo.contract.event.TaskCompletedEvent;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

/**
 * Applies a composer's write-back when the form is submitted.
 *
 * <p>Valtimo publishes {@link TaskCompletedEvent} once per completed task, from its {@code contract}
 * module, and it carries both things this needs: {@code businessKey} — the case document id, for a
 * dossier process — and the process variables, which is where a composer's letter has just landed.
 *
 * <p><b>Why an event and not a form field.</b> Every other form writes to the case by having a
 * {@code doc:}-keyed field in its submission, and a composer cannot: its own field is already keyed
 * {@code pv:} so the letter reaches the generate task, and a Form.io component can add neither a
 * top-level sibling nor a submitted child of its own — measured, not assumed, see
 * <a href="../../../../../../../docs/adr/0006-letter-composer-configuration.md">ADR 0006</a>. So the
 * platform's own submission signal is used instead of its submission <em>payload</em>.
 *
 * <p>It fires for every completed task in the installation, so the cheap checks come first: a
 * business key that is not a case id, or no variables, and there is nothing to do. Only then is the
 * case's configuration consulted.
 *
 * <p><b>What this still does not buy.</b> Moving write-back here closes the window in which a
 * redeployed form could change the rules under a letter already composed, and it stops a case
 * waiting on an asynchronous render for values the employee already approved. It does <em>not</em>
 * let a failed write be reported to that employee: the event arrives after the task has completed,
 * so throwing would report a failure for a submission that succeeded. Telling someone needs a
 * mechanism that is not an exception — see #179.
 */
@Slf4j
@RequiredArgsConstructor
public class ComposerSubmissionListener {

    private final ComposerWriteBackService writeBackService;

    @EventListener
    public void onTaskCompleted(TaskCompletedEvent event) {
        UUID documentId = caseIdOf(event.getBusinessKey());
        if (documentId == null) {
            return;
        }
        Map<String, Object> variables = event.getVariables();
        if (variables == null || variables.isEmpty()) {
            return;
        }
        try {
            writeBackService.applyFromSubmission(documentId, variables);
        } catch (RuntimeException e) {
            // The service promises not to throw; this is the belt to that braces. Spring dispatches
            // this event synchronously from the submission, and the task is *already* completed by
            // the time it arrives — so an exception here would report a failure for a submission
            // that in fact succeeded, and a retry would complete a second task.
            log.error("Letter composer write-back failed for case {} on task completion: {}",
                    documentId, e.getMessage(), e);
        }
    }

    /**
     * The case this task belonged to, or null when it belonged to none.
     *
     * <p>Valtimo's dossier-driven processes carry the case document UUID as the process business
     * key. A process started without a case has something else there, or nothing — not an error,
     * just nowhere for a letter's values to go.
     */
    private static UUID caseIdOf(String businessKey) {
        if (businessKey == null || businessKey.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(businessKey);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
