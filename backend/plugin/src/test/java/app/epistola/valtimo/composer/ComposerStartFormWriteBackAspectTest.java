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
package app.epistola.valtimo.composer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.form.web.rest.dto.FormSubmissionResultFailed;
import com.ritense.form.web.rest.dto.FormSubmissionResultSucceeded;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The start-form half of write-back: which submissions it claims, and which it leaves alone.
 */
class ComposerStartFormWriteBackAspectTest {

    private static final UUID CASE = UUID.randomUUID();
    private static final UUID PROCESS_LINK = UUID.randomUUID();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ComposerWriteBackService writeBackService;
    private ComposerStartFormWriteBackAspect aspect;

    @BeforeEach
    void setUp() {
        writeBackService = mock(ComposerWriteBackService.class);
        aspect = new ComposerStartFormWriteBackAspect(writeBackService, MAPPER);
    }

    private static JsonNode submission(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode withLetter() {
        return submission("""
                {"submit": true, "pv:epistolaLetter":
                  {"schemaVersion": 1, "catalogId": "gemeente", "templateId": "besluit",
                   "data": {"naam": "Jansen"}, "inputs": {"decisionType": "gegrond"}}}
                """);
    }

    @Test
    @DisplayName("an ad-hoc letter on an existing case reaches that case")
    void appliesForAStartFormOnAnExistingCase() {
        aspect.afterStartFormSubmission(new FormSubmissionResultSucceeded(CASE.toString()),
                PROCESS_LINK, withLetter(), "correspondentie", CASE.toString(), null);

        verify(writeBackService).applyFromSubmission(eq(CASE), argThat(
                (Map<String, Object> vars) -> vars.containsKey("epistolaLetter")
                        // Handed over as a Map, which is the kind of value a process variable holds:
                        // DynamicDocument accepts nothing else but a JSON string, and a JsonNode
                        // failed with "the composed letter could not be read".
                        && vars.get("epistolaLetter") instanceof Map));
    }

    @Test
    @DisplayName("a case created by this very submission is found from the result")
    void appliesForAStartFormThatCreatesTheCase() {
        aspect.afterStartFormSubmission(new FormSubmissionResultSucceeded(CASE.toString()),
                PROCESS_LINK, withLetter(), "correspondentie", null, null);

        verify(writeBackService).applyFromSubmission(eq(CASE), any());
    }

    @Test
    @DisplayName("a submission that completed a task is left to the listener")
    void leavesTaskSubmissionsAlone() {
        // Both firing would write the same values twice, which is one case version per trigger.
        aspect.afterStartFormSubmission(new FormSubmissionResultSucceeded(CASE.toString()),
                PROCESS_LINK, withLetter(), "correspondentie", CASE.toString(), "task-1");

        verify(writeBackService, never()).applyFromSubmission(any(), any());
    }

    @Test
    @DisplayName("a refused submission writes nothing")
    void ignoresARefusedSubmission() {
        aspect.afterStartFormSubmission(
                new FormSubmissionResultFailed(List.of(
                        new com.ritense.valtimo.contract.result.OperationError.FromString("nee"))),
                PROCESS_LINK, withLetter(), "correspondentie", CASE.toString(), null);

        verify(writeBackService, never()).applyFromSubmission(any(), any());
    }

    @Test
    @DisplayName("a submission carrying no pv: field has no letter to write back")
    void ignoresASubmissionWithoutALetter() {
        aspect.afterStartFormSubmission(new FormSubmissionResultSucceeded(CASE.toString()),
                PROCESS_LINK, submission("{\"objector\": {\"firstName\": \"Jan\"}}"),
                "correspondentie", CASE.toString(), null);

        verify(writeBackService, never()).applyFromSubmission(any(), any());
    }

    @Test
    @DisplayName("a pv: field that is not an object cannot be a letter")
    void ignoresANonObjectProcessVariable() {
        aspect.afterStartFormSubmission(new FormSubmissionResultSucceeded(CASE.toString()),
                PROCESS_LINK, submission("{\"pv:iets\": \"een tekst\"}"),
                "correspondentie", CASE.toString(), null);

        verify(writeBackService, never()).applyFromSubmission(any(), any());
    }
}
