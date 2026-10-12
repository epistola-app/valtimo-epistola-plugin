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
package app.epistola.valtimo.schema;

import app.epistola.valtimo.domain.SimpleMappingSupport;
import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateField.RichTextKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** How the analyzer reads a reference to one of Epistola's rich-text schemas (ADR 0008). */
class JsonSchemaMappingAnalyzerRichTextTest {

    private static final String BLOCK = "https://epistola.app/schemas/richtext-block-v1.json";
    private static final String INLINE = "https://epistola.app/schemas/richtext-inline-v1.json";

    private final JsonSchemaMappingAnalyzer analyzer = new JsonSchemaMappingAnalyzer();

    private TemplateField only(Map<String, Object> property, boolean required) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("tekst", property),
                "required", required ? List.of("tekst") : List.of());
        JsonSchemaMappingAnalyzer.Analysis analysis = analyzer.analyze(schema);
        assertThat(analysis.support().level()).isEqualTo(SimpleMappingSupport.Level.FULL);
        return analysis.fields().get(0);
    }

    @Test
    void readsTheCanonicalReferenceAsOneMappableRichTextValue() {
        TemplateField field = only(Map.of("$ref", BLOCK, "title", "Toelichting", "description", "Vrije tekst"), true);

        assertThat(field.fieldType()).isEqualTo(TemplateField.FieldType.SCALAR);
        assertThat(field.complex()).isFalse();
        assertThat(field.required()).isTrue();
        assertThat(field.type()).isEqualTo("richtext-block");
        assertThat(field.description()).isEqualTo("Vrije tekst");
        assertThat(field.hints().richText()).isEqualTo(RichTextKind.BLOCK);
        assertThat(field.hints().title()).isEqualTo("Toelichting");
    }

    @Test
    void tellsInlineFromBlock() {
        assertThat(only(Map.of("$ref", INLINE), false).hints().richText()).isEqualTo(RichTextKind.INLINE);
        assertThat(only(Map.of("$ref", INLINE), false).type()).isEqualTo("richtext-inline");
    }

    @Test
    void findsItThroughALocalDefinition() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "$defs", Map.of("notitie", Map.of("$ref", BLOCK)),
                "properties", Map.of("tekst", Map.of("$ref", "#/$defs/notitie")));

        TemplateField field = analyzer.analyze(schema).fields().get(0);

        assertThat(field.hints().richText()).isEqualTo(RichTextKind.BLOCK);
        assertThat(field.complex()).isFalse();
    }

    @Test
    void findsItInsideANullableUnion() {
        TemplateField field = only(Map.of("oneOf", List.of(Map.of("$ref", INLINE), Map.of("type", "null"))), false);

        assertThat(field.hints().richText()).isEqualTo(RichTextKind.INLINE);
        assertThat(field.nullable()).isTrue();
    }

    @Test
    void stillMarksAnyOtherExternalReferenceComplex() {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("adres", Map.of("$ref", "https://example.com/adres.json")));

        TemplateField field = analyzer.analyze(schema).fields().get(0);

        assertThat(field.complex()).isTrue();
        assertThat(field.hints()).isNull();
    }

    @Test
    void mapsAnArrayOfRichTextAsOneValue_forNowWithoutAnEditor() {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("alineas", Map.of("type", "array", "items", Map.of("$ref", BLOCK))));

        TemplateField field = analyzer.analyze(schema).fields().get(0);

        assertThat(field.complex()).isTrue();
        assertThat(field.complexityReason()).contains("rich text");
    }
}
