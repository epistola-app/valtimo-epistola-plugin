package app.epistola.valtimo.service.admin;

import app.epistola.valtimo.service.admin.EpistolaFormCarrierRepairService.FormCarrierIssue;
import app.epistola.valtimo.service.admin.EpistolaFormCarrierRepairService.FormCarrierRepairResult;
import app.epistola.valtimo.service.admin.EpistolaFormCarrierRepairService.FormCarrierRepairSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.form.domain.FormIoFormDefinition;
import com.ritense.form.repository.FormDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpistolaFormCarrierRepairServiceTest {

    private static final String PREVIEW_NO_CARRIER =
            "{\"components\":[{\"type\":\"epistola-document-preview\",\"key\":\"preview\",\"input\":false}]}";
    private static final String PREVIEW_WITH_CARRIER =
            "{\"components\":[{\"type\":\"epistola-document-preview\",\"key\":\"preview\",\"input\":false,"
                    + "\"components\":[{\"type\":\"hidden\",\"key\":\"epistolaTaskInstanceId\",\"input\":true,"
                    + "\"persistent\":false,\"properties\":{\"sourceKey\":\"epistola-task:id\"}}]}]}";
    private static final String NESTED_IN_PANEL =
            "{\"components\":[{\"type\":\"panel\",\"key\":\"p\",\"components\":["
                    + "{\"type\":\"epistola-document\",\"key\":\"doc\",\"input\":false}]}]}";
    private static final String NON_EPISTOLA =
            "{\"components\":[{\"type\":\"textfield\",\"key\":\"x\",\"input\":true}]}";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EpistolaFormCarrierRepairService service(FormDefinitionRepository repo) {
        return new EpistolaFormCarrierRepairService(repo, objectMapper);
    }

    private ObjectNode parse(String json) throws Exception {
        return (ObjectNode) objectMapper.readTree(json);
    }

    // ---- pure helpers ----

    @Test
    void countsEpistolaComponentsMissingCarrier() throws Exception {
        assertThat(EpistolaFormCarrierRepairService.countComponentsMissingCarrier(parse(PREVIEW_NO_CARRIER)))
                .isEqualTo(1);
        assertThat(EpistolaFormCarrierRepairService.countComponentsMissingCarrier(parse(NESTED_IN_PANEL)))
                .isEqualTo(1);
        assertThat(EpistolaFormCarrierRepairService.countComponentsMissingCarrier(parse(PREVIEW_WITH_CARRIER)))
                .isZero();
        assertThat(EpistolaFormCarrierRepairService.countComponentsMissingCarrier(parse(NON_EPISTOLA)))
                .isZero();
    }

    @Test
    void detectsTheCarrierBySourceKey() throws Exception {
        assertThat(EpistolaFormCarrierRepairService.hasCarrier(parse(PREVIEW_WITH_CARRIER))).isTrue();
        assertThat(EpistolaFormCarrierRepairService.hasCarrier(parse(PREVIEW_NO_CARRIER))).isFalse();
    }

    @Test
    void injectsCarrierAndIsIdempotent() throws Exception {
        ObjectNode form = parse(PREVIEW_NO_CARRIER);
        assertThat(service(mock(FormDefinitionRepository.class)).injectCarriers(form)).isEqualTo(1);
        assertThat(EpistolaFormCarrierRepairService.hasCarrier(form)).isTrue();
        assertThat(service(mock(FormDefinitionRepository.class)).injectCarriers(form)).isZero();
    }

    // ---- findIssues over the repository ----

    @Test
    void findIssuesReturnsOnlyFormsWithMissingCarrier() {
        FormIoFormDefinition legacy =
                new FormIoFormDefinition(UUID.randomUUID(), "assess-objection", PREVIEW_NO_CARRIER, null, false);
        FormIoFormDefinition fixed =
                new FormIoFormDefinition(UUID.randomUUID(), "review-document", PREVIEW_WITH_CARRIER, null, false);
        FormIoFormDefinition plain =
                new FormIoFormDefinition(UUID.randomUUID(), "plain", NON_EPISTOLA, null, true);
        FormDefinitionRepository repo = mock(FormDefinitionRepository.class);
        when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(legacy, fixed, plain)));

        List<FormCarrierIssue> issues = service(repo).findIssues();

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).name()).isEqualTo("assess-objection");
        assertThat(issues.get(0).missingComponents()).isEqualTo(1);
        assertThat(issues.get(0).readOnly()).isFalse();
    }

    // ---- repair ----

    @Test
    void repairInjectsCarrierAndSaves() {
        UUID id = UUID.randomUUID();
        FormIoFormDefinition form =
                new FormIoFormDefinition(id, "assess-objection", PREVIEW_NO_CARRIER, null, false);
        FormDefinitionRepository repo = mock(FormDefinitionRepository.class);
        when(repo.findById(id)).thenReturn(Optional.of(form));

        FormCarrierRepairResult result = service(repo).repair(id);

        assertThat(result.success()).isTrue();
        assertThat(result.componentsPatched()).isEqualTo(1);
        verify(repo).save(form);
        assertThat(EpistolaFormCarrierRepairService.hasCarrier(form.getFormDefinition())).isTrue();
    }

    @Test
    void repairOfAlreadyFixedFormSavesNothing() {
        UUID id = UUID.randomUUID();
        FormIoFormDefinition form =
                new FormIoFormDefinition(id, "review-document", PREVIEW_WITH_CARRIER, null, false);
        FormDefinitionRepository repo = mock(FormDefinitionRepository.class);
        when(repo.findById(id)).thenReturn(Optional.of(form));

        FormCarrierRepairResult result = service(repo).repair(id);

        assertThat(result.success()).isTrue();
        assertThat(result.componentsPatched()).isZero();
        verify(repo, never()).save(any());
    }

    @Test
    void repairOfUnknownFormReportsFailure() {
        UUID id = UUID.randomUUID();
        FormDefinitionRepository repo = mock(FormDefinitionRepository.class);
        when(repo.findById(id)).thenReturn(Optional.empty());

        FormCarrierRepairResult result = service(repo).repair(id);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("Form not found");
    }

    @Test
    void repairAllSummarizesAcrossForms() {
        UUID id = UUID.randomUUID();
        FormIoFormDefinition legacy =
                new FormIoFormDefinition(id, "assess-objection", PREVIEW_NO_CARRIER, null, false);
        FormDefinitionRepository repo = mock(FormDefinitionRepository.class);
        when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(legacy)));
        when(repo.findById(id)).thenReturn(Optional.of(legacy));

        FormCarrierRepairSummary summary = service(repo).repairAll();

        assertThat(summary.formsRepaired()).isEqualTo(1);
        assertThat(summary.componentsPatched()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
    }
}
