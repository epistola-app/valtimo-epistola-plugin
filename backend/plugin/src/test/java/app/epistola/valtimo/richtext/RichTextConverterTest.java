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

import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateField.FieldType;
import app.epistola.valtimo.domain.TemplateField.RichTextKind;
import app.epistola.valtimo.expression.ExpressionFunctionRegistry;
import app.epistola.valtimo.mapping.JsonataMappingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static app.epistola.valtimo.richtext.Docs.BREAK;
import static app.epistola.valtimo.richtext.Docs.STRONG;
import static app.epistola.valtimo.richtext.Docs.doc;
import static app.epistola.valtimo.richtext.Docs.p;
import static app.epistola.valtimo.richtext.Docs.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RichTextConverterTest {

    private static List<RichTextFormat> shipped() {
        return List.of(new PlainTextFormat(), new HtmlFormat(), new MarkdownFormat(),
                new ProseMirrorFormat(new ObjectMapper()));
    }

    private static TemplateField richText(String name, String path, RichTextKind kind) {
        return new TemplateField(name, path, "richtext", FieldType.SCALAR, false, null, List.of(), false, null,
                false, new TemplateField.FieldHints(null, null, null, null, null, null, kind));
    }

    private static TemplateField richTextList(String name, RichTextKind kind) {
        return new TemplateField(name, name, "array", FieldType.SCALAR, false, null, List.of(), false, null,
                false, new TemplateField.FieldHints(null, null, null, null, null, null, kind));
    }

    private static TemplateField scalar(String name, String path) {
        return new TemplateField(name, path, "string", FieldType.SCALAR, false, null, List.of());
    }

    @Nested
    class Configuration {

        @Test
        void readsAndAsksForHtmlUnlessConfiguredOtherwise() {
            // What Valtimo's WYSIWYG text areas and the generated editors write.
            assertThat(new app.epistola.valtimo.config.EpistolaProperties().getRichText().getDefaultFormat())
                    .isEqualTo(HtmlFormat.ID);
        }

        @Test
        void refusesADefaultNoFormatHas() {
            assertThatThrownBy(() -> new RichTextConverter(shipped(), "rtf"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'rtf'")
                    .hasMessageContaining("[html, markdown, plain, prosemirror]");
        }

        @Test
        void refusesTwoFormatsWithOneId() {
            assertThatThrownBy(() -> new RichTextConverter(List.of(new HtmlFormat(), new HtmlFormat()), "html"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'html'");
        }

        @Test
        void takesAnInstallationsOwnFormatById() {
            RichTextFormat shouting = new RichTextFormat() {
                @Override
                public String id() {
                    return "shouting";
                }

                @Override
                public Map<String, Object> toProseMirror(Object value, RichTextLosses losses) {
                    return doc(p(text(String.valueOf(value).toUpperCase())));
                }
            };
            var converter = new RichTextConverter(List.of(new PlainTextFormat(), shouting), "plain");

            assertThat(converter.toDocument("hallo", "shouting", RichTextKind.BLOCK)).isEqualTo(doc(p(text("HALLO"))));
            assertThat(converter.formatIds()).containsExactly("plain", "shouting");
        }

        @Test
        void namesTheFormatsThereAreWhenOneIsUnknown() {
            var converter = new RichTextConverter(shipped(), "plain");

            assertThatThrownBy(() -> converter.toDocument("x", "rtf", RichTextKind.BLOCK))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'rtf'")
                    .hasMessageContaining("markdown");
        }
    }

    @Nested
    class Normalize {

        private final RichTextConverter converter = new RichTextConverter(shipped(), "markdown");

        @Test
        void readsAStringInTheDefaultFormat() {
            var result = converter.normalize(
                    Map.of("toelichting", "**vet**", "naam", "**blijft tekst**"),
                    List.of(richText("toelichting", "toelichting", RichTextKind.BLOCK), scalar("naam", "naam")));

            assertThat(result).containsEntry("toelichting", doc(p(text("vet", STRONG))))
                    .containsEntry("naam", "**blijft tekst**");
        }

        @Test
        void shapesADocumentToTheFieldsKind() {
            var result = converter.normalize(
                    Map.of("kort", doc(p(text("een")), p(text("twee")))),
                    List.of(richText("kort", "kort", RichTextKind.INLINE)));

            assertThat(result).containsEntry("kort", doc(p(text("een"), BREAK, text("twee"))));
        }

        @Test
        void dropsABlankValue_andLeavesAbsenceAndNullAlone() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("leeg", "  ");
            data.put("niets", null);

            var result = converter.normalize(data, List.of(
                    richText("leeg", "leeg", RichTextKind.BLOCK),
                    richText("niets", "niets", RichTextKind.BLOCK),
                    richText("afwezig", "afwezig", RichTextKind.BLOCK)));

            assertThat(result).doesNotContainKeys("leeg", "afwezig").containsEntry("niets", null);
        }

        @Test
        void dropsMarkupAroundNothing_asAnEmptiedEditorLeavesIt() {
            var html = new RichTextConverter(shipped(), "html");

            var result = html.normalize(Map.of("leeg", "<p><br></p>", "gevuld", "<p>x</p>"), List.of(
                    richText("leeg", "leeg", RichTextKind.BLOCK), richText("gevuld", "gevuld", RichTextKind.BLOCK)));

            assertThat(result).doesNotContainKey("leeg").containsEntry("gevuld", doc(p(text("x"))));
        }

        @Test
        void findsRichTextInsideObjectsAndArrayRows() {
            TemplateField besluit = new TemplateField("besluit", "besluit", "object", FieldType.OBJECT, false, null,
                    List.of(richText("motivering", "besluit.motivering", RichTextKind.BLOCK)));
            TemplateField regels = new TemplateField("regels", "regels", "array", FieldType.ARRAY, false, null,
                    List.of(richText("tekst", "regels[].tekst", RichTextKind.INLINE)));

            var result = converter.normalize(Map.of(
                    "besluit", Map.of("motivering", "*m*"),
                    "regels", List.of(Map.of("tekst", "r1"), Map.of("tekst", "r2"))), List.of(besluit, regels));

            assertThat(result.get("besluit")).isEqualTo(Map.of("motivering",
                    doc(p(text("m", Docs.EM)))));
            assertThat(result.get("regels")).isEqualTo(List.of(
                    Map.of("tekst", doc(p(text("r1")))), Map.of("tekst", doc(p(text("r2"))))));
        }

        @Test
        void convertsEachItemOfAList_leavingOutTheEmptyOnes() {
            var result = converter.normalize(
                    Map.of("alineas", java.util.Arrays.asList("**een**", " ", null, doc(p(text("twee")), p(text("drie"))))),
                    List.of(richTextList("alineas", RichTextKind.INLINE)));

            assertThat(result).containsEntry("alineas", List.of(
                    doc(p(text("een", STRONG))), doc(p(text("twee"), BREAK, text("drie")))));
        }

        @Test
        void takesASingleValueForAListFieldAsAListOfOne() {
            // What JSONata hands over when a mapping over a case list meets a case with one item.
            var result = converter.normalize(
                    Map.of("een", doc(p(text("enige"))), "tekst", "*los*"),
                    List.of(richTextList("een", RichTextKind.BLOCK), richTextList("tekst", RichTextKind.BLOCK)));

            assertThat(result).containsEntry("een", List.of(doc(p(text("enige")))))
                    .containsEntry("tekst", List.of(doc(p(text("los", Docs.EM)))));
        }

        @Test
        void dropsAListWithNothingInIt() {
            var result = converter.normalize(Map.of("alineas", List.of("", "  ")),
                    List.of(richTextList("alineas", RichTextKind.BLOCK)));

            assertThat(result).doesNotContainKey("alineas");
        }

        @Test
        void reportsWhatAFieldLostByItsPath_andNothingForAFieldThatLostNothing() {
            var html = new RichTextConverter(shipped(), "html");
            Map<String, RichTextLosses> reported = new LinkedHashMap<>();

            html.normalize(Map.of(
                    "besluit", Map.of("toelichting", "<h1>Kop</h1><img src=x>"),
                    "schoon", "<p>niets mis</p>",
                    "alineas", List.of("<h2>a</h2>", "<h2>b</h2>")), List.of(
                    new TemplateField("besluit", "besluit", "object", FieldType.OBJECT, false, null,
                            List.of(richText("toelichting", "besluit.toelichting", RichTextKind.BLOCK))),
                    richText("schoon", "schoon", RichTextKind.BLOCK),
                    richTextList("alineas", RichTextKind.BLOCK)), reported::put);

            assertThat(reported).containsOnlyKeys("besluit.toelichting", "alineas");
            assertThat(reported.get("besluit.toelichting").counts()).isEqualTo(Map.of("heading", 1, "image", 1));
            assertThat(reported.get("alineas").counts()).isEqualTo(Map.of("heading", 2));
        }

        @Test
        void returnsDataWithoutRichTextFieldsAsItCame() {
            Map<String, Object> data = Map.of("naam", "x");

            assertThat(converter.normalize(data, List.of(scalar("naam", "naam")))).isSameAs(data);
        }

        @Test
        void leavesAValueThatIsNeitherTextNorADocumentForEpistolaToRefuse() {
            var result = converter.normalize(Map.of("lijst", List.of("a")),
                    List.of(richText("lijst", "lijst", RichTextKind.BLOCK)));

            assertThat(result).containsEntry("lijst", List.of("a"));
        }

        @Test
        void dropsAnEmptyDocumentAsMuchAsEmptyText() {
            var result = converter.normalize(
                    Map.of("leeg", Map.of("type", "doc", "content", List.of(Map.of("type", "paragraph")))),
                    List.of(richText("leeg", "leeg", RichTextKind.BLOCK)));

            assertThat(result).doesNotContainKey("leeg");
        }

        @Test
        void namesTheFieldButNeverTheValueWhenAFormatFails() {
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
            var failing = new RichTextConverter(List.of(broken), "broken");

            assertThatThrownBy(() -> failing.normalize(Map.of("motivering", "BSN 123456782"),
                    List.of(richText("motivering", "besluit.motivering", RichTextKind.BLOCK))))
                    .isInstanceOf(RichTextConversionException.class)
                    .hasMessageContaining("besluit.motivering")
                    .hasMessageContaining("IllegalStateException")
                    .hasMessageNotContaining("123456782")
                    .hasNoCause();
        }
    }

    @Nested
    class InAMapping {

        private final RichTextConverter converter = new RichTextConverter(shipped(), "plain");
        private final JsonataMappingService mapping = new JsonataMappingService(
                new ExpressionFunctionRegistry(List.of(new RichTextFunction(converter))));

        @Test
        void readsAValueInTheFormatTheMappingNames() {
            var result = mapping.evaluate(
                    "{ \"a\": $richText($doc.html, \"html\"), \"b\": $richText($doc.md, \"markdown\"),"
                            + " \"c\": $richText($doc.plain) }",
                    Map.of("html", "<p><b>x</b></p>", "md", "**y**", "plain", "**z**"), Map.of(), Map.of());

            assertThat(result).containsEntry("a", doc(p(text("x", STRONG))))
                    .containsEntry("b", doc(p(text("y", STRONG))))
                    .containsEntry("c", doc(p(text("**z**"))));
        }

        @Test
        void mapsOverACaseListTheJsonataWay_evenWhenItHasOneItem() {
            TemplateField alineas = richTextList("alineas", RichTextKind.BLOCK);
            for (String expression : List.of(
                    "{ \"alineas\": $doc.items.$richText($, \"markdown\") }",
                    "{ \"alineas\": $map($doc.items, function($v) { $richText($v, \"markdown\") }) }")) {
                var two = converter.normalize(mapping.evaluate(expression,
                        Map.of("items", List.of("*x*", "y")), Map.of(), Map.of()), List.of(alineas));
                var one = converter.normalize(mapping.evaluate(expression,
                        Map.of("items", List.of("*x*")), Map.of(), Map.of()), List.of(alineas));

                assertThat(two).as(expression)
                        .containsEntry("alineas", List.of(doc(p(text("x", Docs.EM))), doc(p(text("y")))));
                assertThat(one).as(expression).containsEntry("alineas", List.of(doc(p(text("x", Docs.EM)))));
            }
        }

        @Test
        void warnsWhatItLostWhenALetterIsGenerated_andOnlyDebugsForAPreview() {
            var function = new RichTextFunction(converter);
            var generating = org.mockito.Mockito.mock(app.epistola.valtimo.expression.ExpressionContext.class);
            org.mockito.Mockito.when(generating.getExecution())
                    .thenReturn(org.mockito.Mockito.mock(org.operaton.bpm.engine.delegate.DelegateExecution.class));
            var previewing = org.mockito.Mockito.mock(app.epistola.valtimo.expression.ExpressionContext.class);

            try (var logs = CapturedLogs.of(RichTextFunction.class)) {
                function.execute(generating, "<h1>Jansen, BSN 123456782</h1>", "html");
                function.execute(previewing, "<h1>Jansen</h1>", "html");
                function.execute(generating, "<p>niets verloren</p>", "html");

                assertThat(logs.at(ch.qos.logback.classic.Level.WARN)).singleElement()
                        .satisfies(event -> assertThat(event.getFormattedMessage())
                                .contains("'html'", "heading \u00D71")
                                .doesNotContain("Jansen", "123456782"));
                assertThat(logs.at(ch.qos.logback.classic.Level.DEBUG)).hasSize(1);
            }
        }

        @Test
        void yieldsNothingForMarkupAroundNothing_soARequiredFieldIsReportedMissing() {
            var result = mapping.evaluate("{ \"a\": $richText($doc.leeg, \"html\") }",
                    Map.of("leeg", "<p><br></p>"), Map.of(), Map.of());

            assertThat(result).doesNotContainKey("a");
        }

        @Test
        void neverPutsTheValueInTheFailureOfANamedFormat() {
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
            var withBroken = new RichTextConverter(List.of(new PlainTextFormat(), broken), "plain");

            assertThatThrownBy(() -> withBroken.toDocument("BSN 123456782", "broken", RichTextKind.BLOCK))
                    .isInstanceOf(RichTextConversionException.class)
                    .hasMessageContaining("'broken'")
                    .hasMessageNotContaining("123456782")
                    .hasNoCause();
        }

        @Test
        void yieldsNothingForNothing_soAnAbsentFieldStaysAbsent() {
            var result = mapping.evaluate("{ \"a\": $richText($doc.missing, \"html\") }", Map.of(), Map.of(), Map.of());

            assertThat(result).doesNotContainKey("a");
        }
    }
}
