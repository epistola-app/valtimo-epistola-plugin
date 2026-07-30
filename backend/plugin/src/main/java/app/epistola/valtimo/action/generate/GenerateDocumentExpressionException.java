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

    private GenerateDocumentExpressionException(String message, Throwable cause) {
        super(message, cause);
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

        return new GenerateDocumentExpressionException(message.toString(), cause);
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
