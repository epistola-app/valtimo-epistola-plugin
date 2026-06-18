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
