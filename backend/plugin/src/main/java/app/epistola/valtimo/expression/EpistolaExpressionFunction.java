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

/**
 * Marker interface for Spring beans that are callable from {@code expr:} expressions
 * in Epistola data mappings.
 * <p>
 * Implementations define one or more {@code execute(ExpressionContext, ...)} methods
 * with typed parameters. The framework discovers these via reflection and matches
 * the best overload at runtime based on argument types.
 * <p>
 * <b>Convention:</b>
 * <ul>
 *   <li>Method name must be {@code execute}</li>
 *   <li>First parameter must be {@link ExpressionContext}</li>
 *   <li>Remaining parameters are the user-provided arguments (typed)</li>
 *   <li>Multiple overloads are supported</li>
 *   <li>Return type is the expression result</li>
 * </ul>
 * <p>
 * Example usage in a data mapping value:
 * <pre>expr:formatDate(#doc['invoice.date'], 'dd-MM-yyyy')</pre>
 * <p>
 * Example implementation:
 * <pre>
 * &#64;Component
 * public class FormatDateFunction implements EpistolaExpressionFunction {
 *     public String name() { return "formatDate"; }
 *     public String description() { return "Format a date to a string pattern"; }
 *
 *     public String execute(ExpressionContext ctx, LocalDate date, String pattern) {
 *         return date.format(DateTimeFormatter.ofPattern(pattern));
 *     }
 * }
 * </pre>
 */
public interface EpistolaExpressionFunction {

    /**
     * The identifier used in {@code expr:} expressions.
     * Must be a valid Java identifier (e.g., "formatDate", "str").
     */
    String name();

    /**
     * A human-readable description shown in the frontend UI.
     */
    String description();
}
