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
package app.epistola.valtimo.service.form;

import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateField.FieldType;
import app.epistola.valtimo.domain.TemplateField.RichTextKind;
import app.epistola.valtimo.richtext.HtmlFormat;
import app.epistola.valtimo.richtext.PlainTextFormat;
import app.epistola.valtimo.richtext.RichTextConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A rich-text field is asked for with the configured format's editor (ADR 0008). */
class FormioFormGeneratorRichTextTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static TemplateField richText(boolean required, RichTextKind kind) {
        return new TemplateField("toelichting", "besluit.toelichting", "richtext-block", FieldType.SCALAR, required,
                "Waarom", List.of(), false, null, false,
                new TemplateField.FieldHints("Toelichting", null, null, null, null, null, kind));
    }

    private JsonNode component(FormioFormGenerator generator, TemplateField field, Map<String, Object> data) {
        return generator.generateForm(List.of(field), data).get("components").get(0);
    }

    @Test
    void asksForHtmlWithTheWysiwygEditor() {
        var generator = new FormioFormGenerator(objectMapper,
                new RichTextConverter(List.of(new PlainTextFormat(), new HtmlFormat()), "html"));

        JsonNode component = component(generator, richText(true, RichTextKind.BLOCK), Map.of());

        assertThat(component.get("type").asText()).isEqualTo("textarea");
        assertThat(component.get("editor").asText()).isEqualTo("ckeditor");
        assertThat(component.get("key").asText()).isEqualTo("besluit.toelichting");
        assertThat(component.get("label").asText()).isEqualTo("Toelichting");
        assertThat(component.get("tooltip").asText()).isEqualTo("Waarom");
        assertThat(component.at("/validate/required").asBoolean()).isTrue();
        assertThat(component.has("defaultValue")).isFalse();
    }

    @Test
    void asksForPlainTextWithAPlainTextArea_byDefault() {
        JsonNode component = component(new FormioFormGenerator(objectMapper), richText(false, RichTextKind.INLINE), Map.of());

        assertThat(component.get("type").asText()).isEqualTo("textarea");
        assertThat(component.has("editor")).isFalse();
        assertThat(component.has("validate")).isFalse();
    }

    @Test
    void showsADocumentThatWasRenderedInTheEditorsFormat_notAsJson() {
        var generator = new FormioFormGenerator(objectMapper,
                new RichTextConverter(List.of(new PlainTextFormat(), new HtmlFormat()), "html"));
        Map<String, Object> document = Map.of("type", "doc", "content", List.of(Map.of("type", "paragraph",
                "content", List.of(Map.of("type", "text", "text", "vet", "marks", List.of(Map.of("type", "strong")))))));

        JsonNode component = component(generator, richText(false, RichTextKind.BLOCK), Map.of("toelichting", document));

        assertThat(component.get("defaultValue").asText()).isEqualTo("<p><strong>vet</strong></p>");
    }

    @Test
    void showsTextAlreadyInTheCasesFormatAsItIs() {
        JsonNode component = component(new FormioFormGenerator(objectMapper), richText(false, RichTextKind.BLOCK),
                Map.of("toelichting", "al getypt"));

        assertThat(component.get("defaultValue").asText()).isEqualTo("al getypt");
    }
}
