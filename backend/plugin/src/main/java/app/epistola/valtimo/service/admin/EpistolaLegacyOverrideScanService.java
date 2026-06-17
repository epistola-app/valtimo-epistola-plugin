package app.epistola.valtimo.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.ritense.form.domain.FormIoFormDefinition;
import com.ritense.form.repository.FormDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>TEMPORARY — remove once all forms are migrated.</b>
 *
 * <p>Detects forms whose {@code epistola-document-preview} components still carry the <i>legacy</i>
 * input-override format: an {@code overrideMapping} stored as a JSON <b>object</b>
 * ({@code { "doc": { "path": "form:fieldKey" } }}) rather than the new JSONata <b>string</b> over
 * {@code $form}.
 *
 * <p>Legacy mappings keep working — the frontend converts them on the fly — but they only persist in
 * the new format once the form is re-saved in the form builder. This scan surfaces, on the admin page,
 * which forms still need that one-time re-save.
 */
@Slf4j
public class EpistolaLegacyOverrideScanService {

    private static final String PREVIEW_TYPE = "epistola-document-preview";
    private static final String TYPE_FIELD = "type";
    private static final String OVERRIDE_MAPPING_FIELD = "overrideMapping";
    private static final int PAGE_SIZE = 50;

    private final FormDefinitionRepository formDefinitionRepository;

    public EpistolaLegacyOverrideScanService(FormDefinitionRepository formDefinitionRepository) {
        this.formDefinitionRepository = formDefinitionRepository;
    }

    /** A form with at least one component still using the legacy override-mapping object format. */
    public record LegacyOverrideForm(String formId, String name, int legacyComponents, boolean readOnly) {}

    /** All forms whose preview components still use the legacy object override format. */
    public List<LegacyOverrideForm> findLegacyForms() {
        List<LegacyOverrideForm> forms = new ArrayList<>();
        int page = 0;
        Page<FormIoFormDefinition> formPage;
        do {
            formPage = formDefinitionRepository.findAll(PageRequest.of(page, PAGE_SIZE));
            for (FormIoFormDefinition form : formPage) {
                try {
                    int legacy = countLegacyOverrideComponents(form.getFormDefinition());
                    if (legacy > 0) {
                        forms.add(new LegacyOverrideForm(
                                form.getId().toString(), form.getName(), legacy, form.isReadOnly()));
                    }
                } catch (Exception e) {
                    log.warn("Could not inspect form '{}' ({}) for legacy override mapping",
                            form.getName(), form.getId(), e);
                }
            }
            page++;
        } while (formPage.hasNext());
        return forms;
    }

    // ---- pure JSON helper (package-private for testing) ----

    /**
     * Number of {@code epistola-document-preview} components anywhere in the form whose
     * {@code overrideMapping} is a JSON object (legacy). A textual value is the migrated JSONata format
     * and is not counted.
     */
    static int countLegacyOverrideComponents(JsonNode node) {
        if (node == null) {
            return 0;
        }
        int count = 0;
        if (node.isArray()) {
            for (JsonNode child : node) {
                count += countLegacyOverrideComponents(child);
            }
        } else if (node.isObject()) {
            if (PREVIEW_TYPE.equals(node.path(TYPE_FIELD).asText())
                    && node.path(OVERRIDE_MAPPING_FIELD).isObject()) {
                count++;
            }
            for (JsonNode child : node) {
                count += countLegacyOverrideComponents(child);
            }
        }
        return count;
    }
}
