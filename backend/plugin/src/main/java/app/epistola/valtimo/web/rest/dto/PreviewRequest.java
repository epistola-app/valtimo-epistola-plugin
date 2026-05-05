package app.epistola.valtimo.web.rest.dto;

import java.util.Map;

/**
 * Request body for document preview.
 *
 * @param documentId           The Valtimo document ID used to populate the $doc JSONata context.
 * @param processDefinitionKey The process definition key (required — identifies the process link)
 * @param sourceActivityId     The BPMN activity ID of the generate-document service task (required)
 * @param processInstanceId    Optional process instance ID used to populate the $pv JSONata context.
 * @param overrides            Optional data overrides (deep-merged with resolved data after mapping, overrides win)
 * @param inputOverrides       Optional input-level overrides applied before JSONata evaluation.
 *                             Structure: {@code {"doc": {...}, "pv": {...}}}. Values under "doc" are
 *                             overlaid on the document content; values under "pv" take precedence over
 *                             process variables. The JSONata mapping then runs against the overridden inputs.
 */
public record PreviewRequest(
        String documentId,
        String processDefinitionKey,
        String sourceActivityId,
        String processInstanceId,
        Map<String, Object> overrides,
        Map<String, Map<String, Object>> inputOverrides
) {
}
