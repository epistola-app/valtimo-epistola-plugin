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
 * <p>{@code correlated=false} means the Epistola job is still in flight
 * ({@code epistolaStatus} = PENDING / IN_PROGRESS); the controller maps that
 * to HTTP 409 so the UI can surface a "try again later" message without
 * treating it as an error.
 */
public record ReconcileResult(
        String executionId,
        String processInstanceId,
        String tenantId,
        String requestId,
        String epistolaStatus,
        Integer correlatedCount,
        boolean correlated
) {}
