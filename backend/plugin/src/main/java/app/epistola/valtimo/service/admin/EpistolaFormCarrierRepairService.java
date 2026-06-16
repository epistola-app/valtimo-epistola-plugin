package app.epistola.valtimo.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.form.domain.FormIoFormDefinition;
import com.ritense.form.repository.FormDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * <b>TEMPORARY — remove in 1.0.0.</b>
 *
 * <p>Detects and repairs forms authored <i>before</i> the Epistola task-id carrier was embedded in the
 * components' Formio schema. Such a form has an {@code epistola-document-preview} / {@code epistola-document}
 * / {@code epistola-retry-form} component but no carrier (a hidden field with
 * {@code properties.sourceKey = "epistola-task:id"}), so form prefill has nothing to fill and the component
 * fails with "… only available from within a user task".
 *
 * <p>Surfaced on the admin page: {@link #findIssues()} lists the affected forms and {@link #repair(UUID)}
 * injects the carrier into a form's Epistola components ({@link #repairAll()} does the lot). Repair is an
 * explicit admin action — unlike a startup migration it runs after all form auto-deployment, so it never
 * races the reconciliation.
 *
 * <p><b>Note on classpath forms.</b> A repair persists for forms authored in the form-management UI. Forms
 * deployed from a host's classpath are reconciled to their (carrier-less) source on the next boot, so they
 * re-appear here until the carrier is added to their source (re-drop the component) — {@link FormCarrierIssue#readOnly()}
 * flags those.
 */
@Slf4j
public class EpistolaFormCarrierRepairService {

    private static final Set<String> EPISTOLA_TYPES =
            Set.of("epistola-document-preview", "epistola-document", "epistola-retry-form");
    private static final String TYPE_FIELD = "type";
    private static final String COMPONENTS_FIELD = "components";
    private static final String PROPERTIES_FIELD = "properties";
    private static final String SOURCE_KEY_FIELD = "sourceKey";
    private static final String SOURCE_KEY = "epistola-task:id";
    private static final String CARRIER_KEY = "epistolaTaskInstanceId";
    private static final int PAGE_SIZE = 50;

    private final FormDefinitionRepository formDefinitionRepository;
    private final ObjectMapper objectMapper;

    public EpistolaFormCarrierRepairService(
            FormDefinitionRepository formDefinitionRepository, ObjectMapper objectMapper) {
        this.formDefinitionRepository = formDefinitionRepository;
        this.objectMapper = objectMapper;
    }

    /** A form that has an Epistola component missing the task-id carrier. */
    public record FormCarrierIssue(String formId, String name, int missingComponents, boolean readOnly) {}

    /** Outcome of repairing a single form. */
    public record FormCarrierRepairResult(
            String formId, String name, boolean success, int componentsPatched, String errorMessage) {}

    /** Aggregate outcome of {@link #repairAll()}. */
    public record FormCarrierRepairSummary(int formsRepaired, int componentsPatched, int failed) {}

    /** All forms whose Epistola components are missing the carrier. */
    public List<FormCarrierIssue> findIssues() {
        List<FormCarrierIssue> issues = new ArrayList<>();
        int page = 0;
        Page<FormIoFormDefinition> formPage;
        do {
            formPage = formDefinitionRepository.findAll(PageRequest.of(page, PAGE_SIZE));
            for (FormIoFormDefinition form : formPage) {
                try {
                    int missing = countComponentsMissingCarrier(form.getFormDefinition());
                    if (missing > 0) {
                        issues.add(new FormCarrierIssue(
                                form.getId().toString(), form.getName(), missing, form.isReadOnly()));
                    }
                } catch (Exception e) {
                    log.warn("Could not inspect form '{}' ({}) for missing carrier",
                            form.getName(), form.getId(), e);
                }
            }
            page++;
        } while (formPage.hasNext());
        return issues;
    }

    /** Injects the carrier into the given form's Epistola components. */
    public FormCarrierRepairResult repair(UUID formId) {
        FormIoFormDefinition form = formDefinitionRepository.findById(formId).orElse(null);
        if (form == null) {
            return new FormCarrierRepairResult(formId.toString(), null, false, 0, "Form not found");
        }
        try {
            JsonNode root = form.getFormDefinition();
            if (root == null || !root.isObject()) {
                return new FormCarrierRepairResult(formId.toString(), form.getName(), true, 0, null);
            }
            ObjectNode patched = (ObjectNode) root.deepCopy();
            int injected = injectCarriers(patched);
            if (injected > 0) {
                form.isWriting();
                form.changeDefinition(patched.toString());
                form.doneWriting();
                formDefinitionRepository.save(form);
                log.info("Repaired Epistola task-id carrier in form '{}' ({}): {} component(s)",
                        form.getName(), formId, injected);
            }
            return new FormCarrierRepairResult(formId.toString(), form.getName(), true, injected, null);
        } catch (Exception e) {
            log.warn("Failed to repair Epistola task-id carrier in form '{}' ({})", form.getName(), formId, e);
            return new FormCarrierRepairResult(formId.toString(), form.getName(), false, 0,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Repairs every form returned by {@link #findIssues()}. */
    public FormCarrierRepairSummary repairAll() {
        int formsRepaired = 0;
        int componentsPatched = 0;
        int failed = 0;
        for (FormCarrierIssue issue : findIssues()) {
            FormCarrierRepairResult result = repair(UUID.fromString(issue.formId()));
            if (result.success()) {
                if (result.componentsPatched() > 0) {
                    formsRepaired++;
                    componentsPatched += result.componentsPatched();
                }
            } else {
                failed++;
            }
        }
        return new FormCarrierRepairSummary(formsRepaired, componentsPatched, failed);
    }

    // ---- pure JSON helpers (package-private for testing) ----

    /** Number of Epistola components anywhere in the form that lack the carrier. */
    static int countComponentsMissingCarrier(JsonNode node) {
        if (node == null) {
            return 0;
        }
        int count = 0;
        if (node.isArray()) {
            for (JsonNode child : node) {
                count += countComponentsMissingCarrier(child);
            }
        } else if (node.isObject()) {
            if (EPISTOLA_TYPES.contains(node.path(TYPE_FIELD).asText()) && !hasCarrier(node)) {
                count++;
            }
            for (JsonNode child : node) {
                count += countComponentsMissingCarrier(child);
            }
        }
        return count;
    }

    /** True if the node (or a descendant) already has a component with the carrier sourceKey. */
    static boolean hasCarrier(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (hasCarrier(child)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isObject()) {
            JsonNode sourceKey = node.path(PROPERTIES_FIELD).path(SOURCE_KEY_FIELD);
            if (sourceKey.isTextual() && SOURCE_KEY.equals(sourceKey.asText())) {
                return true;
            }
            for (JsonNode child : node) {
                if (hasCarrier(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Adds a carrier child to every Epistola component missing one. Returns the number added. */
    int injectCarriers(JsonNode node) {
        int injected = 0;
        if (node.isArray()) {
            for (JsonNode child : node) {
                injected += injectCarriers(child);
            }
            return injected;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (EPISTOLA_TYPES.contains(obj.path(TYPE_FIELD).asText()) && !hasCarrier(obj)) {
                componentsArray(obj).add(buildCarrier());
                injected++;
            }
            for (JsonNode child : obj) {
                injected += injectCarriers(child);
            }
        }
        return injected;
    }

    private ArrayNode componentsArray(ObjectNode component) {
        JsonNode existing = component.get(COMPONENTS_FIELD);
        if (existing instanceof ArrayNode array) {
            return array;
        }
        return component.putArray(COMPONENTS_FIELD);
    }

    private ObjectNode buildCarrier() {
        ObjectNode carrier = objectMapper.createObjectNode();
        carrier.put(TYPE_FIELD, "hidden");
        carrier.put("key", CARRIER_KEY);
        carrier.put("input", true);
        carrier.put("persistent", false);
        carrier.put("label", "Epistola Task Id");
        carrier.putObject(PROPERTIES_FIELD).put(SOURCE_KEY_FIELD, SOURCE_KEY);
        return carrier;
    }
}
