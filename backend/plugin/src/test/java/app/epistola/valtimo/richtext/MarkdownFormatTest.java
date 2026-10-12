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

import java.util.Map;

import static app.epistola.valtimo.richtext.Docs.BREAK;
import static app.epistola.valtimo.richtext.Docs.EM;
import static app.epistola.valtimo.richtext.Docs.STRIKE;
import static app.epistola.valtimo.richtext.Docs.STRONG;
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

class MarkdownFormatTest {

    private final MarkdownFormat format = new MarkdownFormat();

    private Map<String, Object> convert(String markdown) {
        return convert(markdown, RichTextKind.BLOCK);
    }

    private Map<String, Object> convert(String markdown, RichTextKind kind) {
        return assertValid(ProseMirrorSanitizer.sanitize(format.toProseMirror(markdown), kind), kind);
    }

    @Test
    void separatesParagraphsByABlankLine_andJoinsLinesWithinOne() {
        assertThat(convert("Geachte heer,\n\nUw aanvraag\nis toegekend.\r\n"))
                .isEqualTo(doc(p(text("Geachte heer,")), p(text("Uw aanvraag is toegekend."))));
    }

    @Test
    void breaksALineOnTwoTrailingSpacesOrABackslash() {
        assertThat(convert("een  \ntwee\\\ndrie"))
                .isEqualTo(doc(p(text("een"), BREAK, text("twee"), BREAK, text("drie"))));
    }

    @Test
    void readsEmphasis() {
        assertThat(convert("**vet** *schuin* _ook schuin_ __ook vet__ ~~weg~~ ***beide***"))
                .isEqualTo(doc(p(text("vet", STRONG), text(" "), text("schuin", EM), text(" "),
                        text("ook schuin", EM), text(" "), text("ook vet", STRONG), text(" "),
                        text("weg", STRIKE), text(" "), text("beide", STRONG, EM))));
    }

    @Test
    void nestsEmphasis() {
        assertThat(convert("*a **b** c*"))
                .isEqualTo(doc(p(text("a ", EM), text("b", EM, STRONG), text(" c", EM))));
    }

    @Test
    void leavesWhatIsNotEmphasisAlone() {
        assertThat(convert("2 * 3 * 4, snake_case_naam, \\*geen\\* en **open"))
                .isEqualTo(doc(p(text("2 * 3 * 4, snake_case_naam, *geen* en **open"))));
    }

    @Test
    void readsLinks() {
        assertThat(convert("[site](https://epistola.app \"titel\") <https://x.nl> <a@b.nl> [kwaad](javascript:x)"))
                .isEqualTo(doc(p(text("site", link("https://epistola.app")), text(" "),
                        text("https://x.nl", link("https://x.nl")), text(" "),
                        text("a@b.nl", link("mailto:a@b.nl")), text(" kwaad"))));
    }

    @Test
    void nestsListsByIndentation() {
        String markdown = """
                - een
                - twee
                  - sub
                    lui vervolg
                - drie

                3. drie
                4. vier
                * ander soort
                """;

        assertThat(convert(markdown)).isEqualTo(doc(
                ul(li(p(text("een"))), li(p(text("twee")), ul(li(p(text("sub lui vervolg"))))), li(p(text("drie")))),
                olFrom(3, li(p(text("drie"))), li(p(text("vier")))),
                ul(li(p(text("ander soort"))))));
    }

    @Test
    void endsAListAtUnindentedTextAfterABlankLine() {
        assertThat(convert("- item\n\n  nog in item\n\nerna"))
                .isEqualTo(doc(ul(li(p(text("item")), p(text("nog in item")))), p(text("erna"))));
    }

    @Test
    void keepsTheTextOfHeadingsQuotesAndCode() {
        String markdown = "# Besluit\n\n> geciteerd\n\n`code` hier\n\n```\nregel *1*\nregel 2\n```\n\n---";

        assertThat(convert(markdown)).isEqualTo(doc(
                p(text("Besluit")), p(text("geciteerd")), p(text("code hier")),
                p(text("regel *1*"), BREAK, text("regel 2"))));
    }

    @Test
    void readsBackWhatItWrites() {
        Map<String, Object> original = convert(
                "a **b *c*** [d](https://x.nl) ~~e~~ 2 \\* 3\\\nregel\n\n1. f\n   - g\n2. h");

        String markdown = (String) format.fromProseMirror(original);

        assertThat(convert(markdown)).isEqualTo(original);
    }
}
