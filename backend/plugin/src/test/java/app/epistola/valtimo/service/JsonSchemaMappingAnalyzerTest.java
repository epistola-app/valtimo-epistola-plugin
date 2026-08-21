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
package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.SimpleMappingSupport;
import app.epistola.valtimo.domain.TemplateField;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSchemaMappingAnalyzerTest {

    private final JsonSchemaMappingAnalyzer analyzer = new JsonSchemaMappingAnalyzer();

    @Test
    void advancedDataContract_resolvesDeterministicFieldsAndDegradesComplexSubtrees() throws IOException {
        JsonSchemaMappingAnalyzer.Analysis analysis = analyzer.analyze(loadAdvancedDataContract());

        assertEquals(SimpleMappingSupport.Level.PARTIAL, analysis.support().level());
        assertEquals(6, analysis.fields().size());

        TemplateField applicant = field(analysis.fields(), "applicant");
        assertEquals(TemplateField.FieldType.OBJECT, applicant.fieldType());
        assertFalse(applicant.complex());
        assertTrue(applicant.required());
        assertTrue(field(applicant.children(), "email").required());
        assertFalse(field(applicant.children(), "phoneNumber").required());

        TemplateField address = field(applicant.children(), "address");
        assertEquals("applicant.address", address.path());
        assertTrue(field(address.children(), "street").required());
        assertTrue(field(address.children(), "postalCode").required());
        assertTrue(field(address.children(), "city").required());

        TemplateField subject = field(analysis.fields(), "subject");
        assertTrue(subject.complex());
        assertEquals("oneOf<person | organization>", subject.type());
        assertTrue(subject.required());

        TemplateField alternateSubjects = field(analysis.fields(), "alternateSubjects");
        assertTrue(alternateSubjects.complex());
        assertEquals("array<oneOf<person | organization>>", alternateSubjects.type());

        TemplateField correspondenceAddress = field(analysis.fields(), "correspondenceAddress");
        assertFalse(correspondenceAddress.complex());
        assertTrue(correspondenceAddress.nullable());
        assertEquals(3, correspondenceAddress.children().size());

        TemplateField deliveryRoutes = field(analysis.fields(), "deliveryRoutes");
        assertTrue(deliveryRoutes.complex());
        assertTrue(deliveryRoutes.complexityReason().contains("Nested arrays"));
    }

    @Test
    void rootUnion_requiresAdvancedMode() {
        Map<String, Object> schema = Map.of(
                "oneOf", List.of(
                        Map.of("type", "object", "properties", Map.of("person", Map.of("type", "string"))),
                        Map.of("type", "object", "properties", Map.of("company", Map.of("type", "string")))
                )
        );

        JsonSchemaMappingAnalyzer.Analysis analysis = analyzer.analyze(schema);

        assertEquals(SimpleMappingSupport.Level.UNSUPPORTED, analysis.support().level());
        assertTrue(analysis.fields().isEmpty());
    }

    @Test
    void recursiveAndUnresolvedReferencesBecomeComplexFieldsWithoutRecursingForever() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "$defs", Map.of(
                        "node", Map.of(
                                "type", "object",
                                "properties", Map.of("child", Map.of("$ref", "#/$defs/node"))
                        )
                ),
                "properties", Map.of(
                        "tree", Map.of("$ref", "#/$defs/node"),
                        "missing", Map.of("$ref", "#/$defs/does-not-exist")
                )
        );

        JsonSchemaMappingAnalyzer.Analysis analysis = analyzer.analyze(schema);

        assertEquals(SimpleMappingSupport.Level.PARTIAL, analysis.support().level());
        TemplateField tree = field(analysis.fields(), "tree");
        assertTrue(field(tree.children(), "child").complex());
        assertTrue(field(analysis.fields(), "missing").complex());
    }

    @Test
    void escapedJsonPointerAndNullableTypeArrayAreSupported() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "$defs", Map.of("a/b", Map.of("type", List.of("null", "string"))),
                "properties", Map.of("value", Map.of("$ref", "#/$defs/a~1b"))
        );

        TemplateField value = field(analyzer.analyze(schema).fields(), "value");

        assertFalse(value.complex());
        assertTrue(value.nullable());
        assertEquals("string", value.type());
    }

    private Map<String, Object> loadAdvancedDataContract() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/advanced-data-contract.schema.json")) {
            return new ObjectMapper().readValue(input, new TypeReference<>() {
            });
        }
    }

    private TemplateField field(List<TemplateField> fields, String name) {
        return fields.stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Field not found: " + name));
    }
}
