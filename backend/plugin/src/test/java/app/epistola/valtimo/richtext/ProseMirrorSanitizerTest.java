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
package app.epistola.valtimo.richtext;

import app.epistola.valtimo.domain.TemplateField.RichTextKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static app.epistola.valtimo.richtext.RichTextSchemas.assertValid;
import static org.assertj.core.api.Assertions.assertThat;

class ProseMirrorSanitizerTest {

    private static Map<String, Object> sanitize(Object doc, RichTextKind kind) {
        return assertValid(ProseMirrorSanitizer.sanitize(doc, kind), kind);
    }

    private static Map<String, Object> doc(Object... blocks) {
        return Map.of("type", "doc", "content", List.of(blocks));
    }

    private static Map<String, Object> paragraph(Object... inline) {
        return Map.of("type", "paragraph", "content", List.of(inline));
    }

    private static Map<String, Object> text(String text, Object... marks) {
        return marks.length == 0
                ? Map.of("type", "text", "text", text)
                : Map.of("type", "text", "text", text, "marks", List.of(marks));
    }

    @Test
    void keepsADocumentEpistolasEditorWroteAsItIs() {
        Map<String, Object> written = doc(
                paragraph(text("Geachte "), text("heer", Map.of("type", "strong")), Map.of("type", "hard_break"),
                        text("link", Map.of("type", "link", "attrs", Map.of("href", "https://epistola.app")))),
                Map.of("type", "ordered_list", "attrs", Map.of("listType", "lower-roman", "order", 3),
                        "content", List.of(Map.of("type", "list_item", "content", List.of(paragraph(text("een")))))));

        assertThat(sanitize(written, RichTextKind.BLOCK)).isEqualTo(written);
    }

    @Test
    void writesTheAliasesTheSchemaAcceptsUnderTheEditorsOwnNames() {
        Map<String, Object> result = sanitize(doc(
                paragraph(text("x", Map.of("type", "bold"), Map.of("type", "italic"), Map.of("type", "strike")),
                        Map.of("type", "hardBreak")),
                Map.of("type", "bulletList", "content", List.of(
                        Map.of("type", "listItem", "content", List.of(paragraph(text("y"))))))),
                RichTextKind.BLOCK);

        assertThat(result).isEqualTo(doc(
                paragraph(text("x", Map.of("type", "strong"), Map.of("type", "em"), Map.of("type", "strikethrough")),
                        Map.of("type", "hard_break")),
                Map.of("type", "bullet_list", "content", List.of(
                        Map.of("type", "list_item", "content", List.of(paragraph(text("y"))))))));
    }

    @Test
    void dropsMarksTheSchemaDoesNotHave_andLinksThatRunSomethingOrPointNowhere() {
        Map<String, Object> result = sanitize(doc(paragraph(
                text("a", Map.of("type", "code")),
                text("b", Map.of("type", "link", "attrs", Map.of("href", "javascript:alert(1)"))),
                text("c", Map.of("type", "link", "attrs", Map.of("href", "/relative"))),
                text("d", Map.of("type", "textStyle", "attrs", Map.of("color", "red"))),
                text("e", Map.of("type", "textStyle", "attrs", Map.of("color", "#ABC"))))),
                RichTextKind.BLOCK);

        assertThat(result).isEqualTo(doc(paragraph(
                text("abcd"),
                text("e", Map.of("type", "textStyle", "attrs", Map.of("color", "#ABC"))))));
    }

    @Test
    void keepsTheTextOfWhatTheSchemaCannotHold() {
        Map<String, Object> result = sanitize(doc(
                Map.of("type", "heading", "attrs", Map.of("level", 1), "content", List.of(text("Besluit"))),
                Map.of("type", "blockquote", "content", List.of(paragraph(text("geciteerd")))),
                Map.of("type", "image", "attrs", Map.of("src", "x.png")),
                Map.of("type", "table", "content", List.of(Map.of("type", "table_row", "content", List.of(
                        Map.of("type", "table_cell", "content", List.of(paragraph(text("cel")))))))),
                Map.of("type", "expression", "attrs", Map.of("expression", "x"))),
                RichTextKind.BLOCK);

        assertThat(result).isEqualTo(doc(paragraph(text("Besluit")), paragraph(text("geciteerd")), paragraph(text("cel"))));
    }

    @Test
    void givesAListItemThatStartsWithAListTheParagraphItNeeds() {
        Map<String, Object> nested = Map.of("type", "bullet_list", "content", List.of(
                Map.of("type", "list_item", "content", List.of(paragraph(text("binnen"))))));
        Map<String, Object> result = sanitize(doc(Map.of("type", "bullet_list", "content", List.of(
                Map.of("type", "list_item", "content", List.of(nested))))), RichTextKind.BLOCK);

        assertThat(result).isEqualTo(doc(Map.of("type", "bullet_list", "content", List.of(
                Map.of("type", "list_item", "content", List.of(Map.of("type", "paragraph"), nested))))));
    }

    @Test
    void putsAListWrittenStraightIntoAListIntoTheItemBeforeIt() {
        Map<String, Object> nested = Map.of("type", "bullet_list", "content", List.of(
                Map.of("type", "list_item", "content", List.of(paragraph(text("sub"))))));
        Map<String, Object> result = sanitize(doc(Map.of("type", "bullet_list", "content", List.of(
                Map.of("type", "list_item", "content", List.of(paragraph(text("hoofd")))), nested))),
                RichTextKind.BLOCK);

        assertThat(result).isEqualTo(doc(Map.of("type", "bullet_list", "content", List.of(
                Map.of("type", "list_item", "content", List.of(paragraph(text("hoofd")), nested))))));
    }

    @Test
    void narrowsAnythingToOneParagraphForInlineRichText_keepingListsVisible() {
        Map<String, Object> result = sanitize(doc(
                paragraph(text("Eerste")),
                paragraph(),
                Map.of("type", "ordered_list", "attrs", Map.of("order", 2), "content", List.of(
                        Map.of("type", "list_item", "content", List.of(paragraph(text("twee", Map.of("type", "em"))))),
                        Map.of("type", "list_item", "content", List.of(paragraph(text("drie"))))))),
                RichTextKind.INLINE);

        assertThat(result).isEqualTo(doc(paragraph(
                text("Eerste"), Map.of("type", "hard_break"),
                text("2. "), text("twee", Map.of("type", "em")), Map.of("type", "hard_break"),
                text("3. drie"))));
    }

    @Test
    void yieldsAValidEmptyDocumentForAnythingThatIsNotOne() {
        for (RichTextKind kind : RichTextKind.values()) {
            for (Object garbage : new Object[]{null, "text", List.of(), Map.of(), Map.of("type", "doc", "content", "x")}) {
                assertThat(sanitize(garbage, kind)).isEqualTo(doc(Map.of("type", "paragraph")));
            }
        }
    }

    @Test
    void survivesNestingDeeperThanAnyoneWrites() {
        Map<String, Object> node = new LinkedHashMap<>(paragraph(text("diep")));
        for (int i = 0; i < 500; i++) {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("type", "blockquote");
            wrapper.put("content", new ArrayList<>(List.of(node)));
            node = wrapper;
        }

        sanitize(doc(node), RichTextKind.BLOCK);
        sanitize(doc(node), RichTextKind.INLINE);
    }
}
