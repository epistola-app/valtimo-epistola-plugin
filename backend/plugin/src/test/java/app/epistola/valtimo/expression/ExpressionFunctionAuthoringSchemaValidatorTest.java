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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionFunctionAuthoringSchemaValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsSupportedStructuralSchemasAndKeywordLikePropertyNames() throws Exception {
        var schema = objectMapper.readTree("""
                {
                  "type": "object",
                  "properties": {
                    "if": { "type": "string" },
                    "person": {
                      "$ref": "#/$defs/person",
                      "properties": { "nickname": { "type": ["string", "null"] } }
                    },
                    "activities": {
                      "type": "array",
                      "items": { "type": "object", "properties": { "name": { "type": "string" } } }
                    }
                  },
                  "$defs": {
                    "person": { "type": "object", "properties": { "name": { "type": "string" } } }
                  }
                }
                """);

        assertThat(ExpressionFunctionAuthoringSchemaValidator.validate(schema)).isEmpty();
    }

    @Test
    void rejectsStructuresThatCannotBeRepresentedFaithfully() throws Exception {
        List<String> schemas = List.of(
                "{ \"$ref\": \"https://example.com/person.json\" }",
                "{ \"$dynamicRef\": \"#person\" }",
                "{ \"prefixItems\": [{ \"type\": \"string\" }] }",
                "{ \"items\": [{ \"type\": \"string\" }] }",
                "{ \"patternProperties\": { \".*\": { \"type\": \"string\" } } }",
                "{ \"dependentRequired\": { \"name\": [\"address\"] } }",
                "{ \"dependentSchemas\": { \"name\": { \"required\": [\"address\"] } } }",
                "{ \"dependencies\": { \"name\": [\"address\"] } }",
                "{ \"if\": { \"required\": [\"name\"] }, \"then\": { \"required\": [\"address\"] } }"
        );

        assertThat(schemas)
                .allSatisfy(schema -> assertThat(ExpressionFunctionAuthoringSchemaValidator.validate(
                        objectMapper.readTree(schema))).isPresent());
    }
}
