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
 * Request body for document preview.
 *
 * <p>The caller supplies only the authorization context ({@code taskId}) and the component
 * configuration ({@code sourceActivityId} + overrides). The process instance and case document
 * the preview resolves against are derived server-side from the authorized task, so they are
 * not part of this request.
 *
 * @param taskId           The Operaton user task ID providing the authorization context.
 *                         Required: the caller must have {@code OperatonTask:VIEW} on this task.
 * @param sourceActivityId The BPMN activity ID of the generate-document service task. Optional:
 *                         when blank, the single generate-document link is auto-discovered.
 * @param overrides        Optional data overrides (deep-merged with resolved data after mapping, overrides win)
 * @param inputOverrides   Optional input-level overrides applied before JSONata evaluation.
 *                         Structure: {@code {"doc": {...}, "pv": {...}}}. Values under "doc" are
 *                         overlaid on the document content; values under "pv" take precedence over
 *                         process variables. The JSONata mapping then runs against the overridden inputs.
 */
public record PreviewRequest(
        String taskId,
        String sourceActivityId,
        Map<String, Object> overrides,
        Map<String, Map<String, Object>> inputOverrides
) {
}
