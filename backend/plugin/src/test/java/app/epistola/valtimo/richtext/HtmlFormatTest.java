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

import java.util.List;
import java.util.Map;

import static app.epistola.valtimo.richtext.Docs.BREAK;
import static app.epistola.valtimo.richtext.Docs.EM;
import static app.epistola.valtimo.richtext.Docs.STRIKE;
import static app.epistola.valtimo.richtext.Docs.STRONG;
import static app.epistola.valtimo.richtext.Docs.SUB;
import static app.epistola.valtimo.richtext.Docs.SUP;
import static app.epistola.valtimo.richtext.Docs.UNDERLINE;
import static app.epistola.valtimo.richtext.Docs.color;
import static app.epistola.valtimo.richtext.Docs.doc;
import static app.epistola.valtimo.richtext.Docs.li;
import static app.epistola.valtimo.richtext.Docs.link;
import static app.epistola.valtimo.richtext.Docs.ol;
import static app.epistola.valtimo.richtext.Docs.olFrom;
import static app.epistola.valtimo.richtext.Docs.p;
import static app.epistola.valtimo.richtext.Docs.text;
import static app.epistola.valtimo.richtext.Docs.ul;
import static app.epistola.valtimo.richtext.RichTextSchemas.assertValid;
import static org.assertj.core.api.Assertions.assertThat;

class HtmlFormatTest {

    private final HtmlFormat format = new HtmlFormat();

    private Map<String, Object> convert(String html) {
        return convert(html, RichTextKind.BLOCK);
    }

    private Map<String, Object> convert(String html, RichTextKind kind) {
        return assertValid(ProseMirrorSanitizer.sanitize(format.toProseMirror(html), kind), kind);
    }

    @Test
    void readsWhatCkEditorWrites() {
        String html = "<p>Geachte <strong>heer</strong> Jansen,</p>"
                + "<p>Uw aanvraag is <i>toegekend</i>.&nbsp;Zie <a href=\"https://epistola.app\">de site</a>.</p>"
                + "<ul><li>punt één</li><li>punt twee<ul><li>sub</li></ul></li></ul>"
                + "<ol start=\"3\"><li>drie</li></ol>";

        assertThat(convert(html)).isEqualTo(doc(
                p(text("Geachte "), text("heer", STRONG), text(" Jansen,")),
                p(text("Uw aanvraag is "), text("toegekend", EM), text(". Zie "),
                        text("de site", link("https://epistola.app")), text(".")),
                ul(li(p(text("punt één"))), li(p(text("punt twee")), ul(li(p(text("sub")))))),
                olFrom(3, li(p(text("drie"))))));
    }

    @Test
    void rebuildsQuillsFlatListsIntoNestedOnes() {
        // Quill 2's own markup: one <ol> for every list, each item naming its type and depth, and a
        // ql-ui span the editor uses for the marker.
        String html = "<p>Hallo <span style=\"color: rgb(230, 0, 0);\">rood</span>, <u>onder</u> en <s>door</s></p>"
                + "<ol>"
                + "<li data-list=\"bullet\"><span class=\"ql-ui\" contenteditable=\"false\"></span>een</li>"
                + "<li data-list=\"bullet\" class=\"ql-indent-1\"><span class=\"ql-ui\" contenteditable=\"false\"></span>sub</li>"
                + "<li data-list=\"bullet\"><span class=\"ql-ui\" contenteditable=\"false\"></span>twee</li>"
                + "<li data-list=\"ordered\"><span class=\"ql-ui\" contenteditable=\"false\"></span>genummerd</li>"
                + "</ol><p><br></p><p>H<sub>2</sub>O en x<sup>2</sup>﻿</p>";

        assertThat(convert(html)).isEqualTo(doc(
                p(text("Hallo "), text("rood", color("#e60000")), text(", "), text("onder", UNDERLINE),
                        text(" en "), text("door", STRIKE)),
                ul(li(p(text("een")), ul(li(p(text("sub"))))), li(p(text("twee")))),
                ol(li(p(text("genummerd")))),
                p(text("H"), text("2", SUB), text("O en x"), text("2", SUP))));
    }

    @Test
    void rebuildsTheFlatListsOfOlderQuillToo() {
        // Before data-list, Quill kept the list type on the container and only the depth on items.
        String html = "<ul><li>een</li><li class=\"ql-indent-1\">sub</li><li class=\"ql-indent-2\">subsub</li>"
                + "<li>twee</li></ul><ol><li>genummerd</li><li class=\"ql-indent-1\">onder</li></ol>";

        assertThat(convert(html)).isEqualTo(doc(
                ul(li(p(text("een")), ul(li(p(text("sub")), ul(li(p(text("subsub"))))))), li(p(text("twee")))),
                ol(li(p(text("genummerd")), ol(li(p(text("onder"))))))));
    }

