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
import app.epistola.valtimo.expression.ExpressionFunctionRegistry;
import app.epistola.valtimo.mapping.JsonataMappingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        void mapsOverACaseListTheJsonataWay_keepingAOneItemListAList() {
            // The array constructor is what keeps a one-item result a list: JSONata would otherwise
            // collapse it into its item, and a list field refuses a single value.
            for (String expression : List.of(
                    "{ \"alineas\": [$doc.items.$richText($, \"markdown\")] }",
                    "{ \"alineas\": [$map($doc.items, function($v) { $richText($v, \"markdown\") })] }")) {
                var two = mapping.evaluate(expression, Map.of("items", List.of("*x*", "y")), Map.of(), Map.of());
                var one = mapping.evaluate(expression, Map.of("items", List.of("*x*")), Map.of(), Map.of());

                assertThat(two).as(expression)
                        .containsEntry("alineas", List.of(doc(p(text("x", Docs.EM))), doc(p(text("y")))));
                assertThat(one).as(expression).containsEntry("alineas", List.of(doc(p(text("x", Docs.EM)))));
            }
        }

        @Test
        void narrowsToOneParagraphForAnInlineField_whenTheMappingSaysSo() {
            var result = mapping.evaluate(
                    "{ \"block\": $richText($doc.html, \"html\"), \"inline\": $richText($doc.html, \"html\", \"inline\") }",
                    Map.of("html", "<p>Een</p><p>Twee</p>"), Map.of(), Map.of());

            assertThat(result)
                    .containsEntry("block", converter.toDocument("<p>Een</p><p>Twee</p>", "html", RichTextKind.BLOCK))
                    .containsEntry("inline", converter.toDocument("<p>Een</p><p>Twee</p>", "html", RichTextKind.INLINE));
            assertThat(result.get("inline")).isNotEqualTo(result.get("block"));
        }

        @Test
        void refusesAKindItDoesNotKnow() {
            assertThatThrownBy(() -> mapping.evaluate("{ \"a\": $richText($doc.html, \"html\", \"alinea\") }",
                    Map.of("html", "<p>x</p>"), Map.of(), Map.of()))
                    .hasMessageContaining("alinea");
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
