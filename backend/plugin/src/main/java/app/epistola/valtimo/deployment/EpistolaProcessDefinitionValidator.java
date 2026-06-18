package app.epistola.valtimo.deployment;

import app.epistola.valtimo.domain.EpistolaProcessVariables;
import app.epistola.valtimo.web.rest.dto.BpmnValidationViolation;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.BusinessRuleTask;
import org.operaton.bpm.model.bpmn.instance.CatchEvent;
import org.operaton.bpm.model.bpmn.instance.EventDefinition;
import org.operaton.bpm.model.bpmn.instance.FlowNode;
import org.operaton.bpm.model.bpmn.instance.Gateway;
import org.operaton.bpm.model.bpmn.instance.IntermediateCatchEvent;
import org.operaton.bpm.model.bpmn.instance.Message;
import org.operaton.bpm.model.bpmn.instance.MessageEventDefinition;
import org.operaton.bpm.model.bpmn.instance.ReceiveTask;
import org.operaton.bpm.model.bpmn.instance.ScriptTask;
import org.operaton.bpm.model.bpmn.instance.SequenceFlow;
import org.operaton.bpm.model.bpmn.instance.ServiceTask;
import org.operaton.bpm.model.bpmn.instance.UserTask;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Validates that every BPMN process definition using the {@code generate-document}
 * Epistola plugin action is structured to keep the result-correlation race-safe.
 *
 * <p>What the validator does (and explicitly does not do):
 * <ul>
 *   <li>It only emits warnings when the BPMN uses the catch-event pattern — the
 *       {@code generate-document} service task's forward graph reaches an
 *       {@link EpistolaProcessVariables#MESSAGE_NAME} {@link IntermediateCatchEvent}
 *       before any other wait state. The forward walk follows direct sequence flows
 *       and traverses through all gateway types (exclusive, inclusive, parallel,
 *       complex, event-based) without evaluating their conditions.</li>
 *   <li>If no such catch event is reachable, the validator is silent — that BPMN is
 *       using the variable pattern (read the result from a process variable later)
 *       and has no race exposure.</li>
 *   <li>It never modifies the BPMN. Operators remediate by editing the source model
 *       in their authoring tool.</li>
 * </ul>
 *
 * <p>Two violations:
 * <ul>
 *   <li>{@link BpmnValidationViolation#CODE_PLATFORM_ASYNC_AFTER_ON_SERVICE_TASK} —
 *       the deployed service task has the platform-injected signature
 *       ({@code expression="${null}"} + {@code asyncAfter="true"}, no other handler),
 *       which Valtimo applies whenever the user didn't author a {@code camunda:expression}.
 *       The asyncAfter widens the commit-vs-collector race; remediation is to add
 *       any {@code camunda:expression} to the service task.</li>
 *   <li>{@link BpmnValidationViolation#CODE_ASYNC_BEFORE_ON_CATCH_EVENT} — the catch
 *       event has {@code camunda:asyncBefore} set; same race-widening effect.</li>
 * </ul>
 *
 * <p>Violations are logged at WARN and surfaced via {@link #getViolations()} for the
 * admin UI. Validation does not fail deployment — Valtimo redeploys many definitions
 * on every boot and a hard fail would be operationally hostile.
 *
 * <p>Scanning runs on a <strong>cron</strong> schedule (default {@code 0 *​/10 * * * *} — every
 * 10 minutes on the wall clock) rather than a per-instance fixed delay, so in a clustered
 * deployment every node fires at the same clock boundary and the scans stay aligned within a
 * minute of each other (NTP-synced clocks assumed) instead of drifting by each node's startup
 * offset. To avoid every node hitting the engine/DB at the same instant, each node then defers
 * the actual scan by a small random 1–25s jitter (scheduled, not slept, so the shared task
 * scheduler thread stays free). An additional one-shot scan runs on {@link ApplicationReadyEvent}
 * so a freshly (re)started node populates immediately rather than waiting for the next boundary.
 * The result is a point-in-time snapshot stored in an {@link AtomicReference} for lock-free
 * reads, alongside the {@link #getLastCheckedAt() last-checked timestamp}. Per deployed version,
 * results are cached and only recomputed when a new version is deployed or its
 * {@code generate-document} links change, so an unchanged process is not re-parsed every tick.
 */
@Slf4j
public class EpistolaProcessDefinitionValidator {

    private static final String GENERATE_DOCUMENT_ACTION_KEY = "epistola-generate-document";
    private static final long FALLBACK_INTERVAL_MS = 600_000L;

    /** Random per-node delay applied after the aligned cron tick, to de-synchronise the herd. */
    private static final long MIN_JITTER_MS = 1_000L;
    private static final long MAX_JITTER_MS = 25_000L;

    private final RepositoryService repositoryService;
    private final ProcessLinkService processLinkService;

    /**
     * The validator's scan cadence in milliseconds, derived from the configured cron so the
     * admin UI can state how often the result refreshes. Approximate for irregular crons.
     */
    private final long refreshIntervalMs;

    private final AtomicReference<List<BpmnValidationViolation>> violations =
            new AtomicReference<>(Collections.emptyList());

    /** When the last scan finished, or {@code null} if no scan has run yet. */
    private final AtomicReference<Instant> lastCheckedAt = new AtomicReference<>(null);

    /**
     * Per-version result cache keyed by {@link ProcessDefinition#getId()} (the
     * deployment-specific id that changes on every new deployment). A deployed
     * version's BPMN is immutable, so its violations can only change if the set of
     * {@code generate-document} links changes — captured by {@link CachedResult#linkSignature()}.
     * Reused across scans to skip the expensive BPMN model parse + forward-graph walk.
     * Rebuilt every scan so ids no longer in the latest set are evicted.
     */
    private Map<String, CachedResult> cache = Map.of();

    /** Spring's shared scheduler, used to defer the jittered scan without blocking a thread. */
    private final TaskScheduler taskScheduler;

    public EpistolaProcessDefinitionValidator(
            RepositoryService repositoryService,
            ProcessLinkService processLinkService,
            TaskScheduler taskScheduler,
            String cron,
            String zone
    ) {
        this.repositoryService = repositoryService;
        this.processLinkService = processLinkService;
        this.taskScheduler = taskScheduler;
        this.refreshIntervalMs = estimateIntervalMs(cron, zone);
    }

    /**
     * Derive the scan period (ms) from the cron schedule by measuring the gap between its
     * next two fire times — exact for fixed-period crons like {@code 0 *​/10 * * * *}, and a
     * close approximation for irregular ones (the UI only states "roughly every N min").
     * Falls back to 10 minutes if the cron can't be parsed (in which case Spring's scheduler
     * would also fail fast at startup).
     */
    private static long estimateIntervalMs(String cron, String zone) {
        try {
            CronExpression expression = CronExpression.parse(cron);
            ZonedDateTime base = ZonedDateTime.now(ZoneId.of(zone));
            ZonedDateTime first = expression.next(base);
            ZonedDateTime second = first == null ? null : expression.next(first);
            if (first != null && second != null) {
                return Duration.between(first, second).toMillis();
            }
        } catch (Exception e) {
            log.warn("Could not derive scan interval from cron '{}' (zone '{}'): {}",
                    cron, zone, e.getMessage());
        }
        return FALLBACK_INTERVAL_MS;
    }

    public List<BpmnValidationViolation> getViolations() {
        return violations.get();
    }

    /** When the last scan finished, or {@code null} if no scan has run yet. */
    public Instant getLastCheckedAt() {
        return lastCheckedAt.get();
    }

    /** The scan interval in milliseconds, derived from the cron schedule. */
    public long getRefreshIntervalMs() {
        return refreshIntervalMs;
    }

    /** Cached validation outcome for one deployed process-definition version. */
    private record CachedResult(List<String> linkSignature, List<BpmnValidationViolation> violations) {}

    /**
     * One-shot scan once the context is ready, so a freshly (re)started node populates its
     * snapshot immediately instead of showing nothing until the first cron boundary. Runs
     * without jitter — the alignment/herd concern only applies to the periodic cron ticks.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void scanOnStartup() {
        scan();
    }

    /**
     * Cron entry point. Using a wall-clock cron (not a per-instance fixed delay) keeps the scans
     * aligned across cluster nodes — every node fires on the same boundary; {@code zone} defaults
     * to UTC so alignment holds regardless of each node's local timezone. The actual scan is then
     * deferred by a small random {@value #MIN_JITTER_MS}–{@value #MAX_JITTER_MS} ms jitter so the
     * nodes don't all hit the engine/DB at the same instant (still within a minute of each other).
     * Deferring via {@link TaskScheduler#schedule} rather than sleeping keeps the scheduler thread
     * free during the wait.
     */
    @Scheduled(
            cron = "${epistola.validator.cron:0 */10 * * * *}",
            zone = "${epistola.validator.zone:UTC}"
    )
    public void scheduledScan() {
        taskScheduler.schedule(this::scan, Instant.now().plusMillis(nextJitterMs()));
    }

    /** A random jitter in {@code [MIN_JITTER_MS, MAX_JITTER_MS]} milliseconds. */
    long nextJitterMs() {
        return ThreadLocalRandom.current().nextLong(MIN_JITTER_MS, MAX_JITTER_MS + 1);
    }

    /**
     * Run the validation scan now. Cluster-aligned periodic ticks reach this via
     * {@link #scheduledScan()} (with jitter); startup reaches it via {@link #scanOnStartup()}.
     */
    public synchronized void scan() {
        Map<String, CachedResult> previous = cache;
        Map<String, CachedResult> next = new HashMap<>();
        List<BpmnValidationViolation> found = new ArrayList<>();
        try {
            List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                    .latestVersion()
                    .list();
            for (ProcessDefinition definition : definitions) {
                try {
                    List<PluginProcessLink> links = generateDocumentLinks(definition);
                    List<String> signature = links.stream()
                            .map(PluginProcessLink::getActivityId)
                            .sorted()
                            .toList();
                    CachedResult cached = previous.get(definition.getId());
                    List<BpmnValidationViolation> result = (cached != null
                            && cached.linkSignature().equals(signature))
                            ? cached.violations()
                            : validate(definition, links);
                    next.put(definition.getId(), new CachedResult(signature, result));
                    found.addAll(result);
                } catch (Exception e) {
                    log.debug("Skipped validation for process definition {}: {}",
                            definition.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to enumerate process definitions for race-safety validation: {}", e.getMessage());
            return;
        }

        cache = next;
        violations.set(Collections.unmodifiableList(found));
        lastCheckedAt.set(Instant.now());
        if (!found.isEmpty()) {
            log.warn("Epistola race-safety validation found {} violation(s):", found.size());
            for (BpmnValidationViolation v : found) {
                log.warn("  [{}] processDefinition={} activity={} — {}",
                        v.code(), v.processDefinitionKey(), v.activityId(), v.message());
            }
        } else {
            log.debug("Epistola race-safety validation: no violations");
        }
    }

    /** The {@code generate-document} plugin process links bound to this definition version. */
    private List<PluginProcessLink> generateDocumentLinks(ProcessDefinition definition) {
        return processLinkService.getProcessLinks(definition.getId()).stream()
                .filter(PluginProcessLink.class::isInstance)
                .map(PluginProcessLink.class::cast)
                .filter(link -> GENERATE_DOCUMENT_ACTION_KEY.equals(link.getPluginActionDefinitionKey()))
                .toList();
    }

    private List<BpmnValidationViolation> validate(ProcessDefinition definition, List<PluginProcessLink> links) {
        if (links.isEmpty()) {
            return List.of();
        }

        BpmnModelInstance model = repositoryService.getBpmnModelInstance(definition.getId());
        if (model == null) {
            return List.of();
        }

        String displayName = definition.getName() != null ? definition.getName() : definition.getKey();
        List<BpmnValidationViolation> result = new ArrayList<>();

        // catch-event id -> the generate-document service-task ids that reach it, to detect ambiguous
        // pairings (two+ generate-documents flowing into one catch event the auto-wiring can't tell apart).
        Map<String, List<String>> sourcesByWait = new HashMap<>();
        // catch-event id -> the resultProcessVariable of each reaching generate-document (aligned with
        // sourcesByWait; null when a source has none). Lets us flag only the genuinely ambiguous
        // case — converging branches that DON'T all share one result variable — and stay quiet when they
        // do (the correct fix for an exclusive split that merges into a single catch event).
        Map<String, List<String>> resultVarsByWait = new HashMap<>();

        for (PluginProcessLink link : links) {
            String activityId = link.getActivityId();
            ServiceTask serviceTask = model.getModelElementById(activityId) instanceof ServiceTask st ? st : null;
            if (serviceTask == null) {
                continue;
            }

            FlowNode reachableWait = findReachableEpistolaWait(serviceTask);
            if (reachableWait == null) {
                // Variable-pattern BPMN — no catch event / receive task in the immediate forward
                // graph. Race exposure is N/A. Validator stays silent.
                continue;
            }

            sourcesByWait.computeIfAbsent(reachableWait.getId(), k -> new ArrayList<>()).add(activityId);
            resultVarsByWait.computeIfAbsent(reachableWait.getId(), k -> new ArrayList<>())
                    .add(resultVariableOf(link));

            // Platform-injected asyncAfter check: signature is expression="${null}"
            // AND asyncAfter=true AND no other handler (class/type/delegateExpression).
            // If the user authored their own camunda:expression, none of these match —
            // even if they wrote "${null}" themselves, that's a deliberate opt-out and
            // we don't second-guess it (the if-condition skips silently because the
            // user-set value is identical to what we'd flag, but the *reason* it's
            // there is intent, not platform default — there's no way to tell apart
            // and that's fine: either way Valtimo's auto-injector ran or didn't, and
            // the resulting deployed model is what runs).
            if (isPlatformInjectedAsyncAfter(serviceTask)) {
                result.add(violation(definition.getKey(), displayName, activityId,
                        BpmnValidationViolation.CODE_PLATFORM_ASYNC_AFTER_ON_SERVICE_TASK,
                        "this service task flows into the " + EpistolaProcessVariables.MESSAGE_NAME
                                + " catch event / receive task but has no camunda:expression set — Valtimo auto-enabled "
                                + "camunda:asyncAfter=\"true\" on it, opening a transactional race that can "
                                + "drop result correlations. Add camunda:expression=\"${null}\" "
                                + "(or any other expression) to the service task in your BPMN authoring "
                                + "tool to opt out."));
            }

            if (reachableWait.isOperatonAsyncBefore()) {
                result.add(violation(definition.getKey(), displayName, reachableWait.getId(),
                        BpmnValidationViolation.CODE_ASYNC_BEFORE_ON_CATCH_EVENT,
                        "remove camunda:asyncBefore on the " + EpistolaProcessVariables.MESSAGE_NAME
                                + " catch event / receive task — it must subscribe in the same transaction as the "
                                + "service-task execution to keep the result-collector race-safe."));
            }
        }

        // Ambiguous pairing: more than one generate-document reaches the same catch event. The
        // auto-wiring pins exactly one result variable's jobPath there, so a branch whose result
        // variable wasn't the one pinned gets no epistolaWaitFor token — its completion is never
        // correlated, the wait stalls, and (being token-less) it doesn't even show in admin Pending
        // Jobs. We flag this ONLY when the converging branches do not all share one (non-blank) result
        // variable: sharing one is the correct fix for an exclusive split that merges (only one branch
        // runs, so the single shared variable always resolves). Flag once per catch event.
        for (Map.Entry<String, List<String>> entry : sourcesByWait.entrySet()) {
            List<String> sources = entry.getValue();
            if (sources.size() <= 1) {
                continue;
            }
            List<String> resultVars = resultVarsByWait.getOrDefault(entry.getKey(), List.of());
            Set<String> distinctNonBlank = new HashSet<>();
            for (String v : resultVars) {
                if (v != null && !v.isBlank()) {
                    distinctNonBlank.add(v);
                }
            }
            boolean allShareOneVariable = distinctNonBlank.size() == 1
                    && resultVars.stream().allMatch(v -> v != null && !v.isBlank());
            if (allShareOneVariable) {
                continue; // safe: exclusive merge onto a single shared result variable
            }
            List<String> sortedSources = sources.stream().sorted().toList();
            List<String> shownVars = resultVars.stream().map(v -> v == null || v.isBlank() ? "<unset>" : v).sorted().toList();
            result.add(violation(definition.getKey(), displayName, entry.getKey(),
                    BpmnValidationViolation.CODE_AMBIGUOUS_CATCH_EVENT,
                    "generate-document tasks " + sortedSources + " all flow into this "
                            + EpistolaProcessVariables.MESSAGE_NAME + " catch event / receive task with different "
                            + "result variables " + shownVars + " — the auto-wiring can pin only one, so completions "
                            + "on the other branch(es) are never correlated: the process stalls at the wait and "
                            + "does not appear in admin Pending Jobs. Fix: for an exclusive split that merges, "
                            + "give every branch the SAME resultProcessVariable (only one branch runs, so the "
                            + "shared variable always resolves); for parallel branches, give each its own wait "
                            + "and its own resultProcessVariable."));
        }

        return result;
    }

    /**
     * Walk the BPMN forward graph from {@code start}, following sequence flows and
     * traversing through gateways. Returns the first reachable Epistola wait — either an
     * {@link IntermediateCatchEvent} or a {@link ReceiveTask} referencing our
     * {@link EpistolaProcessVariables#MESSAGE_NAME} message — or {@code null} if some other wait
     * state breaks every branch first. Both wait kinds are auto-wired (the start listener pins the
     * correlation token on the wait's own execution), so both must be discoverable here.
     *
     * <p>Conditions on gateway outflows are NOT evaluated — if any branch from any
     * gateway can lead to our wait, it counts. Conservative on purpose: we'd
     * rather warn on a path that's unreachable at runtime than silently miss a real race.
     */
    static FlowNode findReachableEpistolaWait(ServiceTask start) {
        Set<String> visited = new HashSet<>();
        Deque<FlowNode> queue = new ArrayDeque<>();
        for (SequenceFlow sf : start.getOutgoing()) {
            queue.add(sf.getTarget());
        }

        while (!queue.isEmpty()) {
            FlowNode node = queue.pop();
            if (node == null || !visited.add(node.getId())) {
                continue;
            }

            // Our wait — a round message catch event OR a receive task on EpistolaDocumentGenerated.
            if (isEpistolaWaitTarget(node)) {
                return node;
            }

            if (node instanceof IntermediateCatchEvent) {
                // Different message / timer / signal — also a wait state, break this branch.
                continue;
            }

            if (node instanceof Gateway) {
                for (SequenceFlow sf : node.getOutgoing()) {
                    queue.add(sf.getTarget());
                }
                continue;
            }

            // Other wait states (user task, non-Epistola receive task): break this branch.
            if (node instanceof UserTask || node instanceof ReceiveTask) {
                continue;
            }

            // Synchronous activities also break: the wait happens after them, race semantics
            // shift. We don't follow past them.
            if (node instanceof ServiceTask
                    || node instanceof ScriptTask
                    || node instanceof BusinessRuleTask) {
                continue;
            }

            // Anything else (subprocess, end event, throw event, etc.) — don't traverse.
        }

        return null;
    }

    /** The {@code resultProcessVariable} configured on a generate-document link, or {@code null} if none/blank. */
    private static String resultVariableOf(PluginProcessLink link) {
        var props = link.getActionProperties();
        if (props == null || !props.hasNonNull("resultProcessVariable")) {
            return null;
        }
        String value = props.get("resultProcessVariable").asText();
        return value.isBlank() ? null : value;
    }

    private static boolean isPlatformInjectedAsyncAfter(ServiceTask task) {
        return task.isOperatonAsyncAfter()
                && "${null}".equals(task.getOperatonExpression())
                && task.getOperatonClass() == null
                && task.getOperatonType() == null
                && task.getOperatonDelegateExpression() == null;
    }

    /**
     * Whether {@code node} is an Epistola wait — a round {@link IntermediateCatchEvent} OR a
     * {@link ReceiveTask}, in either case referencing the {@link EpistolaProcessVariables#MESSAGE_NAME}
     * message. Both are auto-wired and correlated identically.
     */
    static boolean isEpistolaWaitTarget(FlowNode node) {
        if (node instanceof IntermediateCatchEvent ice) {
            return matchesEpistolaMessage(ice);
        }
        if (node instanceof ReceiveTask receiveTask) {
            Message message = receiveTask.getMessage();
            return message != null && EpistolaProcessVariables.MESSAGE_NAME.equals(message.getName());
        }
        return false;
    }

    static boolean matchesEpistolaMessage(CatchEvent catchEvent) {
        for (EventDefinition def : catchEvent.getEventDefinitions()) {
            if (def instanceof MessageEventDefinition med) {
                Message message = med.getMessage();
                if (message != null && EpistolaProcessVariables.MESSAGE_NAME.equals(message.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BpmnValidationViolation violation(String key, String name, String activityId,
                                                     String code, String message) {
        return new BpmnValidationViolation(key, name, activityId, code, message);
    }
}
