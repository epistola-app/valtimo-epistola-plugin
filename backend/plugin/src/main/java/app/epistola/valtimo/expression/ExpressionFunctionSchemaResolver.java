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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.Dialects;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Loads and validates result schemas declared on expression-function overloads.
 *
 * <p>Schemas are validated against their declared JSON Schema meta-schema. A schema without
 * a {@code $schema} declaration is interpreted as draft 2020-12.</p>
 */
@Slf4j
public class ExpressionFunctionSchemaResolver {

    private static final Dialect DEFAULT_DIALECT = Dialects.getDraft202012();
    private static final List<Dialect> SUPPORTED_DIALECTS = List.of(
            Dialects.getDraft4(),
            Dialects.getDraft6(),
            Dialects.getDraft7(),
            Dialects.getDraft201909(),
            DEFAULT_DIALECT
    );
    private static final Map<String, Dialect> DIALECTS_BY_ID = SUPPORTED_DIALECTS.stream()
            .collect(Collectors.toUnmodifiableMap(
                    dialect -> normalizeDialectId(dialect.getId()),
                    Function.identity()
            ));

    private final ObjectMapper objectMapper;
    private final SchemaRegistry schemaRegistry;

    public ExpressionFunctionSchemaResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.schemaRegistry = SchemaRegistry.withDialects(SUPPORTED_DIALECTS);
    }

    /**
     * Resolve the optional schema annotation on an overload without invoking the function.
     */
    public Result resolve(EpistolaExpressionFunction bean, Method method) {
        ExpressionFunctionResultSchema annotation = method.getAnnotation(ExpressionFunctionResultSchema.class);
        if (annotation == null) {
            return Result.empty();
        }

        String resourceName = annotation.value().replaceFirst("^/", "");
        try (InputStream input = bean.getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                return Result.error(
                        "SCHEMA_RESOURCE_NOT_FOUND",
                        "Result schema resource '" + annotation.value() + "' was not found"
                );
            }
            JsonNode schema = objectMapper.readTree(input);
            if (schema == null || (!schema.isObject() && !schema.isBoolean())) {
                return Result.error(
                        "INVALID_JSON_SCHEMA",
                        "Result schema resource '" + annotation.value()
                                + "' must contain a JSON object or boolean schema"
                );
            }

            Dialect dialect = resolveDialect(schema);
            if (dialect == null) {
                return Result.error(
                        "UNSUPPORTED_JSON_SCHEMA_DIALECT",
                        "Result schema resource '" + annotation.value() + "' declares unsupported dialect '"
                                + schema.path("$schema").asText() + "'"
                );
            }
            List<Error> errors = schemaRegistry
                    .getSchema(SchemaLocation.of(dialect.getId()))
                    .validate(schema);
            if (!errors.isEmpty()) {
                return Result.error(
                        "INVALID_JSON_SCHEMA",
                        "Result schema resource '" + annotation.value() + "' is not a valid "
                                + dialect.getSpecificationVersion() + " schema: " + summarize(errors)
                );
            }
            return new Result(schema, null);
        } catch (JsonProcessingException e) {
            return Result.error(
                    "MALFORMED_JSON_SCHEMA",
                    "Result schema resource '" + annotation.value() + "' contains malformed JSON: "
                            + e.getOriginalMessage()
            );
        } catch (IOException | RuntimeException e) {
            log.warn("Could not load result schema '{}' for expression function '{}': {}",
                    annotation.value(), bean.name(), e.getMessage());
            return Result.error(
                    "UNREADABLE_JSON_SCHEMA",
                    "Result schema resource '" + annotation.value() + "' could not be read: " + e.getMessage()
            );
        }
    }

    private Dialect resolveDialect(JsonNode schema) {
        JsonNode dialect = schema.get("$schema");
        if (dialect == null) {
            return DEFAULT_DIALECT;
        }
        if (!dialect.isTextual()) {
            return null;
        }
        return DIALECTS_BY_ID.get(normalizeDialectId(dialect.asText()));
    }

    private static String normalizeDialectId(String dialectId) {
        return dialectId.endsWith("#") ? dialectId.substring(0, dialectId.length() - 1) : dialectId;
    }

    private static String summarize(List<Error> errors) {
        String summary = errors.stream()
                .limit(3)
                .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                .collect(Collectors.joining("; "));
        return errors.size() > 3 ? summary + "; and " + (errors.size() - 3) + " more" : summary;
    }

    /** Result schema or a publishable diagnostic explaining why it is unavailable. */
    public record Result(
            JsonNode schema,
            ExpressionFunctionInfo.SchemaDiagnostic diagnostic
    ) {
        private static Result empty() {
            return new Result(null, null);
        }

        private static Result error(String code, String message) {
            return new Result(null, new ExpressionFunctionInfo.SchemaDiagnostic(code, message));
        }
    }
}
