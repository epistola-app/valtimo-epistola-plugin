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
 * A BPMN structure violation discovered by
 * {@link app.epistola.valtimo.deployment.EpistolaProcessDefinitionValidator}.
 *
 * <p>The validator only fires when the BPMN unambiguously uses the catch-event pattern —
 * a {@code generate-document} service task whose forward graph (through any number of
 * gateways) reaches an {@code EpistolaDocumentGenerated} {@code IntermediateCatchEvent}
 * before any other wait state. When that pattern is detected, the boundary between the
 * service task and the catch event must be synchronous; otherwise the result-collector
 * will race the engine commit and miss messages.
 *
 * <p>Each entry below is one violation. Operators remediate by editing the BPMN in their
 * authoring tool — the plugin never modifies BPMN models.
 */
public record BpmnValidationViolation(
        String processDefinitionKey,
        String processDefinitionName,
        String activityId,
        String code,
        String message
) {
    /**
     * The deployed service task has the platform-injected signature
     * ({@code expression="${null}"} + {@code asyncAfter="true"}, no class/type/delegate),
     * meaning the user didn't set a {@code camunda:expression} so Valtimo auto-enabled
     * asyncAfter at deploy time. Remediation: add {@code camunda:expression="${null}"}
     * (or any other expression) to the service task in the BPMN authoring tool.
     */
    public static final String CODE_PLATFORM_ASYNC_AFTER_ON_SERVICE_TASK = "PLATFORM_ASYNC_AFTER_ON_SERVICE_TASK";

    /**
     * The wait (catch event or receive task) has {@code camunda:asyncBefore="true"}, which forces a tx
     * commit between the service task and the subscription creation — same race window as the
     * platform-injected asyncAfter. Remediation: remove asyncBefore on the wait.
     */
    public static final String CODE_ASYNC_BEFORE_ON_CATCH_EVENT = "ASYNC_BEFORE_ON_CATCH_EVENT";

    /**
     * Two or more {@code generate-document} service tasks flow into the <em>same</em>
     * {@code EpistolaDocumentGenerated} wait (catch event or receive task) <em>with different result
     * variables</em>. The auto-wiring pins exactly one result variable's jobPath to that wait (the
     * resolver keeps only the last pairing), so a branch whose variable wasn't pinned gets no
     * {@code epistolaWaitFor} token: its completion is never correlated, the process stalls at the wait,
     * and — being token-less — it doesn't even appear in admin Pending Jobs. Remediation: for an
     * exclusive split that merges, give every branch the <em>same</em> {@code resultProcessVariable}
     * (only one branch runs, so the shared variable always resolves); for parallel branches, give each
     * its own wait and its own {@code resultProcessVariable}. Not flagged when all converging branches already
     * share one result variable.
     */
    public static final String CODE_AMBIGUOUS_CATCH_EVENT = "AMBIGUOUS_CATCH_EVENT";
}