    @Test
    void readsFormattingWrittenAsStyle() {
        String html = "<p><span style=\"font-weight: 700; font-style: italic; text-decoration: underline line-through\">"
                + "alles</span> <font color=\"#00F\">blauw</font> <span style=\"vertical-align: super\">hoog</span></p>";

        assertThat(convert(html)).isEqualTo(doc(p(
                text("alles", STRONG, EM, UNDERLINE, STRIKE), text(" "), text("blauw", color("#00f")),
                text(" "), text("hoog", SUP))));
    }

    @Test
    void collapsesWhitespaceTheWayABrowserShowsIt() {
        String html = "\n  <p>\n   veel   \n  ruimte  <br>\n  na de breuk  </p>\n\n<div>  blok </div>  ";

        assertThat(convert(html)).isEqualTo(doc(
                p(text("veel ruimte"), BREAK, text("na de breuk")), p(text("blok"))));
    }

    @Test
    void keepsTheTextOfWhatTheSchemaCannotHold_andDropsWhatHasNone() {
        String html = "<h1>Besluit</h1><table><tr><td>cel 1</td><td>cel 2</td></tr></table>"
                + "<img src=\"x.png\" alt=\"plaatje\"><script>alert('x')</script><style>p{color:red}</style>"
                + "<!-- opmerking --><blockquote>geciteerd</blockquote><pre>regel 1\nregel  2</pre>";

        assertThat(convert(html)).isEqualTo(doc(
                p(text("Besluit")), p(text("cel 1")), p(text("cel 2")), p(text("geciteerd")),
                p(text("regel 1"), BREAK, text("regel  2"))));
    }

    @Test
    void keepsALinksTextButNotALinkThatRunsSomething() {
        assertThat(convert("<p><a href=\"javascript:alert(1)\">klik</a> <a href=\"mailto:a@b.nl\">mail</a></p>"))
                .isEqualTo(doc(p(text("klik "), text("mail", link("mailto:a@b.nl")))));
    }

    @Test
    void resolvesCharacterReferences() {
        assertThat(convert("<p>&lt;geen tag&gt; &amp; &eacute;&#233;&#xE9; &euro; &onbekend; a &lt b</p>"))
                .isEqualTo(doc(p(text("<geen tag> & ééé € &onbekend; a &lt b"))));
    }

    @Test
    void toleratesMarkupNobodyShouldWrite() {
        String html = "<p>open <b>vet <i>schuin</p><p>verder</b> a < b <li>los item</li></i>";

        Map<String, Object> result = convert(html);

        assertThat(result.get("content")).asList().hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void readsTextWithoutAnyMarkupAsOneParagraph() {
        assertThat(convert("gewoon tekst")).isEqualTo(doc(p(text("gewoon tekst"))));
    }

    @Test
    void narrowsToOneParagraphForInlineRichText() {
        assertThat(convert("<p>een</p><ul><li>twee</li></ul>", RichTextKind.INLINE))
                .isEqualTo(doc(p(text("een"), BREAK, text("• twee"))));
    }

    @Test
    void readsBackWhatItWrites() {
        Map<String, Object> original = convert("<p>a <strong>b <em>c</em></strong> <a href=\"https://x.nl\">d</a> "
                + "<span style=\"color:#123456\">e</span></p><ol start=\"2\"><li>f<ul><li>g</li></ul></li></ol>");

        String html = (String) format.fromProseMirror(original);

        assertThat(convert(html)).isEqualTo(original);
    }

    @Test
    void readsTheHslColoursCkEditorsPickerWrites() {
        // Captured from Form.io's CKEditor: picking the seventh swatch of the colour grid.
        assertThat(convert("<p><span style=\"color:hsl(30, 75%, 60%);\">oranje</span> "
                + "<span style=\"color: hsl(0 0% 100% / 50%)\">wit</span></p>"))
                .isEqualTo(doc(p(text("oranje", color("#e6994c")), text(" "), text("wit", color("#ffffff")))));
    }

    @Test
    void offersAToolbarOfOnlyWhatEpistolaRenders() {
        Map<String, Object> block = format.editor(RichTextKind.BLOCK);
        Map<String, Object> inline = format.editor(RichTextKind.INLINE);

        assertThat(block).containsEntry("type", "textarea").containsEntry("editor", "ckeditor");
        // Only buttons Form.io's CKEditor build has: an unknown one is a console warning, not a button.
        assertThat(block.get("wysiwyg")).isEqualTo(Map.of("toolbar", List.of(
                "bold", "italic", "|", "fontColor", "|", "bulletedList", "numberedList", "outdent", "indent",
                "|", "link", "|", "undo", "redo")));
        assertThat(String.valueOf(inline.get("wysiwyg"))).doesNotContain("List", "indent");
        assertThat(List.of(block, inline)).allSatisfy(editor -> assertThat(editor).containsEntry("isUploadEnabled", false));
    }
}
