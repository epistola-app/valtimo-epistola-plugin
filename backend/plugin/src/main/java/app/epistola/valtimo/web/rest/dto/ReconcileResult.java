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
package app.epistola.valtimo.web.rest.dto;

/**
 * Outcome of a manual reconcile attempt for a stuck Epistola catch event.
 *
 * <p>{@code correlated} is {@code true} when the call ran a message correlation
 * (i.e. the Epistola job is in a terminal state); {@code correlatedCount} then
 * holds the number of executions that received the message — typically 1, but
 * could legitimately be 0 if a concurrent reconcile / late natural correlation
 * already picked up the same execution.
 *
 * <p>{@code outcome} says why when nothing was correlated, so the admin page can tell the operator
 * what to do: wait ({@link Outcome#STILL_RUNNING}), retry or check the connection ({@link Outcome#EPISTOLA_ERROR}),
 * or send the process back to its generate step, because retrying can never wake it
 * ({@link Outcome#NOT_A_JOB}, {@link Outcome#UNKNOWN_TO_EPISTOLA}). The controller maps each to its
 * own HTTP status.
 */
public record ReconcileResult(
        String executionId,
        String processInstanceId,
        String tenantId,
        String requestId,
        String epistolaStatus,
        Integer correlatedCount,
        boolean correlated,
        Outcome outcome
) {

    public enum Outcome {
        /** The job had finished and its result was delivered to the waiting process. */
        CORRELATED,
        /** Epistola is still working on the job; nothing to deliver yet. */
        STILL_RUNNING,
        /**
         * The wait is pinned to something that is not an Epistola job id — development builds just
         * before 1.0.0 wrote {@code refused-<uuid>} for a generation Epistola refused, and an author
         * can set {@code epistolaWaitFor} by hand. No retry can wake it.
         */
        NOT_A_JOB,
        /**
         * Epistola answered that it does not know this job (404/410), e.g. because the process was
         * moved back to its catch event and is waiting on a job that is gone. No retry can wake it.
         */
        UNKNOWN_TO_EPISTOLA,
        /**
         * Epistola could not answer for the job: unreachable, a 5xx, or a refusal that is about the
         * connection rather than the job (a 401/403 from a wrong API key). A later retry, or a fixed
         * connection, may work.
         */
        EPISTOLA_ERROR
    }
}
