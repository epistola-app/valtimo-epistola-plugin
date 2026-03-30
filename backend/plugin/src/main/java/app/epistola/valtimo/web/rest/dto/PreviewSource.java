package app.epistola.valtimo.web.rest.dto;

/**
 * A previewable document source discovered from running process instances.
 *
 * @param processDefinitionKey The process definition key
 * @param activityId           The BPMN activity ID of the generate-document service task
 * @param templateId           The Epistola template ID configured on this task
 * @param templateName         Human-readable template name (for dropdown display)
 * @param processInstanceId    The running process instance ID (enables pv: expression resolution)
 */
public record PreviewSource(
        String processDefinitionKey,
        String activityId,
        String templateId,
        String templateName,
        String processInstanceId
) {
}
