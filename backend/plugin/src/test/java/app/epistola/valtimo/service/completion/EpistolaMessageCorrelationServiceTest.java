package app.epistola.valtimo.service.completion;
import app.epistola.valtimo.service.completion.EpistolaMessageCorrelationService;

import app.epistola.valtimo.domain.EpistolaProcessVariables;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.operaton.bpm.engine.MismatchingMessageCorrelationException;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder;
import org.operaton.bpm.engine.runtime.MessageCorrelationResult;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstanceQuery;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpistolaMessageCorrelationServiceTest {

    private RuntimeService runtimeService;
    private MessageCorrelationBuilder correlationBuilder;
    private EpistolaMessageCorrelationService service;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger correlationLogger;

    @BeforeEach
    void setUp() {
        runtimeService = mock(RuntimeService.class);
        correlationBuilder = mock(MessageCorrelationBuilder.class);
        service = new EpistolaMessageCorrelationService(runtimeService);

        when(runtimeService.createMessageCorrelation(EpistolaProcessVariables.MESSAGE_NAME))
                .thenReturn(correlationBuilder);
        lenient().when(correlationBuilder.processInstanceVariableEquals(any(), any()))
                .thenReturn(correlationBuilder);
        lenient().when(correlationBuilder.setVariable(any(), any()))
                .thenReturn(correlationBuilder);

        // Default: no process instance matches the jobPath query (variable-pattern path is no-op).
        // Individual tests can override.
        ProcessInstanceQuery emptyQuery = mock(ProcessInstanceQuery.class);
        lenient().when(runtimeService.createProcessInstanceQuery()).thenReturn(emptyQuery);
        lenient().when(emptyQuery.variableValueEquals(anyString(), any())).thenReturn(emptyQuery);
        lenient().when(emptyQuery.list()).thenReturn(Collections.emptyList());

        correlationLogger = (Logger) LoggerFactory.getLogger(EpistolaMessageCorrelationService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        correlationLogger.addAppender(logAppender);
    }

    private void mockMatchingProcessInstance(String pid, String resultVariableName) {
        ProcessInstance pi = mock(ProcessInstance.class);
        lenient().when(pi.getId()).thenReturn(pid);

        ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(query);
        when(query.variableValueEquals(eq("epistolaJobPath"), any())).thenReturn(query);
        when(query.list()).thenReturn(List.of(pi));
        if (resultVariableName != null) {
            when(runtimeService.getVariable(pid, "epistolaResultVariableName")).thenReturn(resultVariableName);
        } else {
            when(runtimeService.getVariable(pid, "epistolaResultVariableName")).thenReturn(null);
        }
    }

    @AfterEach
    void tearDown() {
        correlationLogger.detachAppender(logAppender);
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
    void correlateCompletion_shouldLogWarnWhenZeroInstancesMatch() {
        when(correlationBuilder.correlateAllWithResult())
                .thenReturn(Collections.emptyList());

        service.correlateCompletion("tenant-a", "req-999", "FAILED", null, "validation error");

        assertThat(logAppender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    String formatted = event.getFormattedMessage();
                    assertThat(formatted).contains("Correlated 0 instances");
                    assertThat(formatted).contains("epistola:job:tenant-a/req-999");
                    assertThat(formatted).contains("FAILED");
                });
    }

    @Test
    void correlateCompletion_shouldReturnZeroOnMismatchingCorrelationException() {
        when(correlationBuilder.correlateAllWithResult())
                .thenThrow(new MismatchingMessageCorrelationException("no match"));

        int count = service.correlateCompletion("tenant-a", "req-000", "COMPLETED", "doc-1", null);

        assertThat(count).isZero();
    }

    @Test
    void correlateCompletion_writesRichObjectToConfiguredVariable() {
        when(correlationBuilder.correlateAllWithResult())
                .thenReturn(Collections.emptyList());
        mockMatchingProcessInstance("pi-1", "epistolaRequestId");

        service.correlateCompletion("tenant-a", "req-rich", "COMPLETED", "doc-rich", null);

        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(runtimeService).setVariable(eq("pi-1"), eq("epistolaRequestId"), valueCaptor.capture());
        Object value = valueCaptor.getValue();
        assertThat(value).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        assertThat(map).containsEntry("requestId", "req-rich");
        assertThat(map).containsEntry("status", "COMPLETED");
        assertThat(map).containsEntry("documentId", "doc-rich");
        assertThat(map).containsEntry("errorMessage", null);
    }

    @Test
    void correlateCompletion_writesRichObjectUnderWhateverNameUserConfigured() {
        when(correlationBuilder.correlateAllWithResult())
                .thenReturn(Collections.emptyList());
        mockMatchingProcessInstance("pi-1", "myCustomVarName");

        service.correlateCompletion("tenant-a", "req-rich", "FAILED", null, "boom");

        verify(runtimeService).setVariable(eq("pi-1"), eq("myCustomVarName"), any());
    }

    @Test
    void correlateCompletion_skipsRichObjectWhenCompanionVariableMissing() {
        when(correlationBuilder.correlateAllWithResult())
                .thenReturn(Collections.emptyList());
        mockMatchingProcessInstance("pi-1", null);

        service.correlateCompletion("tenant-a", "req-rich", "COMPLETED", "doc-1", null);

        verify(runtimeService, never()).setVariable(eq("pi-1"), anyString(), any());
    }

    @Test
    void correlateCompletion_skipsRichObjectWhenNoProcessInstanceMatches() {
        // Default setup: empty query result. The variable-pattern path is a no-op.
        when(correlationBuilder.correlateAllWithResult())
                .thenReturn(Collections.emptyList());

        service.correlateCompletion("tenant-a", "req-orphan", "COMPLETED", "doc-1", null);

        verify(runtimeService, never()).setVariable(anyString(), anyString(), any());
    }

    @Test
    void correlateCompletion_shouldLogWarnOnMismatchingCorrelationException() {
        when(correlationBuilder.correlateAllWithResult())
                .thenThrow(new MismatchingMessageCorrelationException("no match"));

        service.correlateCompletion("tenant-a", "req-000", "COMPLETED", "doc-1", null);

        assertThat(logAppender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    String formatted = event.getFormattedMessage();
                    assertThat(formatted).contains("Correlated 0 instances");
                    assertThat(formatted).contains("MismatchingMessageCorrelationException");
                    assertThat(formatted).contains("epistola:job:tenant-a/req-000");
                });
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
