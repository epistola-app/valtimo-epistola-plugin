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
import app.epistola.valtimo.richtext.RichTextValues.Purpose;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RichTextValuesTest {

    // The default deliberately differs from every tag below: a tag's own format is what counts.
    private final RichTextConverter converter = RichTextFixtures.converter("plain");
    private final RichTextValues values = new RichTextValues(converter);

    private static Map<String, Object> tagged(String format, String kind, String value) {
        Map<String, Object> tag = new HashMap<>();
        tag.put("format", format);
        tag.put("kind", kind);
        tag.put("value", value);
        return Map.of(RichTextValues.TAG, tag);
    }

    private Map<String, Object> convert(Map<String, Object> data) {
        return values.toDocuments(data, "besluit", Purpose.GENERATION);
    }

    @Test
    void readsATaggedValueInItsOwnFormat_notTheDefault() {
        assertThat(convert(Map.of("motivation", tagged("markdown", "BLOCK", "Het is **gegrond**"))))
                .containsEntry("motivation",
                        converter.toDocument("Het is **gegrond**", "markdown", RichTextKind.BLOCK));
    }

    @Test
    void narrowsToTheKindTheTagNames() {
        String twoParagraphs = "<p>Eerste</p><p>Tweede</p>";

        assertThat(convert(Map.of("aanhef", tagged("html", "INLINE", twoParagraphs))))
                .containsEntry("aanhef", converter.toDocument(twoParagraphs, "html", RichTextKind.INLINE));
    }

    @Test
    void findsTagsAnywhere_inObjectsAndLists() {
        Map<String, Object> data = Map.of("besluit", Map.of("alineas", List.of(
                tagged("html", "BLOCK", "<p>Een</p>"),
                tagged("html", "BLOCK", "<p><br></p>"),
                tagged("html", "BLOCK", "<p>Twee</p>"))));

        assertThat(convert(data)).isEqualTo(Map.of("besluit", Map.of("alineas", List.of(
                converter.toDocument("<p>Een</p>", "html", RichTextKind.BLOCK),
                converter.toDocument("<p>Twee</p>", "html", RichTextKind.BLOCK)))));
    }

    @Test
    void leavesOutAListWhoseEveryItemWasEmpty_butKeepsAListThatHeldNoRichText() {
        assertThat(convert(Map.of(
                "alineas", List.of(tagged("html", "BLOCK", "")),
                "kenmerken", List.of())))
                .isEqualTo(Map.of("kenmerken", List.of()));
    }

    @Test
    void passesEverythingElseAsItWas_aBareStringIncluded() {
        Map<String, Object> mapped = Map.of(
                "motivation", "<p>gemapt</p>",
                "document", converter.toDocument("<p>al een document</p>", "html", RichTextKind.BLOCK),
                "bedrag", 12,
                // Shaped like a tag, but with company: data, not a tag.
                "notATag", Map.of(RichTextValues.TAG, Map.of("format", "html"), "other", 1));

        assertThat(convert(mapped)).isEqualTo(mapped);
    }

    @Test
    void refusesATagWithoutAFormat_namingTheField() {
        assertThatThrownBy(() -> convert(Map.of("besluit", Map.of("motivation", tagged(null, "BLOCK", "<p>x</p>")))))
                .isInstanceOf(RichTextConversionException.class)
                .hasMessageContaining("'besluit.motivation'");
    }

    @Test
    void refusesAKindItDoesNotKnow() {
        assertThatThrownBy(() -> convert(Map.of("motivation", tagged("html", "PARAGRAPH", "<p>x</p>"))))
                .isInstanceOf(RichTextConversionException.class)
                .hasMessageContaining("PARAGRAPH");
    }

    @Test
    void reportsAFailingFormatByFieldAndFormat_neverTheText() {
        RichTextFormat broken = new RichTextFormat() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public Map<String, Object> toProseMirror(Object value, RichTextLosses losses) {
                throw new IllegalStateException("cannot parse " + value);
            }
        };
        RichTextValues failing = new RichTextValues(new RichTextConverter(List.of(broken), "broken"));

        assertThatThrownBy(() -> failing.toDocuments(
                Map.of("alineas", List.of(tagged("broken", "BLOCK", "Jansen, BSN 123456782"))), "besluit",
                Purpose.GENERATION))
                .isInstanceOf(RichTextConversionException.class)
                .hasMessageContaining("'alineas[0]'")
                .hasMessageContaining("'broken'")
                .hasMessageNotContaining("Jansen")
                .hasNoCause();
    }

    @Test
    void warnsWhatALetterLost_namingTheFieldAndTemplateButNeverTheText() {
        try (var logs = CapturedLogs.of(RichTextValues.class)) {
            convert(Map.of("toelichting", tagged("html", "BLOCK", "<h1>Jansen, BSN 123456782</h1><p>tekst</p>")));

            assertThat(logs.at(Level.WARN)).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                    .contains("'toelichting'", "'besluit'", "heading ×1")
                    .doesNotContain("Jansen", "123456782", "tekst"));
        }
    }

    @Test
    void onlyDebugsItForAPreview_whichAnEditorRepeatsOnEveryChange() {
        try (var logs = CapturedLogs.of(RichTextValues.class)) {
            values.toDocuments(Map.of("toelichting", tagged("html", "BLOCK", "<h1>Kop</h1>")), "besluit",
                    Purpose.PREVIEW);

            assertThat(logs.at(Level.WARN)).isEmpty();
            assertThat(logs.at(Level.DEBUG)).singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage()).contains("heading ×1"));
        }
    }

    @Test
    void unwrapsToWhatWasTyped_forTheCaseAndForAnEditorShowingItAgain() {
        Map<String, Object> letter = Map.of(
                "besluit", Map.of("motivation", tagged("html", "BLOCK", "<p>Getypt</p>")),
                "alineas", List.of(tagged("html", "BLOCK", "<p>Een</p>")),
                "naam", "Jansen");

        assertThat(RichTextValues.unwrapAll(letter)).isEqualTo(Map.of(
                "besluit", Map.of("motivation", "<p>Getypt</p>"),
                "alineas", List.of("<p>Een</p>"),
                "naam", "Jansen"));
    }
}
