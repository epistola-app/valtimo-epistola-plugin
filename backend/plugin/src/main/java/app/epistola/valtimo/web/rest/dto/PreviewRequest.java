package app.epistola.valtimo.web.rest.dto;

import java.util.Map;

/**
 * Request body for document preview.
 *
 * @param documentId           The Valtimo document ID (required — resolves doc:/case: expressions)
 * @param processDefinitionKey The process definition key (required — identifies the process link)
 * @param sourceActivityId     The BPMN activity ID of the generate-document service task (required)
 * @param processInstanceId    Optional process instance ID (resolves pv: expressions if provided)
 * @param overrides            Optional data overrides (deep-merged with resolved data, overrides win)
 */
public record PreviewRequest(
        String documentId,
        String processDefinitionKey,
        String sourceActivityId,
        String processInstanceId,
        Map<String, Object> overrides
) {
}
