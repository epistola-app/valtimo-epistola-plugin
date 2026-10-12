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
import static app.epistola.valtimo.richtext.Docs.doc;
import static app.epistola.valtimo.richtext.Docs.p;
import static app.epistola.valtimo.richtext.Docs.text;
import static app.epistola.valtimo.richtext.RichTextSchemas.assertValid;
import static org.assertj.core.api.Assertions.assertThat;

class PlainTextFormatTest {

    private final PlainTextFormat format = new PlainTextFormat();

    private Map<String, Object> convert(String text, RichTextKind kind) {
        return assertValid(ProseMirrorSanitizer.sanitize(format.toProseMirror(text, new RichTextLosses()), kind), kind);
    }

    @Test
    void aBlankLineSeparatesParagraphsAndALineBreakStaysOne() {
        assertThat(convert("Geachte heer,\r\n\r\n\r\nUw aanvraag\nis toegekend.\n", RichTextKind.BLOCK))
                .isEqualTo(doc(p(text("Geachte heer,")), p(text("Uw aanvraag"), BREAK, text("is toegekend."))));
    }

    @Test
    void markupIsJustCharacters() {
        assertThat(convert("2 * 3 * 4 <b>niet vet</b> **ook niet**", RichTextKind.BLOCK))
                .isEqualTo(doc(p(text("2 * 3 * 4 <b>niet vet</b> **ook niet**"))));
    }

    @Test
    void inlineRichTextKeepsTheParagraphsAsLines() {
        assertThat(convert("een\n\ntwee", RichTextKind.INLINE)).isEqualTo(doc(p(text("een"), BREAK, text("twee"))));
    }

    @Test
    void emptyTextIsAnEmptyDocument() {
        assertThat(convert("  \n ", RichTextKind.BLOCK)).isEqualTo(doc(p()));
    }

    @Test
    void writesADocumentBackAsTheTextItWasReadFrom() {
        String text = "Geachte heer,\n\nUw aanvraag\nis toegekend.";
        assertThat(format.fromProseMirror(convert(text, RichTextKind.BLOCK))).isEqualTo(text);
    }
}
