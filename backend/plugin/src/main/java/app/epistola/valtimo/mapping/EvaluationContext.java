package app.epistola.valtimo.mapping;

import org.operaton.bpm.engine.delegate.DelegateExecution;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Context for JSONata data mapping evaluation.
 * <p>
 * Holds delegates (how to resolve data) rather than data itself.
 * The service uses these delegates to lazily construct the $doc and $pv
 * bindings, and passes a fully populated ExpressionContext to custom functions.
 */
public class EvaluationContext {

    private final String expression;
    private final Function<String, Map<String, Object>> documentResolver;
    private final Function<String, Object> processVariableResolver;
    private final Supplier<Map<String, Object>> processVariableEnumerator;
    private final String documentId;
    private final DelegateExecution execution;

    private EvaluationContext(Builder builder) {
        this.expression = builder.expression;
        this.documentResolver = builder.documentResolver;
        this.processVariableResolver = builder.processVariableResolver;
        this.processVariableEnumerator = builder.processVariableEnumerator;
        this.documentId = builder.documentId;
        this.execution = builder.execution;
    }

    public String getExpression() { return expression; }
    public Function<String, Map<String, Object>> getDocumentResolver() { return documentResolver; }
    public Function<String, Object> getProcessVariableResolver() { return processVariableResolver; }
    public Supplier<Map<String, Object>> getProcessVariableEnumerator() { return processVariableEnumerator; }
    public String getDocumentId() { return documentId; }
    public DelegateExecution getExecution() { return execution; }

    /** Return a copy with a different expression, keeping all resolvers. */
    public EvaluationContext withExpression(String newExpression) {
        return builder()
                .expression(newExpression)
                .documentResolver(this.documentResolver)
                .processVariableResolver(this.processVariableResolver)
                .processVariableEnumerator(this.processVariableEnumerator)
                .documentId(this.documentId)
                .execution(this.execution)
                .build();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String expression;
        private Function<String, Map<String, Object>> documentResolver;
        private Function<String, Object> processVariableResolver;
        private Supplier<Map<String, Object>> processVariableEnumerator;
        private String documentId;
        private DelegateExecution execution;

        public Builder expression(String expression) {
            this.expression = expression;
            return this;
        }

        public Builder documentResolver(Function<String, Map<String, Object>> documentResolver) {
            this.documentResolver = documentResolver;
            return this;
        }

        public Builder processVariableResolver(Function<String, Object> processVariableResolver) {
            this.processVariableResolver = processVariableResolver;
            return this;
        }

        /**
         * Optional bulk supplier for {@code $pv} enumeration ({@code $keys($pv)},
         * {@code $each($pv, ...)}, {@code $pv.*}). When unset, those operations return empty.
         */
        public Builder processVariableEnumerator(Supplier<Map<String, Object>> processVariableEnumerator) {
            this.processVariableEnumerator = processVariableEnumerator;
            return this;
        }

        public Builder documentId(String documentId) {
            this.documentId = documentId;
            return this;
        }

        public Builder execution(DelegateExecution execution) {
            this.execution = execution;
            return this;
        }

        public EvaluationContext build() {
            return new EvaluationContext(this);
        }
    }
}
