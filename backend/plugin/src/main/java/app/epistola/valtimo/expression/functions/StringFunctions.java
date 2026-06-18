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
package app.epistola.valtimo.expression.functions;

import app.epistola.valtimo.expression.ExpressionContext;
import app.epistola.valtimo.expression.EpistolaExpressionFunction;

/**
 * Expression function for common string operations.
 * <p>
 * Usage examples:
 * <pre>
 * expr:str(#doc['name'])                          → passthrough
 * expr:str(#doc['firstName'], ' ', #doc['lastName']) → concatenate
 * </pre>
 */
public class StringFunctions implements EpistolaExpressionFunction {

    @Override
    public String name() {
        return "str";
    }

    @Override
    public String description() {
        return "String utilities: passthrough, concatenation, upper/lower case";
    }

    /**
     * Convert a value to its string representation.
     */
    public String execute(ExpressionContext ctx, Object value) {
        return value != null ? value.toString() : "";
    }

    /**
     * Concatenate two strings.
     */
    public String execute(ExpressionContext ctx, String a, String b) {
        return (a != null ? a : "") + (b != null ? b : "");
    }

    /**
     * Concatenate three strings.
     */
    public String execute(ExpressionContext ctx, String a, String b, String c) {
        return (a != null ? a : "") + (b != null ? b : "") + (c != null ? c : "");
    }

    /**
     * Return {@code value} if non-null, otherwise return {@code fallback}.
     */
    public Object execute(ExpressionContext ctx, Object value, Object fallback) {
        return value != null ? value : fallback;
    }
}
