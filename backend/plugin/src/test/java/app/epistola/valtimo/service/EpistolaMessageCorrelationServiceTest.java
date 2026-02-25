package app.epistola.valtimo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.MismatchingMessageCorrelationException;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder;
import org.operaton.bpm.engine.runtime.MessageCorrelationResult;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    void correlateCompletion_shouldCorrelateWithJobPath() {
        when(correlationBuilder.correlateAllWithResult())
                .thenReturn(List.of(mock(MessageCorrelationResult.class)));

        int count = service.correlateCompletion("tenant-a", "req-123", "COMPLETED", "doc-456", null);

        assertThat(count).isEqualTo(1);
        verify(correlationBuilder).processInstanceVariableEquals(
                "epistolaJobPath", "epistola:job:tenant-a/req-123");
        verify(correlationBuilder).setVariable("epistolaStatus", "COMPLETED");
        verify(correlationBuilder).setVariable("epistolaDocumentId", "doc-456");
        verify(correlationBuilder).setVariable("epistolaErrorMessage", null);
        verify(correlationBuilder).correlateAllWithResult();
    }

    @Test
    void correlateCompletion_shouldReturnZeroWhenNoMatchingProcesses() {
        when(correlationBuilder.correlateAllWithResult())
                .thenReturn(Collections.emptyList());

        int count = service.correlateCompletion("tenant-a", "req-999", "COMPLETED", "doc-1", null);

        assertThat(count).isZero();
    }

    @Test
    void correlateCompletion_shouldReturnZeroOnMismatchingCorrelationException() {
        when(correlationBuilder.correlateAllWithResult())
                .thenThrow(new MismatchingMessageCorrelationException("no match"));

        int count = service.correlateCompletion("tenant-a", "req-000", "COMPLETED", "doc-1", null);

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

        int count = service.correlateCompletion("tenant-a", "req-multi", "COMPLETED", "doc-multi", null);

        assertThat(count).isEqualTo(3);
    }

    @Test
    void correlateCompletion_shouldPassErrorMessageForFailedJobs() {
        when(correlationBuilder.correlateAllWithResult())
                .thenReturn(List.of(mock(MessageCorrelationResult.class)));

        service.correlateCompletion("tenant-a", "req-fail", "FAILED", null, "Template rendering error");

        verify(correlationBuilder).setVariable("epistolaStatus", "FAILED");
        verify(correlationBuilder).setVariable("epistolaDocumentId", null);
        verify(correlationBuilder).setVariable("epistolaErrorMessage", "Template rendering error");
    }

    @Nested
    class JobPathFormat {

        @Test
        void buildJobPath_producesCorrectFormat() {
            String path = EpistolaMessageCorrelationService.buildJobPath("demo-tenant", "550e8400-e29b-41d4-a716-446655440000");
            assertThat(path).isEqualTo("epistola:job:demo-tenant/550e8400-e29b-41d4-a716-446655440000");
        }

        @Test
        void parseJobPath_extractsTenantAndRequestId() {
            String[] parts = EpistolaMessageCorrelationService.parseJobPath(
                    "epistola:job:demo-tenant/550e8400-e29b-41d4-a716-446655440000");
            assertThat(parts[0]).isEqualTo("demo-tenant");
            assertThat(parts[1]).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        }

        @Test
        void parseJobPath_roundTripsWithBuildJobPath() {
            String original = EpistolaMessageCorrelationService.buildJobPath("my-tenant", "abc-123");
            String[] parts = EpistolaMessageCorrelationService.parseJobPath(original);
            assertThat(parts[0]).isEqualTo("my-tenant");
            assertThat(parts[1]).isEqualTo("abc-123");
        }

        @Test
        void parseJobPath_rejectsNullInput() {
            assertThatThrownBy(() -> EpistolaMessageCorrelationService.parseJobPath(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void parseJobPath_rejectsInvalidPrefix() {
            assertThatThrownBy(() -> EpistolaMessageCorrelationService.parseJobPath("invalid:prefix/abc"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void parseJobPath_rejectsPathWithoutSlash() {
            assertThatThrownBy(() -> EpistolaMessageCorrelationService.parseJobPath("epistola:job:no-slash"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
