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
 * Describes a process instance currently parked on an {@code EpistolaDocumentGenerated} wait.
 *
 * <p>{@link #status} distinguishes:
 * <ul>
 *   <li>{@link #STATUS_WAITING} — a normal wait: it carries the {@code epistolaWaitFor} correlation
 *       token, so the collector can (and will) wake it when its result lands.</li>
 *   <li>{@link #STATUS_UNWIRED} — the wait has the subscription but <em>no</em> token, so the collector
 *       can never correlate it: the process is stuck. These were previously skipped entirely (invisible
 *       in admin); they are now surfaced so an operator can see and fix the process model. {@code tenantId}
 *       is best-effort (from the standalone {@code epistolaTenantId} variable) and {@code requestId} is
 *       {@code null}. Reconcile cannot recover an unwired wait — there is no jobPath to resolve.</li>
 * </ul>
 */
public record PendingJob(
        String executionId,
        String processInstanceId,
        String processDefinitionKey,
        String processDefinitionName,
        String activityId,
        String activityName,
        String tenantId,
        String requestId,
        String configurationTitle,
        String status
) {
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_UNWIRED = "UNWIRED";
}
