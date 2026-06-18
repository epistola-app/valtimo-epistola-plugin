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
