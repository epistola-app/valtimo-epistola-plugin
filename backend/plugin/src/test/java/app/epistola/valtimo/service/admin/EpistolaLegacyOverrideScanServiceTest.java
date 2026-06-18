/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package app.epistola.valtimo.service.admin;

import app.epistola.valtimo.service.admin.EpistolaLegacyOverrideScanService.LegacyOverrideForm;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.form.domain.FormIoFormDefinition;
import com.ritense.form.repository.FormDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EpistolaLegacyOverrideScanServiceTest {

    // Legacy: overrideMapping is a JSON object of "form:"-refs.
    private static final String PREVIEW_LEGACY =
            "{\"components\":[{\"type\":\"epistola-document-preview\",\"key\":\"preview\","
                    + "\"overrideMapping\":{\"pv\":{\"motivation\":\"form:pv:motivation\"}}}]}";
    // Migrated: overrideMapping is a JSONata string.
    private static final String PREVIEW_MIGRATED =
            "{\"components\":[{\"type\":\"epistola-document-preview\",\"key\":\"preview\","
                    + "\"overrideMapping\":\"{ \\\"pv\\\": { \\\"motivation\\\": $form.motivation } }\"}]}";
    // No override mapping at all.
    private static final String PREVIEW_NO_OVERRIDE =
            "{\"components\":[{\"type\":\"epistola-document-preview\",\"key\":\"preview\"}]}";
    // Legacy preview nested inside a panel.
    private static final String NESTED_LEGACY =
            "{\"components\":[{\"type\":\"panel\",\"key\":\"p\",\"components\":["
                    + "{\"type\":\"epistola-document-preview\",\"key\":\"preview\","
                    + "\"overrideMapping\":{\"doc\":{\"naam\":\"form:naam\"}}}]}]}";
    private static final String NON_EPISTOLA =
            "{\"components\":[{\"type\":\"textfield\",\"key\":\"x\",\"input\":true}]}";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EpistolaLegacyOverrideScanService service(FormDefinitionRepository repo) {
        return new EpistolaLegacyOverrideScanService(repo);
    }

    private ObjectNode parse(String json) throws Exception {
        return (ObjectNode) objectMapper.readTree(json);
    }

    @Test
    void countsOnlyLegacyObjectOverrideMappings() throws Exception {
        assertThat(EpistolaLegacyOverrideScanService.countLegacyOverrideComponents(parse(PREVIEW_LEGACY)))
                .isEqualTo(1);
        assertThat(EpistolaLegacyOverrideScanService.countLegacyOverrideComponents(parse(NESTED_LEGACY)))
                .isEqualTo(1);
        assertThat(EpistolaLegacyOverrideScanService.countLegacyOverrideComponents(parse(PREVIEW_MIGRATED)))
                .isZero();
        assertThat(EpistolaLegacyOverrideScanService.countLegacyOverrideComponents(parse(PREVIEW_NO_OVERRIDE)))
                .isZero();
        assertThat(EpistolaLegacyOverrideScanService.countLegacyOverrideComponents(parse(NON_EPISTOLA)))
                .isZero();
    }

    @Test
    void findLegacyFormsReturnsOnlyFormsStillOnTheObjectFormat() {
        FormIoFormDefinition legacy =
                new FormIoFormDefinition(UUID.randomUUID(), "assess-objection", PREVIEW_LEGACY, null, false);
        FormIoFormDefinition migrated =
                new FormIoFormDefinition(UUID.randomUUID(), "assess-objection-v2", PREVIEW_MIGRATED, null, false);
        FormIoFormDefinition plain =
                new FormIoFormDefinition(UUID.randomUUID(), "plain", NON_EPISTOLA, null, true);
        FormDefinitionRepository repo = mock(FormDefinitionRepository.class);
        when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(legacy, migrated, plain)));

        List<LegacyOverrideForm> forms = service(repo).findLegacyForms();

        assertThat(forms).hasSize(1);
        assertThat(forms.get(0).name()).isEqualTo("assess-objection");
        assertThat(forms.get(0).legacyComponents()).isEqualTo(1);
        assertThat(forms.get(0).readOnly()).isFalse();
    }

    @Test
    void findLegacyFormsPagesThroughEveryForm() {
        FormIoFormDefinition firstPage =
                new FormIoFormDefinition(UUID.randomUUID(), "page-0-form", PREVIEW_LEGACY, null, false);
        FormIoFormDefinition secondPage =
                new FormIoFormDefinition(UUID.randomUUID(), "page-1-form", NESTED_LEGACY, null, false);
        FormDefinitionRepository repo = mock(FormDefinitionRepository.class);
        when(repo.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(firstPage), PageRequest.of(0, 50), 60))
                .thenReturn(new PageImpl<>(List.of(secondPage), PageRequest.of(1, 50), 60));

        List<LegacyOverrideForm> forms = service(repo).findLegacyForms();

        assertThat(forms).extracting(LegacyOverrideForm::name)
                .containsExactlyInAnyOrder("page-0-form", "page-1-form");
    }
}
