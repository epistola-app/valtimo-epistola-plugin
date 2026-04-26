package app.epistola.valtimo.web.rest.dto;

/**
 * Describes a process instance currently waiting for an Epistola document generation result.
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
        String configurationTitle
) {}
