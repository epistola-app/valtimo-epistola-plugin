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
    private final String operation;
    private final String processDefinitionId;
    private final String processInstanceId;
    private final String activityId;

    private EvaluationContext(Builder builder) {
        this.expression = builder.expression;
        this.documentResolver = builder.documentResolver;
        this.processVariableResolver = builder.processVariableResolver;
        this.processVariableEnumerator = builder.processVariableEnumerator;
        this.documentId = builder.documentId;
        this.execution = builder.execution;
        this.operation = builder.operation;
        this.processDefinitionId = builder.processDefinitionId;
        this.processInstanceId = builder.processInstanceId;
        this.activityId = builder.activityId;
    }

    public String getExpression() { return expression; }
    public Function<String, Map<String, Object>> getDocumentResolver() { return documentResolver; }
    public Function<String, Object> getProcessVariableResolver() { return processVariableResolver; }
    public Supplier<Map<String, Object>> getProcessVariableEnumerator() { return processVariableEnumerator; }
    public String getDocumentId() { return documentId; }
    public DelegateExecution getExecution() { return execution; }
    public String getOperation() { return operation; }
    public String getProcessDefinitionId() { return processDefinitionId; }
    public String getProcessInstanceId() { return processInstanceId; }
    public String getActivityId() { return activityId; }

    /** Return a copy with a different expression, keeping all resolvers. */
    public EvaluationContext withExpression(String newExpression) {
        return builder()
                .expression(newExpression)
                .documentResolver(this.documentResolver)
                .processVariableResolver(this.processVariableResolver)
                .processVariableEnumerator(this.processVariableEnumerator)
                .documentId(this.documentId)
                .execution(this.execution)
                .operation(this.operation)
                .processDefinitionId(this.processDefinitionId)
                .processInstanceId(this.processInstanceId)
                .activityId(this.activityId)
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
        private String operation;
        private String processDefinitionId;
        private String processInstanceId;
        private String activityId;

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

        public Builder operation(String operation) {
            this.operation = operation;
            return this;
        }

        public Builder processDefinitionId(String processDefinitionId) {
            this.processDefinitionId = processDefinitionId;
            return this;
        }

        public Builder processInstanceId(String processInstanceId) {
            this.processInstanceId = processInstanceId;
            return this;
        }

        public Builder activityId(String activityId) {
            this.activityId = activityId;
            return this;
        }

        public EvaluationContext build() {
            return new EvaluationContext(this);
        }
    }
}
