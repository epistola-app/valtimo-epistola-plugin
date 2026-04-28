package app.epistola.valtimo.expression;

import org.operaton.bpm.engine.delegate.DelegateExecution;

import java.util.Map;

/**
 * Default immutable implementation of {@link ExpressionContext}.
 */
public record DefaultExpressionContext(
        DelegateExecution execution,
        String documentId,
        Map<String, Object> documentData,
        Map<String, Object> processVariables,
        Map<String, Object> resolvedMapping
) implements ExpressionContext {

    @Override
    public DelegateExecution getExecution() {
        return execution;
    }

    @Override
    public String getDocumentId() {
        return documentId;
    }

    @Override
    public Map<String, Object> getDocumentData() {
        return documentData != null ? documentData : Map.of();
    }

    @Override
    public Map<String, Object> getProcessVariables() {
        return processVariables != null ? processVariables : Map.of();
    }

    @Override
    public Map<String, Object> getResolvedMapping() {
        return resolvedMapping != null ? resolvedMapping : Map.of();
    }
}
