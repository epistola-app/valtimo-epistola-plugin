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
     * The catch event has {@code camunda:asyncBefore="true"}, which forces a tx commit
     * between the service task and the subscription creation — same race window as the
     * platform-injected asyncAfter. Remediation: remove asyncBefore on the catch event.
     */
    public static final String CODE_ASYNC_BEFORE_ON_CATCH_EVENT = "ASYNC_BEFORE_ON_CATCH_EVENT";

    /**
     * Two or more {@code generate-document} service tasks flow into the <em>same</em>
     * {@code EpistolaDocumentGenerated} catch event. The auto-wiring pins exactly one result
     * variable's jobPath to that catch event, so completions can correlate to the wrong branch
     * (and the resolver silently keeps only the last pairing). Remediation: give each branch its
     * own catch event, or disambiguate by setting a distinct {@code epistolaWaitFor}
     * {@code camunda:inputParameter} on each catch event.
     */
    public static final String CODE_AMBIGUOUS_CATCH_EVENT = "AMBIGUOUS_CATCH_EVENT";
}
