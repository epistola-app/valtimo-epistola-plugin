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
package app.epistola.valtimo.action.generate;

import app.epistola.valtimo.mapping.EvaluationContext;
import org.operaton.bpm.engine.delegate.DelegateExecution;

import java.util.ArrayList;
import java.util.List;

public final class GenerateDocumentExpressionException extends RuntimeException {

    private static final int MAX_EXPRESSION_LENGTH = 240;

    /** What went wrong with the expression: it failed, produced something unusable, or nothing. */
    public enum Kind {
        /** The expression could not be evaluated: a JSONata error, or {@code $error(...)}. */
        EVALUATION_FAILED,
        /** It produced a value its field cannot use, such as a number for a version range. */
        INVALID_RESULT,
        /**
         * It produced no value: a path to nothing, a misspelled variable, a bare name without quotes.
         * Distinct from {@code ""}, which an author writes on purpose (ADR 0009, second addendum).
         */
        NO_VALUE
    }

    private final Kind kind;

    private GenerateDocumentExpressionException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    /**
     * An expression that produced no value where its field needs a deliberate one. Says how to write
     * each deliberate outcome, because the usual cause is a forgotten quote or a variable the case
     * does not have.
     */
    public static GenerateDocumentExpressionException noValue(
            int version, String field, String expression, String deliberateEmpty) {
        return new GenerateDocumentExpressionException(Kind.NO_VALUE, "JSONata for epistola-generate-document v"
                + version + " field '" + field + "' (expression='" + expressionSnippet(expression)
                + "') produced no value. " + deliberateEmpty, null);
    }

    public static GenerateDocumentExpressionException evaluationFailed(
            int version,
            String field,
            String expression,
            EvaluationContext context,
            RuntimeException cause
    ) {
        StringBuilder message = new StringBuilder("Failed to evaluate JSONata for ")
                .append("epistola-generate-document v")
                .append(version)
                .append(" field '")
                .append(field)
                .append("'")
                .append(" (expression='")
                .append(expressionSnippet(expression))
                .append("'");

        diagnosticContext(context).forEach(item -> message.append(", ").append(item));
        message.append(")");
        if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
            message.append(": ").append(cause.getMessage());
        }

        return new GenerateDocumentExpressionException(Kind.EVALUATION_FAILED, message.toString(), cause);
    }

    /**
     * An expression that evaluated, but to something its field cannot use. The message names the
     * field and the expression, not the value it produced, which may come from case data.
     */
    public static GenerateDocumentExpressionException invalidResult(
            int version,
            String field,
            String expression,
            String problem
    ) {
        return new GenerateDocumentExpressionException(Kind.INVALID_RESULT, "JSONata for epistola-generate-document v"
                + version + " field '" + field + "' (expression='" + expressionSnippet(expression) + "') " + problem,
                null);
    }

    public static String expressionSnippet(String expression) {
        if (expression == null) {
            return "null";
        }
        String singleLine = expression.replace('\n', ' ').replace('\r', ' ');
        if (singleLine.length() <= MAX_EXPRESSION_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, MAX_EXPRESSION_LENGTH) + "…";
    }

    private static List<String> diagnosticContext(EvaluationContext context) {
        List<String> items = new ArrayList<>();
        add(items, "operation", context.getOperation());
        add(items, "processDefinitionId", firstNonBlank(
                context.getProcessDefinitionId(),
                executionValue(context.getExecution(), DelegateExecution::getProcessDefinitionId)));
        add(items, "processInstanceId", firstNonBlank(
                context.getProcessInstanceId(),
                executionValue(context.getExecution(), DelegateExecution::getProcessInstanceId)));
        add(items, "activityId", firstNonBlank(
                context.getActivityId(),
                executionValue(context.getExecution(), DelegateExecution::getCurrentActivityId)));
        add(items, "documentId", context.getDocumentId());
        return items;
    }

    private static String executionValue(
            DelegateExecution execution,
            java.util.function.Function<DelegateExecution, String> accessor
    ) {
        if (execution == null) {
            return null;
        }
        try {
            return accessor.apply(execution);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static void add(List<String> items, String name, String value) {
        if (value != null && !value.isBlank()) {
            items.add(name + "=" + value);
        }
    }
}
