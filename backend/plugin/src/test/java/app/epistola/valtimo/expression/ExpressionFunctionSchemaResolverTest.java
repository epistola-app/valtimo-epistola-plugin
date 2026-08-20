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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionFunctionSchemaResolverTest {

    private final ExpressionFunctionSchemaResolver resolver =
            new ExpressionFunctionSchemaResolver(new ObjectMapper());

    @Test
    void acceptsSupportedDialectAndDefaultDialectSchemas() throws Exception {
        assertThat(resolve("draft7").schema()).isNotNull();
        assertThat(resolve("defaultDialect").schema()).isNotNull();
    }

    @Test
    void rejectsSemanticallyInvalidSchema() throws Exception {
        var result = resolve("invalid");

        assertThat(result.schema()).isNull();
        assertThat(result.diagnostic().code()).isEqualTo("INVALID_JSON_SCHEMA");
        assertThat(result.diagnostic().message()).contains("type");
    }

    @Test
    void rejectsUnsupportedDialect() throws Exception {
        var result = resolve("unsupported");

        assertThat(result.schema()).isNull();
        assertThat(result.diagnostic().code()).isEqualTo("UNSUPPORTED_JSON_SCHEMA_DIALECT");
    }

    @Test
    void rejectsValidSchemasThatTheExpressionEditorCannotRepresent() throws Exception {
        var result = resolve("unsupportedAuthoring");

        assertThat(result.schema()).isNull();
        assertThat(result.diagnostic().code()).isEqualTo("UNSUPPORTED_EXPRESSION_AUTHORING_SCHEMA");
        assertThat(result.diagnostic().message())
                .contains("external references")
                .contains("#/properties/remotePerson/$ref");
    }

    private ExpressionFunctionSchemaResolver.Result resolve(String methodName) throws Exception {
        SchemaFixtures bean = new SchemaFixtures();
        Method method = SchemaFixtures.class.getMethod(methodName, ExpressionContext.class);
        return resolver.resolve(bean, method);
    }

    private static class SchemaFixtures implements EpistolaExpressionFunction {
        @Override
        public String name() {
            return "fixtures";
        }

        @Override
        public String description() {
            return "Schema resolver fixtures";
        }

        @ExpressionFunctionResultSchema("expression-schemas/draft7.schema.json")
        public Map<String, Object> draft7(ExpressionContext context) {
            return Map.of();
        }

        @ExpressionFunctionResultSchema("expression-schemas/default-dialect.schema.json")
        public Map<String, Object> defaultDialect(ExpressionContext context) {
            return Map.of();
        }

        @ExpressionFunctionResultSchema("expression-schemas/invalid.schema.json")
        public Map<String, Object> invalid(ExpressionContext context) {
            return Map.of();
        }

        @ExpressionFunctionResultSchema("expression-schemas/unsupported.schema.json")
        public Map<String, Object> unsupported(ExpressionContext context) {
            return Map.of();
        }

        @ExpressionFunctionResultSchema("expression-schemas/unsupported-authoring.schema.json")
        public Map<String, Object> unsupportedAuthoring(ExpressionContext context) {
            return Map.of();
        }
    }
}
