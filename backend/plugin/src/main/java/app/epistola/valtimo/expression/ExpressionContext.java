package app.epistola.valtimo.expression;

import org.operaton.bpm.engine.delegate.DelegateExecution;

import java.util.Map;

/**
 * Context provided to expression functions during evaluation.
 * Gives functions access to the current execution state and resolved data.
 */
public interface ExpressionContext {

    /**
     * The Operaton execution context, or {@code null} when called from a REST endpoint.
     */
    DelegateExecution getExecution();

    /**
     * The Valtimo document instance ID, or {@code null} when not available.
     */
    String getDocumentId();

    /**
     * Resolved document data (from {@code doc:} and {@code case:} expressions).
     */
    Map<String, Object> getDocumentData();

    /**
     * Process variables, or an empty map when called outside a process context.
     */
    Map<String, Object> getProcessVariables();

    /**
     * The data mapping resolved so far (standard prefixes resolved, expressions pending).
     */
    Map<String, Object> getResolvedMapping();
}
