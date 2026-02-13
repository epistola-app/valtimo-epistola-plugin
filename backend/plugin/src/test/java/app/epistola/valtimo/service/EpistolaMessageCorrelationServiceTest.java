package app.epistola.valtimo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.MismatchingMessageCorrelationException;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder;
import org.operaton.bpm.engine.runtime.MessageCorrelationResult;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpistolaMessageCorrelationServiceTest {

    private RuntimeService runtimeService;
    private MessageCorrelationBuilder correlationBuilder;
    private EpistolaMessageCorrelationService service;

    @BeforeEach
    void setUp() {
        runtimeService = mock(RuntimeService.class);
        correlationBuilder = mock(MessageCorrelationBuilder.class);
        service = new EpistolaMessageCorrelationService(runtimeService);

        when(runtimeService.createMessageCorrelation(EpistolaMessageCorrelationService.MESSAGE_NAME))
                .thenReturn(correlationBuilder);
        when(correlationBuilder.processInstanceVariableEquals(any(), any()))
                .thenReturn(correlationBuilder);
        when(correlationBuilder.setVariable(any(), any()))
                .thenReturn(correlationBuilder);
    }

    @Test
    void correlateCompletion_shouldCorrelateWithCorrectVariables() {
        when(correlationBuilder.correlateAllWithResult())
                .thenReturn(List.of(mock(MessageCorrelationResult.class)));

        int count = service.correlateCompletion("req-123", "COMPLETED", "doc-456", null);

        assertThat(count).isEqualTo(1);
        verify(correlationBuilder).processInstanceVariableEquals("epistolaRequestId", "req-123");
        verify(correlationBuilder).setVariable("epistolaStatus", "COMPLETED");
        verify(correlationBuilder).setVariable("epistolaDocumentId", "doc-456");
        verify(correlationBuilder).setVariable("epistolaErrorMessage", null);
        verify(correlationBuilder).correlateAllWithResult();
    }

    @Test
    void correlateCompletion_shouldReturnZeroWhenNoMatchingProcesses() {
        when(correlationBuilder.correlateAllWithResult())
                .thenReturn(Collections.emptyList());

        int count = service.correlateCompletion("req-999", "COMPLETED", "doc-1", null);

        assertThat(count).isZero();
    }

    @Test
    void correlateCompletion_shouldReturnZeroOnMismatchingCorrelationException() {
        when(correlationBuilder.correlateAllWithResult())
                .thenThrow(new MismatchingMessageCorrelationException("no match"));

        int count = service.correlateCompletion("req-000", "COMPLETED", "doc-1", null);

        assertThat(count).isZero();
    }

    @Test
    void correlateCompletion_shouldCorrelateMultipleInstances() {
        when(correlationBuilder.correlateAllWithResult())
                .thenReturn(List.of(
                        mock(MessageCorrelationResult.class),
                        mock(MessageCorrelationResult.class),
                        mock(MessageCorrelationResult.class)
                ));

        int count = service.correlateCompletion("req-multi", "COMPLETED", "doc-multi", null);

        assertThat(count).isEqualTo(3);
    }

    @Test
    void correlateCompletion_shouldPassErrorMessageForFailedJobs() {
        when(correlationBuilder.correlateAllWithResult())
                .thenReturn(List.of(mock(MessageCorrelationResult.class)));

        service.correlateCompletion("req-fail", "FAILED", null, "Template rendering error");

        verify(correlationBuilder).setVariable("epistolaStatus", "FAILED");
        verify(correlationBuilder).setVariable("epistolaDocumentId", null);
        verify(correlationBuilder).setVariable("epistolaErrorMessage", "Template rendering error");
    }
}
