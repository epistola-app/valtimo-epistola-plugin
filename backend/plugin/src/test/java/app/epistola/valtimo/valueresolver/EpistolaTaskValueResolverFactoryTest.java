package app.epistola.valtimo.valueresolver;

import com.ritense.valtimo.operaton.domain.OperatonExecution;
import com.ritense.valtimo.operaton.domain.OperatonTask;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.delegate.VariableScope;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EpistolaTaskValueResolverFactoryTest {

    private final EpistolaTaskValueResolverFactory factory = new EpistolaTaskValueResolverFactory();

    @Test
    void supportsTheEpistolaTaskPrefix() {
        assertThat(factory.supportedPrefix()).isEqualTo("epistola");
    }

    @Test
    void resolvesTaskIdentityFromTheTaskVariableScope() {
        OperatonExecution execution = mock(OperatonExecution.class);
        when(execution.getId()).thenReturn("exec-99");
        OperatonTask task = mock(OperatonTask.class);
        when(task.getId()).thenReturn("task-123");
        when(task.getTaskDefinitionKey()).thenReturn("approveDocument");
        when(task.getProcessInstanceId()).thenReturn("pi-7");
        when(task.getExecution()).thenReturn(execution);

        Function<String, Object> resolver = factory.createResolver("pi-7", task);

        assertThat(resolver.apply("taskId")).isEqualTo("task-123");
        assertThat(resolver.apply("taskDefinitionKey")).isEqualTo("approveDocument");
        assertThat(resolver.apply("processInstanceId")).isEqualTo("pi-7");
        assertThat(resolver.apply("executionId")).isEqualTo("exec-99");
    }

    @Test
    void resolvesViaThePropertyMapPath() {
        // This is the path Valtimo's form prefill actually uses:
        // resolveValues(pid, scope, keys) -> createResolver(properties map). A Java factory must route
        // it back to the (pid, scope) resolver itself; if it doesn't, the carrier field stays empty.
        OperatonTask task = mock(OperatonTask.class);
        when(task.getId()).thenReturn("task-123");

        Function<String, Object> resolver =
                factory.createResolver(Map.of("processInstanceId", "pi-7", "variableScope", task));

        assertThat(resolver.apply("taskId")).isEqualTo("task-123");
    }

    @Test
    void returnsNullForUnknownKeys() {
        OperatonTask task = mock(OperatonTask.class);
        when(task.getId()).thenReturn("task-123");

        Function<String, Object> resolver = factory.createResolver("pi-7", task);

        assertThat(resolver.apply("somethingElse")).isNull();
    }

    @Test
    void returnsNullExecutionIdWhenTaskHasNoExecution() {
        OperatonTask task = mock(OperatonTask.class);
        when(task.getExecution()).thenReturn(null);

        Function<String, Object> resolver = factory.createResolver("pi-7", task);

        assertThat(resolver.apply("executionId")).isNull();
    }

    @Test
    void resolvesNullOutsideATaskContext() {
        // A non-task VariableScope (e.g. a start form's execution) yields no task identity.
        VariableScope nonTaskScope = mock(VariableScope.class);

        Function<String, Object> resolver = factory.createResolver("pi-7", nonTaskScope);

        assertThat(resolver.apply("taskId")).isNull();
        assertThat(resolver.apply("taskDefinitionKey")).isNull();
    }

    @Test
    void documentOnlyResolverYieldsNull() {
        // No task/process context — nothing to resolve, but must not throw.
        Function<String, Object> resolver = factory.createResolver("document-id");

        assertThat(resolver.apply("taskId")).isNull();
    }

    @Test
    void validatorAcceptsAnyKey() {
        assertThat(factory.createValidator("some-document-definition").apply("taskId"))
                .isEqualTo(kotlin.Unit.INSTANCE);
    }
}
