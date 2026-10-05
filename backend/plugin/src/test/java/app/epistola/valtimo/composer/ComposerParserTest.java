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
package app.epistola.valtimo.composer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a form definition's JSON means, with no Valtimo in sight.
 *
 * <p>These rules used to be reachable only through {@code ComposerConfigurationResolverTest} and
 * its four mocked services — a process link service, a form repository, a form flow service and a
 * case definition service — none of which has anything to do with whether a letter without a
 * catalog is usable. Splitting the parser out of the resolver is what makes this file possible,
 * and the mock-free version is the one worth editing when the stored shape changes.
 */
class ComposerParserTest {

    private static final UUID PLUGIN_CONFIGURATION_ID = UUID.randomUUID();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private java.util.List<LetterComposerConfiguration> parse(String json) {
        try {
            return ComposerParser.composersOn(objectMapper.readTree(json).path("components"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String composer(String extra) {
        return """
                {"components":[
                  {"type":"epistola-letter-composer","key":"pv:epistolaLetter",
                   "pluginConfigurationId":"%s","catalogId":"gemeente",
                   "dataMapping":"{\\"naam\\": $doc.naam}",
                   "templates":[{"templateId":"besluit","label":"Besluit"},{"templateId":"herinnering"}]%s}
                ]}
                """.formatted(PLUGIN_CONFIGURATION_ID, extra);
    }

    /**
     * Write-back is optional, and a composer that declares none must be as usable as one that does.
     *
     * <p>Worth pinning rather than assuming: the parser, the configuration and the service each
     * have their own idea of "no rules" (absent key, null map, empty map), and a composer that
     * silently stopped working because it saves nothing would be a bad way to find that out.
     */
    @Test
    void aComposerThatSavesNothingIsStillAComposer() {
        for (String writeBack : new String[] {"", ",\"writeBack\":{}", ",\"writeBack\":null"}) {
            var found = parse("""
                    {"components":[{"type":"epistola-letter-composer","key":"pv:brief",
                      "pluginConfigurationId":"%s","catalogId":"gemeente",
                      "templates":[{"templateId":"besluit"}]%s}]}
                    """.formatted(PLUGIN_CONFIGURATION_ID, writeBack));

            assertThat(found)
                    .describedAs("composer with writeBack '%s'", writeBack)
                    .singleElement()
                    .satisfies(c -> {
                        assertThat(c.componentKey()).isEqualTo("pv:brief");
                        assertThat(c.writeBack()).isEmpty();
                        assertThat(c.templates()).hasSize(1);
                    });
        }
    }

    @Test
    void findsAComposerHoweverDeeplyALayoutNestsIt() {
        var found = parse("""
                {"components":[{"type":"panel","components":[{"type":"columns","columns":[
                  {"components":[{"type":"epistola-letter-composer","key":"pv:brief",
                    "pluginConfigurationId":"%s","catalogId":"gemeente",
                    "templates":[{"templateId":"besluit"}]}]}
                ]}]}]}
                """.formatted(PLUGIN_CONFIGURATION_ID));

        assertThat(found).singleElement()
                .satisfies(c -> assertThat(c.componentKey()).isEqualTo("pv:brief"));
    }

    @Test
    void labelsALetterWithItsIdWhenTheFormNamesNone() {
        assertThat(parse(composer("")).get(0).requireOne(null, "herinnering").label())
                .isEqualTo("herinnering");
    }

    @Test
    void readsTheOptionalFieldsSetting() {
        assertThat(parse(composer("")).get(0).askOptionalFields()).isFalse();
        assertThat(parse(composer(",\"askOptionalFields\":true")).get(0).askOptionalFields()).isTrue();
    }

    @Test
    void readsACatalogNamedOnTheLetterItself() {
        var found = parse("""
                {"components":[{"type":"epistola-letter-composer","key":"pv:brief",
                  "pluginConfigurationId":"%s","catalogId":"gemeente",
                  "templates":[{"templateId":"besluit"},{"templateId":"aanmaning","catalogId":"landelijk"}]}]}
                """.formatted(PLUGIN_CONFIGURATION_ID));

        assertThat(found.get(0).requireOne(null, "besluit").catalogId()).isEqualTo("gemeente");
        assertThat(found.get(0).requireOne(null, "aanmaning").catalogId()).isEqualTo("landelijk");
    }

    @Test
    void dropsALetterWithNoCatalogAnywhere() {
        // Which catalog a letter comes from decides what is rendered; there is nothing to guess.
        var found = parse("""
                {"components":[{"type":"epistola-letter-composer","key":"pv:brief",
                  "pluginConfigurationId":"%s",
                  "templates":[{"templateId":"besluit","catalogId":"gemeente"},{"templateId":"zwevend"}]}]}
                """.formatted(PLUGIN_CONFIGURATION_ID));

        assertThat(found.get(0).offers("besluit")).isTrue();
        assertThat(found.get(0).offers("zwevend")).isFalse();
    }

    @Test
    void ignoresAComposerWithNothingUsableOnIt() {
        assertThat(parse("""
                {"components":[{"type":"epistola-letter-composer","key":"pv:brief"}]}
                """)).isEmpty();
    }

    @Test
    void readsTheSettingsWidgetsNestedShapeAndTheHandWrittenFlatOne() {
        var nested = parse("""
                {"components":[{"type":"epistola-letter-composer","key":"pv:brief",
                  "letterSet":{"pluginConfigurationId":"%s","catalogId":"gemeente",
                    "templates":[{"templateId":"besluit"}]}}]}
                """.formatted(PLUGIN_CONFIGURATION_ID));

        assertThat(nested).singleElement()
                .satisfies(c -> assertThat(c.offers("besluit")).isTrue());
        assertThat(parse(composer("")).get(0).offers("besluit")).isTrue();
    }

    @Test
    void carriesTheSchemaVersionWithoutJudgingIt() {
        // Read structurally, refused at the point of use — so one component from a newer plugin
        // does not remove every composer on that form.
        assertThat(parse(composer("")).get(0).schemaVersion()).isNull();
        assertThat(parse(composer(",\"schemaVersion\":99")).get(0).schemaVersion()).isEqualTo(99);
    }

    @Test
    void findsEveryComposerOnAForm() {
        var found = parse("""
                {"components":[
                  {"type":"epistola-letter-composer","key":"pv:een","pluginConfigurationId":"%s",
                   "catalogId":"gemeente","templates":[{"templateId":"besluit"}]},
                  {"type":"epistola-letter-composer","key":"pv:twee","pluginConfigurationId":"%s",
                   "catalogId":"gemeente","templates":[{"templateId":"besluit"}]}]}
                """.formatted(PLUGIN_CONFIGURATION_ID, PLUGIN_CONFIGURATION_ID));

        assertThat(found).extracting(LetterComposerConfiguration::componentKey)
                .containsExactly("pv:een", "pv:twee");
    }
}
