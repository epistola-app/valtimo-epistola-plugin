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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ritense.valtimo.contract.event.TaskCompletedEvent;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the submission signal is allowed to do, and what it must not.
 *
 * <p>This listener sees every completed task in the installation, so most of these are about doing
 * nothing — cheaply, and without an opinion about processes that have nothing to do with letters.
 */
class ComposerSubmissionListenerTest {

    private static final UUID CASE = UUID.randomUUID();

    private ComposerWriteBackService writeBackService;
    private ComposerSubmissionListener listener;

    @BeforeEach
    void setUp() {
        writeBackService = mock(ComposerWriteBackService.class);
        listener = new ComposerSubmissionListener(writeBackService);
    }

    private static TaskCompletedEvent event(String businessKey, Map<String, Object> variables) {
        TaskCompletedEvent event = mock(TaskCompletedEvent.class);
        when(event.getBusinessKey()).thenReturn(businessKey);
        when(event.getVariables()).thenReturn(variables);
        return event;
    }

    @Test
    @DisplayName("a submitted letter reaches the case it was composed for")
    void appliesWriteBackForTheCase() {
        listener.onTaskCompleted(event(CASE.toString(), Map.of("epistolaLetter", Map.of("x", 1))));

        verify(writeBackService).applyFromSubmission(eq(CASE), any());
    }

    @Test
    @DisplayName("a process with no case has nowhere to write")
    void ignoresAProcessWithoutACase() {
        // Valtimo's dossier processes carry the case document id as the business key. A process
        // started without one is not an error — it just has nowhere for a letter's values to go.
        listener.onTaskCompleted(event(null, Map.of("epistolaLetter", Map.of("x", 1))));

        verify(writeBackService, never()).applyFromSubmission(any(), any());
    }

    @Test
    @DisplayName("a business key that is not a case id is left alone")
    void ignoresANonCaseBusinessKey() {
        listener.onTaskCompleted(event("order-4711", Map.of("epistolaLetter", Map.of("x", 1))));

        verify(writeBackService, never()).applyFromSubmission(any(), any());
    }

    @Test
    @DisplayName("a task that carried no variables is not worth a database lookup")
    void ignoresATaskWithoutVariables() {
        // The cheap check before the expensive one: this fires for every task in the installation,
        // and reading a case's forms to find no composer would be the wrong thing to do per task.
        listener.onTaskCompleted(event(CASE.toString(), Map.of()));
        listener.onTaskCompleted(event(CASE.toString(), null));

        verify(writeBackService, never()).applyFromSubmission(any(), any());
    }

    @Test
    @DisplayName("a write-back that fails fails the submission")
    void propagatesAFailingWriteBack() {
        // Spring dispatches this event synchronously, inside the submission's transaction, so
        // raising it rolls the submission back: measured against the running app, the submit call
        // answers 400, the task stays open and the case is untouched. Swallowing it would leave a
        // case worker believing the case records a decision it does not.
        doThrow(new ComposerWriteBackException("destination doc:/besluit/type was refused"))
                .when(writeBackService).applyFromSubmission(any(), any());

        assertThatThrownBy(() -> listener.onTaskCompleted(
                event(CASE.toString(), Map.of("epistolaLetter", Map.of("x", 1)))))
                .isInstanceOf(ComposerWriteBackException.class);
    }
}
