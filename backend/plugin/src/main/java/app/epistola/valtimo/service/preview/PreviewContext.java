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
package app.epistola.valtimo.service.preview;

import java.util.Map;

/**
 * The fully-resolved, already-authorized inputs a preview render needs.
 *
 * <p>Both entry points on {@link PreviewService} converge here, which is what keeps the
 * link-resolution, mapping and render path identical between a task preview and a start-event
 * preview. The only difference between them is how these fields were obtained: derived from an
 * authorized task, or resolved from an authorized process definition.
 *
 * @param processDefinitionId Always non-null. Everything downstream keys off the definition, never
 *                            the instance — which is why a start-event preview works at all.
 * @param documentId          The case document backing {@code $doc}, or null for a brand-new case.
 * @param processInstanceId   The running instance backing {@code $pv}, or null for both start
 *                            flavours (a start event has no instance by construction).
 * @param sourceActivityId    The generate-document activity to dry-run. When blank, the task path
 *                            auto-discovers a single one; the start path requires it.
 * @param inputOverrides      Overrides applied before the mapping, shaped {@code {"doc", "pv"}}.
 * @param outputOverrides     Post-mapping deep-merge, task path only (the retry form); null on the
 *                            start path.
 */
record PreviewContext(
        String processDefinitionId,
        String documentId,
        String processInstanceId,
        String sourceActivityId,
        Map<String, Map<String, Object>> inputOverrides,
        Map<String, Object> outputOverrides
) {
}
