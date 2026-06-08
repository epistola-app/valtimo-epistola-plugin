package app.epistola.valtimo.web.rest.dto;

import java.time.Instant;
import java.util.List;

/**
 * The result of the BPMN race-safety validator, plus metadata the admin UI needs to
 * tell the operator how fresh and how complete the result is.
 *
 * <ul>
 *   <li>{@code lastCheckedAt} — when the last scan finished, or {@code null} if no scan
 *       has completed yet (e.g. just after startup, before the first tick).</li>
 *   <li>{@code refreshIntervalMs} — the fixed-delay scan cadence, so the UI can state how
 *       often the result is refreshed without hard-coding the number.</li>
 *   <li>{@code violations} — the current snapshot; empty means healthy. Only the latest
 *       deployed version of each process definition is inspected.</li>
 * </ul>
 */
public record BpmnValidationReport(
        Instant lastCheckedAt,
        long refreshIntervalMs,
        List<BpmnValidationViolation> violations
) {}
