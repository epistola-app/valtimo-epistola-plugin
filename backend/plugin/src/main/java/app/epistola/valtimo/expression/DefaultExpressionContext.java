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
