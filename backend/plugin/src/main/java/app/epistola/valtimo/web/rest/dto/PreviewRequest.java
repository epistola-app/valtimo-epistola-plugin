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
