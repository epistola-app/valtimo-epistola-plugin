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
package app.epistola.valtimo.service.completion;

import app.epistola.valtimo.deployment.EpistolaCatchEventLinkResolver;
import app.epistola.valtimo.domain.EpistolaProcessVariables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.delegate.DelegateExecution;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the token-pinning behaviour of {@link EpistolaCatchEventStartListener}. The
 * after-commit self-heal hook needs a Spring transaction and is covered by the integration test; here
 * we drive {@code notify} directly (no active transaction) to assert the pinning + override rules.
 */
class EpistolaCatchEventStartListenerTest {

    private static final String DEF_ID = "process-def:1";
    private static final String CATCH_ACTIVITY = "wait-doc1";
    private static final String RESULT_VAR = "requestId1";
    private static final String JOB_PATH = "epistola:job:demo/req-1";

    private EpistolaCatchEventLinkResolver resolver;
    private EpistolaMessageCorrelationService correlationService;
    private EpistolaCatchEventStartListener listener;
    private DelegateExecution execution;

    @BeforeEach
    void setUp() {
        resolver = mock(EpistolaCatchEventLinkResolver.class);
        correlationService = mock(EpistolaMessageCorrelationService.class);
        listener = new EpistolaCatchEventStartListener(resolver, correlationService);
        execution = mock(DelegateExecution.class);
        when(execution.getProcessDefinitionId()).thenReturn(DEF_ID);
        when(execution.getCurrentActivityId()).thenReturn(CATCH_ACTIVITY);
        when(execution.getId()).thenReturn("exec-1");
    }

    @Test
    void pinsTheJobPathFromTheResolvedResultVariable() {
        when(resolver.resultVariableFor(DEF_ID, CATCH_ACTIVITY)).thenReturn(RESULT_VAR);
        when(execution.getVariableLocal(EpistolaProcessVariables.WAIT_FOR)).thenReturn(null);
        when(execution.getVariable(RESULT_VAR)).thenReturn(richResult(JOB_PATH));

        listener.notify(execution);

        verify(execution).setVariableLocal(EpistolaProcessVariables.WAIT_FOR, JOB_PATH);
    }

    @Test
    void doesNotOverwriteAnAuthorSuppliedToken() {
        when(resolver.resultVariableFor(DEF_ID, CATCH_ACTIVITY)).thenReturn(RESULT_VAR);
        when(execution.getVariableLocal(EpistolaProcessVariables.WAIT_FOR)).thenReturn("epistola:job:demo/override");

        listener.notify(execution);

        verify(execution, never()).setVariableLocal(eq(EpistolaProcessVariables.WAIT_FOR), any());
    }

    @Test
    void isANoOpForNonEpistolaCatchEvents() {
        when(resolver.resultVariableFor(DEF_ID, CATCH_ACTIVITY)).thenReturn(null);

        listener.notify(execution);

        verify(execution, never()).setVariableLocal(eq(EpistolaProcessVariables.WAIT_FOR), any());
        verify(execution, never()).getVariable(any());
    }

    @Test
    void swallowsExceptionsSoItNeverBreaksCatchEventEntry() {
        // This listener fires on EVERY message catch event in the app; a failure must never propagate
        // and break an unrelated process's catch-event entry.
        when(resolver.resultVariableFor(DEF_ID, CATCH_ACTIVITY)).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> listener.notify(execution)).doesNotThrowAnyException();
    }

    @Test
    void doesNotPinWhenResultHasNoJobPath() {
        when(resolver.resultVariableFor(DEF_ID, CATCH_ACTIVITY)).thenReturn(RESULT_VAR);
        when(execution.getVariableLocal(EpistolaProcessVariables.WAIT_FOR)).thenReturn(null);
        when(execution.getVariable(RESULT_VAR)).thenReturn(richResult(null));

        listener.notify(execution);

        verify(execution, never()).setVariableLocal(eq(EpistolaProcessVariables.WAIT_FOR), any());
    }

    private Map<String, Object> richResult(String jobPath) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(EpistolaProcessVariables.RESULT_KEY_STATUS, "PENDING");
        map.put(EpistolaProcessVariables.RESULT_KEY_JOB_PATH, jobPath);
        return map;
    }
}
