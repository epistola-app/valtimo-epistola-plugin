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
import org.springframework.aop.framework.ProxyFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

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

    @Test
    void shouldPublishOverloadSpecificResultSchemasWithoutInvokingFunctions() {
        SchemaFunction function = new SchemaFunction();
        ExpressionFunctionRegistry schemaRegistry = new ExpressionFunctionRegistry(List.of(function));

        ExpressionFunctionInfo info = schemaRegistry.listFunctions().getFirst();
        ExpressionFunctionInfo.OverloadInfo stringOverload = info.overloads().stream()
                .filter(overload -> "String".equals(overload.returnType()))
                .findFirst()
                .orElseThrow();
        ExpressionFunctionInfo.OverloadInfo mapOverload = info.overloads().stream()
                .filter(overload -> "Map".equals(overload.returnType()))
                .findFirst()
                .orElseThrow();

        assertNotNull(mapOverload.resultSchema());
        assertEquals("object", mapOverload.resultSchema().path("type").asText());
        assertEquals("Full name", mapOverload.resultSchema().path("properties").path("name")
                .path("description").asText());
        assertNull(mapOverload.schemaDiagnostic());
        assertNull(stringOverload.resultSchema());
        assertNull(stringOverload.schemaDiagnostic());
        assertEquals(0, function.invocations);
    }

    @Test
    void shouldReportMalformedAndMissingSchemasWithoutBreakingOtherFunctions() {
        ExpressionFunctionRegistry schemaRegistry = new ExpressionFunctionRegistry(List.of(
                new MalformedSchemaFunction(),
                new MissingSchemaFunction(),
                new StringFunctions()
        ));

        List<ExpressionFunctionInfo> functions = schemaRegistry.listFunctions();
        assertEquals(3, functions.size());
        assertEquals("MALFORMED_JSON_SCHEMA", functions.stream()
                .filter(info -> "malformedSchema".equals(info.name()))
                .findFirst().orElseThrow().overloads().getFirst().schemaDiagnostic().code());
        assertEquals("SCHEMA_RESOURCE_NOT_FOUND", functions.stream()
                .filter(info -> "missingSchema".equals(info.name()))
                .findFirst().orElseThrow().overloads().getFirst().schemaDiagnostic().code());
        assertNull(functions.stream()
                .filter(info -> "str".equals(info.name()))
                .findFirst().orElseThrow().overloads().getFirst().schemaDiagnostic());
    }

    @Test
    void shouldCacheSchemaMetadataAtRegistrationTime() throws Exception {
        SchemaFunction function = new SchemaFunction();
        ExpressionFunctionSchemaResolver resolver = mock(ExpressionFunctionSchemaResolver.class);
        var method = SchemaFunction.class.getMethod("execute", ExpressionContext.class);
        when(resolver.resolve(any(), any())).thenReturn(new ExpressionFunctionSchemaResolver.Result(null, null));
        when(resolver.resolve(function, method)).thenReturn(new ExpressionFunctionSchemaResolver.Result(null, null));

        ExpressionFunctionRegistry schemaRegistry = new ExpressionFunctionRegistry(List.of(function), resolver);
        schemaRegistry.listFunctions();
        schemaRegistry.listFunctions();

        verify(resolver, times(1)).resolve(function, method);
    }

    @Test
    void shouldDiscoverTargetMethodsAndAnnotationsOnClassBasedProxy() {
        SchemaFunction target = new SchemaFunction();
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        EpistolaExpressionFunction proxy = (EpistolaExpressionFunction) proxyFactory.getProxy();

        ExpressionFunctionRegistry proxyRegistry = new ExpressionFunctionRegistry(List.of(proxy));

        ExpressionFunctionInfo.OverloadInfo mapOverload = proxyRegistry.listFunctions().getFirst().overloads().stream()
                .filter(overload -> "Map".equals(overload.returnType()))
                .findFirst()
                .orElseThrow();
        assertNotNull(mapOverload.resultSchema());
        assertEquals(SchemaFunction.class,
                proxyRegistry.getFunction("person").methods().getFirst().getDeclaringClass());
    }

    @Test
    void shouldRejectJdkProxyWithActionableDiagnostic() {
        ProxyFactory proxyFactory = new ProxyFactory(new SchemaFunction());
        proxyFactory.setInterfaces(EpistolaExpressionFunction.class);
        EpistolaExpressionFunction proxy = (EpistolaExpressionFunction) proxyFactory.getProxy();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new ExpressionFunctionRegistry(List.of(proxy)));

        assertTrue(exception.getMessage().contains("JDK dynamic proxy"));
        assertTrue(exception.getMessage().contains("class-based proxying"));
        assertTrue(exception.getMessage().contains(SchemaFunction.class.getName()));
    }

    static class SchemaFunction implements EpistolaExpressionFunction {
        private int invocations;

        @Override
        public String name() {
            return "person";
        }

        @Override
        public String description() {
            return "Returns a person";
        }

        @ExpressionFunctionResultSchema("expression-schemas/person-result.schema.json")
        public Map<String, Object> execute(ExpressionContext context) {
            invocations++;
            return Map.of();
        }

        public String execute(ExpressionContext context, String id) {
            invocations++;
            return id;
        }
    }

    private static class MalformedSchemaFunction implements EpistolaExpressionFunction {
        @Override
        public String name() {
            return "malformedSchema";
        }

        @Override
        public String description() {
            return "Malformed schema fixture";
        }

        @ExpressionFunctionResultSchema("expression-schemas/malformed.schema.txt")
        public Map<String, Object> execute(ExpressionContext context) {
            return Map.of();
        }
    }

    private static class MissingSchemaFunction implements EpistolaExpressionFunction {
        @Override
        public String name() {
            return "missingSchema";
        }

        @Override
        public String description() {
            return "Missing schema fixture";
        }

        @ExpressionFunctionResultSchema("expression-schemas/does-not-exist.schema.json")
        public Map<String, Object> execute(ExpressionContext context) {
            return Map.of();
        }
    }
}
