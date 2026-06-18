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

import app.epistola.valtimo.expression.functions.FormatDateFunction;
import app.epistola.valtimo.expression.functions.StringFunctions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionFunctionRegistryTest {

    private ExpressionFunctionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ExpressionFunctionRegistry(List.of(
                new FormatDateFunction(),
                new StringFunctions()
        ));
    }

    @Test
    void shouldDiscoverRegisteredFunctions() {
        assertNotNull(registry.getFunction("formatDate"));
        assertNotNull(registry.getFunction("str"));
        assertNull(registry.getFunction("nonExistent"));
    }

    @Test
    void shouldDiscoverExecuteMethods() {
        ExpressionFunctionRegistry.RegisteredFunction formatDate = registry.getFunction("formatDate");
        assertNotNull(formatDate);
        // FormatDateFunction has 3 execute overloads: (ctx, LocalDate, String), (ctx, LocalDateTime, String), (ctx, String, String)
        assertEquals(3, formatDate.methods().size());
    }

    @Test
    void shouldListFunctionsWithOverloadMetadata() {
        List<ExpressionFunctionInfo> functions = registry.listFunctions();
        assertEquals(2, functions.size());

        ExpressionFunctionInfo formatDate = functions.stream()
                .filter(f -> "formatDate".equals(f.name()))
                .findFirst()
                .orElseThrow();
        assertEquals("Format a date to a string using a DateTimeFormatter pattern", formatDate.description());
        assertEquals(3, formatDate.overloads().size());

        // Check that argument names are extracted (requires -parameters compiler flag)
        ExpressionFunctionInfo.OverloadInfo overload = formatDate.overloads().stream()
                .filter(o -> o.arguments().size() == 2 &&
                        "LocalDate".equals(o.arguments().get(0).type()))
                .findFirst()
                .orElseThrow();
        assertEquals("date", overload.arguments().get(0).name());
        assertEquals("pattern", overload.arguments().get(1).name());
        assertEquals("String", overload.returnType());
    }

    @Test
    void shouldFindExactMatchOverload() {
        ExpressionFunctionRegistry.MethodMatch match =
                registry.findMatchingOverload("formatDate", new Object[]{LocalDate.of(2024, 1, 15), "dd-MM-yyyy"});
        assertNotNull(match);
        assertEquals("formatDate", match.bean().name());
    }

    @Test
    void shouldFindAssignableMatchOverload() {
        // String args should match the (ctx, String, String) overload
        ExpressionFunctionRegistry.MethodMatch match =
                registry.findMatchingOverload("formatDate", new Object[]{"2024-01-15", "dd-MM-yyyy"});
        assertNotNull(match);
    }

    @Test
    void shouldThrowForUnknownFunction() {
        ExpressionEvaluationException ex = assertThrows(ExpressionEvaluationException.class, () ->
                registry.findMatchingOverload("unknown", new Object[]{}));
        assertTrue(ex.getMessage().contains("Unknown expression function"));
        assertTrue(ex.getMessage().contains("unknown"));
    }

    @Test
    void shouldThrowForNoMatchingOverload() {
        // FormatDateFunction doesn't have a (ctx, Integer) overload
        ExpressionEvaluationException ex = assertThrows(ExpressionEvaluationException.class, () ->
                registry.findMatchingOverload("formatDate", new Object[]{42}));
        assertTrue(ex.getMessage().contains("No matching overload"));
        assertTrue(ex.getMessage().contains("Available overloads"));
    }

    @Test
    void shouldHandleNullArguments() {
        // null should be assignable to any non-primitive parameter
        ExpressionFunctionRegistry.MethodMatch match =
                registry.findMatchingOverload("str", new Object[]{null});
        assertNotNull(match);
    }
}
