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
import app.epistola.valtimo.richtext.RichTextFixtures;
import app.epistola.valtimo.richtext.RichTextValues;
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
        var generator = RichTextFixtures.generator(objectMapper, "html");

        JsonNode component = component(generator, richText(true, RichTextKind.BLOCK), Map.of());

        assertThat(component.get("type").asText()).isEqualTo("textarea");
        assertThat(component.get("editor").asText()).isEqualTo("ckeditor");
        assertThat(component.get("key").asText()).isEqualTo("besluit.toelichting");
        assertThat(component.get("label").asText()).isEqualTo("Toelichting");
        assertThat(component.get("tooltip").asText()).isEqualTo("Waarom");
        assertThat(component.at("/validate/required").asBoolean()).isTrue();
        assertThat(component.has("defaultValue")).isFalse();
        // What the frontend tags the typed value with: the editor's format and the field's kind.
        assertThat(component.at("/epistolaRichText/format").asText()).isEqualTo("html");
        assertThat(component.at("/epistolaRichText/kind").asText()).isEqualTo("BLOCK");
    }

    @Test
    void asksForPlainTextWithAPlainTextArea_byDefault() {
        JsonNode component = component(RichTextFixtures.generator(objectMapper, "plain"), richText(false, RichTextKind.INLINE), Map.of());

        assertThat(component.get("type").asText()).isEqualTo("textarea");
        assertThat(component.has("editor")).isFalse();
        assertThat(component.has("validate")).isFalse();
        assertThat(component.at("/epistolaRichText/format").asText()).isEqualTo("plain");
        assertThat(component.at("/epistolaRichText/kind").asText()).isEqualTo("INLINE");
    }

    @Test
    void showsADocumentThatWasRenderedInTheEditorsFormat_notAsJson() {
        var generator = RichTextFixtures.generator(objectMapper, "html");
        Map<String, Object> document = Map.of("type", "doc", "content", List.of(Map.of("type", "paragraph",
                "content", List.of(Map.of("type", "text", "text", "vet", "marks", List.of(Map.of("type", "strong")))))));

        JsonNode component = component(generator, richText(false, RichTextKind.BLOCK), Map.of("toelichting", document));

        assertThat(component.get("defaultValue").asText()).isEqualTo("<p><strong>vet</strong></p>");
    }

    @Test
    void asksForAListWithOneEditorPerItem() {
        var field = new TemplateField("alineas", "alineas", "array", FieldType.SCALAR, true, null, List.of(),
                false, null, false, new TemplateField.FieldHints(null, null, null, null,
                new TemplateField.Constraints(null, null, null, null, null, 1, 3), null, RichTextKind.BLOCK));
        var generator = RichTextFixtures.generator(objectMapper, "html");
        Map<String, Object> rendered = Map.of("type", "doc", "content",
                List.of(Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", "eerder")))));

        JsonNode component = component(generator, field, Map.of("alineas", List.of(rendered, "<p>getypt</p>")));

        assertThat(component.get("editor").asText()).isEqualTo("ckeditor");
        assertThat(component.get("multiple").asBoolean()).isTrue();
        assertThat(component.at("/validate/required").asBoolean()).isTrue();
        assertThat(component.at("/validate/minLength").asInt()).isEqualTo(1);
        assertThat(component.at("/validate/maxLength").asInt()).isEqualTo(3);
        assertThat(component.get("defaultValue").toString()).isEqualTo("[\"<p>eerder</p>\",\"<p>getypt</p>\"]");
    }

    /**
     * The retry form's round trip: what was rendered is shown, taken back unchanged and tagged the
     * way the frontend tags it — from the component's own format and kind — and must convert to the
     * same document, or a retry would lose the letter's formatting.
     */
    @Test
    void aRenderedDocumentSurvivesTheRetryFormsRoundTrip() {
        var converter = RichTextFixtures.converter("html");
        Map<String, Object> rendered = Map.of("type", "doc", "content", List.of(
                Map.of("type", "paragraph", "content", List.of(
                        Map.of("type", "text", "text", "Geachte "),
                        Map.of("type", "text", "text", "heer", "marks", List.of(Map.of("type", "strong"))),
                        Map.of("type", "text", "text", " rood", "marks",
                                List.of(Map.of("type", "textStyle", "attrs", Map.of("color", "#e60000")))))),
                Map.of("type", "ordered_list", "attrs", Map.of("order", 3), "content", List.of(
                        Map.of("type", "list_item", "content", List.of(Map.of("type", "paragraph", "content",
                                List.of(Map.of("type", "text", "text", "drie")))))))));
        TemplateField field = richText(false, RichTextKind.BLOCK);

        JsonNode component = component(RichTextFixtures.generator(objectMapper, "html"), field,
                Map.of("toelichting", rendered));
        Map<String, Object> resubmitted = Map.of("toelichting", Map.of(RichTextValues.TAG, Map.of(
                "format", component.at("/epistolaRichText/format").asText(),
                "kind", component.at("/epistolaRichText/kind").asText(),
                "value", component.get("defaultValue").asText())));

        assertThat(new RichTextValues(converter).toDocuments(resubmitted, "besluit", RichTextValues.Purpose.PREVIEW)
                .get("toelichting")).isEqualTo(rendered);
    }

    @Test
    void showsWhatAnEditorTaggedBeforeAsItWasTyped() {
        Map<String, Object> tagged = Map.of(RichTextValues.TAG,
                Map.of("format", "html", "kind", "BLOCK", "value", "<p>eerder getypt</p>"));

        JsonNode component = component(RichTextFixtures.generator(objectMapper, "html"), richText(false, RichTextKind.BLOCK),
                Map.of("toelichting", tagged));

        assertThat(component.get("defaultValue").asText()).isEqualTo("<p>eerder getypt</p>");
    }

    @Test
    void showsTextAlreadyInTheCasesFormatAsItIs() {
        JsonNode component = component(RichTextFixtures.generator(objectMapper, "plain"), richText(false, RichTextKind.BLOCK),
                Map.of("toelichting", "al getypt"));

        assertThat(component.get("defaultValue").asText()).isEqualTo("al getypt");
    }
}
