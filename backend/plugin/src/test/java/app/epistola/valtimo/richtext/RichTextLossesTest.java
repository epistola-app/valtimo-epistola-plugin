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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static app.epistola.valtimo.richtext.Docs.doc;
import static app.epistola.valtimo.richtext.Docs.p;
import static app.epistola.valtimo.richtext.Docs.text;
import static org.assertj.core.api.Assertions.assertThat;

/** What a conversion reports losing: kinds of things, counted, never their text. */
class RichTextLossesTest {

    private static Map<String, Integer> lostReading(RichTextFormat format, String value, RichTextKind kind) {
        RichTextLosses losses = new RichTextLosses();
        ProseMirrorSanitizer.sanitize(format.toProseMirror(value, losses), kind, losses);
        return losses.counts();
    }

    @Test
    void htmlFromAnUnrestrictedEditorReportsWhatTheLetterCannotShow() {
        // CKEditor with Form.io's full toolbar, as an author's own form might have it.
        String html = "<h2>Besluit</h2><p style=\"text-align:center;\">midden</p>"
                + "<p><span style=\"background-color:yellow;font-size:20px\">opvallend</span></p>"
                + "<figure class=\"table\"><table><tbody><tr><td>a</td><td>b</td></tr></tbody></table></figure>"
                + "<figure class=\"image\"><img src=\"x.png\"></figure><blockquote><p>citaat</p></blockquote>"
                + "<p><a href=\"javascript:alert(1)\">klik</a> <span style=\"color:red\">rood</span></p>";

        assertThat(lostReading(new HtmlFormat(), html, RichTextKind.BLOCK)).isEqualTo(Map.of(
                "heading", 1, "alignment", 1, "background colour", 1, "font", 1, "table", 1, "image", 1,
                "block quote", 1, "link", 1, "colour", 1));
    }

    @Test
    void htmlFromTheRestrictedEditorReportsNothing() {
        String html = "<p>Geachte <strong>heer</strong>, <i>schuin</i> <span style=\"color:hsl(30, 75%, 60%);\">kleur</span>"
                + " <a href=\"https://x.nl\">link</a></p><ul><li>een<ul><li>sub</li></ul></li></ul>";

        assertThat(lostReading(new HtmlFormat(), html, RichTextKind.BLOCK)).isEmpty();
    }

    @Test
    void quillKeepsSomeFormattingInClasses() {
        String html = "<p class=\"ql-align-center\">midden</p><p class=\"ql-indent-2\">in</p>"
                + "<p><span class=\"ql-size-large\">groot</span></p>"
                + "<ol><li data-list=\"bullet\" class=\"ql-indent-1\">genest, geen verlies</li></ol>";

        assertThat(lostReading(new HtmlFormat(), html, RichTextKind.BLOCK))
                .isEqualTo(Map.of("alignment", 1, "indentation", 1, "font", 1));
    }

    @Test
    void markdownReportsItsOwnConstructs_aQuoteOncePerQuote() {
        String markdown = "# Kop\n\n> regel een\n> regel twee\n\n```\ncode\n```\n\n`inline` ![plaatje](x.png)"
                + " [kwaad](javascript:x)\n\n---";

        assertThat(lostReading(new MarkdownFormat(), markdown, RichTextKind.BLOCK)).isEqualTo(Map.of(
                "heading", 1, "block quote", 1, "code block", 1, "code", 1, "image", 1, "link", 1,
                "horizontal rule", 1));
    }

    @Test
    void plainTextLosesNothing() {
        assertThat(lostReading(new PlainTextFormat(), "a\n\n*b* <c>", RichTextKind.BLOCK)).isEmpty();
    }

    @Test
    void aDocumentReportsWhatTheSanitizerNarrows_aTableOnce() {
        Map<String, Object> table = Map.of("type", "table", "content", List.of(
                Map.of("type", "table_row", "content", List.of(
                        Map.of("type", "table_cell", "content", List.of(p(text("a")))),
                        Map.of("type", "table_cell", "content", List.of(p(text("b")))))),
                Map.of("type", "table_row", "content", List.of(
                        Map.of("type", "table_cell", "content", List.of(p(text("c"))))))));
        Map<String, Object> document = doc(
                Map.of("type", "heading", "content", List.of(text("Kop"))),
                table,
                p(text("x", Map.of("type", "code")), Map.of("type", "image", "attrs", Map.of("src", "x"))));

        RichTextLosses losses = new RichTextLosses();
        ProseMirrorSanitizer.sanitize(new ProseMirrorFormat(new ObjectMapper()).toProseMirror(document, new RichTextLosses()), RichTextKind.BLOCK, losses);

        assertThat(losses.counts()).isEqualTo(Map.of("heading", 1, "table", 1, "code", 1, "image", 1));
    }

    @Test
    void anInlineFieldReportsTheStructureItFlattens() {
        RichTextLosses losses = new RichTextLosses();
        ProseMirrorSanitizer.sanitize(doc(p(text("a")), p(text("b"))), RichTextKind.INLINE, losses);

        assertThat(losses.counts()).isEqualTo(Map.of("paragraphs or lists in an inline field", 1));
    }

    @Test
    void aValidDocumentReportsNothing() {
        RichTextLosses losses = new RichTextLosses();
        ProseMirrorSanitizer.sanitize(doc(p(text("a", Docs.STRONG)), Docs.ul(Docs.li(p(text("b"))))),
                RichTextKind.BLOCK, losses);

        assertThat(losses.isEmpty()).isTrue();
    }

    @Test
    void readsAsKindsAndCounts() {
        RichTextLosses losses = new RichTextLosses();
        losses.lost("heading");
        losses.lost("image");
        losses.lost("heading");

        assertThat(losses).hasToString("heading ×2, image ×1");
    }
}
