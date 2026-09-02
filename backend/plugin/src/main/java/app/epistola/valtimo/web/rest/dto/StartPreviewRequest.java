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

import java.util.Map;

/**
 * Request body for a start-event document preview ({@code POST /preview/start}).
 *
 * <p>A start event has no user task and no process instance, so this request cannot reuse the
 * task-derived context of {@link PreviewRequest}. Instead the caller names the process definition
 * it wants to preview, and the controller authorizes {@code OperatonExecution:CREATE} against that
 * definition — the same check Valtimo performs before serving the start form this component sits
 * on. The client-supplied key is safe because it selects <i>which</i> resource is checked, never
 * <i>whether</i> one is.
 *
 * <p>Deliberately absent, each for a reason:
 * <ul>
 *   <li><b>{@code taskId} / {@code processInstanceId}</b> — a start event has neither by
 *       construction. A body that carries one is <b>silently ignored</b>, not rejected: Spring
 *       Boot's Jackson auto-configuration disables {@code FAIL_ON_UNKNOWN_PROPERTIES}, so an
 *       unknown field is dropped during deserialization. That is safe here because nothing on this
 *       path can read one — the controller authorizes against the process definition, and
 *       {@code PreviewService.generateStartPreview} hard-codes {@code processInstanceId = null}.
 *       There is no plumbing for a smuggled task to reach, so the absence of the field <i>is</i>
 *       the guarantee. Do not add one.</li>
 *   <li><b>{@code overrides}</b> (the post-mapping deep-merge) — that affordance exists only for
 *       the task-bound retry form's edited-payload flow. Omitting it keeps an arbitrary-JSON
 *       injection surface off this path.</li>
 *   <li><b>{@code documentDefinitionName}</b> — not needed, since authorization is against the
 *       process definition rather than the case type.</li>
 * </ul>
 *
 * @param processDefinitionKey The BPMN process definition key to preview. Required. Version-stable
 *                             (a version-pinned id would break on the next deployment); the
 *                             controller resolves it to the latest deployed version.
 * @param sourceActivityId     The BPMN activity id of the generate-document service task. Required
 *                             here — unlike {@code /preview}, this endpoint offers no
 *                             auto-discovery, so an activity id can never be inferred from the wire.
 * @param documentId           Optional Valtimo case-document UUID, for the "start a process on an
 *                             existing case" flavour. When present the caller must additionally
 *                             hold {@code JsonSchemaDocument:VIEW} on it — permission to start a
 *                             process must never confer read access to a case.
 * @param inputOverrides       Input-level overrides applied before JSONata evaluation, shaped
 *                             {@code {"doc": {...}, "pv": {...}}}. On a new-case start form this is
 *                             the <i>only</i> source of data: there is no case and no process
 *                             instance to fall back to.
 */
public record StartPreviewRequest(
        String processDefinitionKey,
        String sourceActivityId,
        String documentId,
        Map<String, Map<String, Object>> inputOverrides
) {
}
