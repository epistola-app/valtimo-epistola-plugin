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
package app.epistola.valtimo.domain;

import app.epistola.valtimo.composer.ComposerException;
import app.epistola.valtimo.composer.ComposerSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicDocumentTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * A field someone cleared is a null in the letter, and a null must not cost the whole letter.
     *
     * <p>`Map.copyOf` refuses a null value, so a letter carrying one failed to be read at all — and
     * it failed inside the generate action, where the only visible symptom is an activity that
     * threw `NullPointerException` with no message. The browser prunes empties today, which is the
     * only reason this was not hit; a letter written by a process, by an older plugin version, or
     * by a form that stores an explicit null is enough to reach it.
     */
    @Test
    void readsALetterWhoseInputWasCleared() {
        Map<String, Object> inputs = new java.util.HashMap<>();
        inputs.put("motivation", null);
        inputs.put("decisionType", "gegrond");
        Map<String, Object> raw = new java.util.HashMap<>();
        raw.put("catalogId", "gemeente");
        raw.put("templateId", "besluit");
        raw.put("data", Map.of("naam", "Jansen"));
        raw.put("inputs", inputs);

        DynamicDocument letter = DynamicDocument.from(raw, "epistolaLetter", objectMapper);

        assertThat(letter.inputs())
                .describedAs("a cleared field is still part of what was typed")
                .containsEntry("decisionType", "gegrond")
                .containsEntry("motivation", null);
    }

    @Test
    void readsALetterWhoseDataHoldsAClearedValue() {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("naam", null);
        Map<String, Object> raw = new java.util.HashMap<>();
        raw.put("catalogId", "gemeente");
        raw.put("templateId", "besluit");
        raw.put("data", data);

        DynamicDocument letter = DynamicDocument.from(raw, "epistolaLetter", objectMapper);

        assertThat(letter.data()).containsEntry("naam", null);
    }

    /**
     * The letter is read from a process variable the engine owns. Keeping a reference to that map
     * would let a later activity's edit change what this letter says it sent, after the fact.
     */
    @Test
    void doesNotKeepTheVariablesOwnMap() {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("naam", "Jansen");
        Map<String, Object> raw = new java.util.HashMap<>();
        raw.put("catalogId", "gemeente");
        raw.put("templateId", "besluit");
        raw.put("data", data);

        DynamicDocument letter = DynamicDocument.from(raw, "epistolaLetter", objectMapper);
        data.put("naam", "iemand anders");

        assertThat(letter.data())
                .describedAs("the letter holds what it was read with")
                .containsEntry("naam", "Jansen");
    }

    /**
     * What a process builds and what the action reads are the same thing, and this is where that
     * is kept true. A round trip rather than two assertions: if either side drifts, the document a
     * process prepared stops being renderable, and the only place that shows up is a failed
     * activity in someone else's system.
     */
    @Test
    void aPreparedDocumentIsReadBackAsOneLetter() {
        Map<String, Object> prepared = DynamicDocument.of(
                "gemeente", "besluit-bezwaar", Map.of("naam", "Jansen"));

        DynamicDocument letter = DynamicDocument.from(prepared, "epistolaLetter", objectMapper);

        assertThat(letter.catalogId()).isEqualTo("gemeente");
        assertThat(letter.templateId()).isEqualTo("besluit-bezwaar");
        assertThat(letter.data()).containsEntry("naam", "Jansen");
        assertThat(letter.schemaVersion()).isEqualTo(ComposerSchema.CURRENT);
        assertThat(letter.inputs())
                .describedAs("a document a process prepared has no typed input")
                .isEmpty();
        assertThat(letter.writeBack())
                .describedAs("and nothing says where its values belong")
                .isEmpty();
    }

    @Test
    void refusesAPreparedDocumentThatNamesNoTemplate() {
        assertThatThrownBy(() -> DynamicDocument.of("gemeente", " ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalog and a template");
    }

    @Test
    void preparesADocumentWithNoDataAtAll() {
        // A template that needs nothing is a real template; null should not be a different case
        // from empty for a caller assembling this by hand.
        assertThat(DynamicDocument.from(
                DynamicDocument.of("gemeente", "besluit", null),
                "epistolaLetter", objectMapper).data())
                .isEmpty();
    }

    @Test
    void readsTheLetterAnObjectVariableHolds() {
        DynamicDocument letter = DynamicDocument.from(
                Map.of("catalogId", "gemeente", "templateId", "besluit", "data", Map.of("naam", "Jansen")),
                "epistolaLetter",
                objectMapper);

        assertThat(letter.catalogId()).isEqualTo("gemeente");
        assertThat(letter.templateId()).isEqualTo("besluit");
        assertThat(letter.data()).containsEntry("naam", "Jansen");
    }

    @Test
    void readsTheLetterAStringVariableHolds() {
        // A process may write the letter as JSON; Operaton hands back exactly what was stored.
        DynamicDocument letter = DynamicDocument.from(
                "{\"catalogId\":\"gemeente\",\"templateId\":\"besluit\",\"data\":{\"naam\":\"Jansen\"}}",
                "epistolaLetter",
                objectMapper);

        assertThat(letter.templateId()).isEqualTo("besluit");
        assertThat(letter.data()).containsEntry("naam", "Jansen");
    }

    @Test
    void acceptsALetterWithoutData() {
        DynamicDocument letter = DynamicDocument.from(
                Map.of("catalogId", "gemeente", "templateId", "besluit"), "epistolaLetter", objectMapper);

        assertThat(letter.data()).isEmpty();
    }

    @Test
    void failsLoudlyWhenTheVariableIsEmpty() {
        // Generating nothing would leave a process that looks like it sent a letter.
        assertThatThrownBy(() -> DynamicDocument.from(null, "epistolaLetter", objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epistolaLetter");
    }

    @Test
    void failsWhenTheLetterNamesNoTemplate() {
        assertThatThrownBy(() -> DynamicDocument.from(
                Map.of("data", Map.of("naam", "Jansen")), "epistolaLetter", objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no catalog and template");
    }

    @Test
    void failsOnAValueThatIsNotALetterAtAll() {
        assertThatThrownBy(() -> DynamicDocument.from(42, "epistolaLetter", objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a composed letter");
        assertThatThrownBy(() -> DynamicDocument.from("not json", "epistolaLetter", objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid JSON");
    }

    @Test
    void readsALetterThatPredatesTheSchemaVersion() {
        // A process instance composed before the field existed may still be waiting for its
        // generate task; an upgrade must not strand it.
        DynamicDocument letter = DynamicDocument.from(
                Map.of("catalogId", "gemeente", "templateId", "besluit"),
                "epistolaLetter",
                objectMapper);

        assertThat(letter.schemaVersion()).isEqualTo(1);
    }

    @Test
    void readsTheVersionTheComposerStamped() {
        DynamicDocument letter = DynamicDocument.from(
                Map.of("schemaVersion", 1, "catalogId", "gemeente", "templateId", "besluit"),
                "epistolaLetter",
                objectMapper);

        assertThat(letter.schemaVersion()).isEqualTo(1);
    }

    @Test
    void refusesALetterWrittenByALaterPlugin() {
        // Generating the wrong letter is worse than not generating one: what a later schema means
        // by these fields is exactly what this plugin cannot know.
        assertThatThrownBy(() -> DynamicDocument.from(
                Map.of("schemaVersion", 99, "catalogId", "gemeente", "templateId", "besluit"),
                "epistolaLetter",
                objectMapper))
                .isInstanceOf(ComposerException.class)
                .hasMessageContaining("99")
                .hasMessageContaining("Upgrade the Epistola plugin");
    }

    @Test
    void refusesALetterWhoseVersionIsNotAVersion() {
        assertThatThrownBy(() -> DynamicDocument.from(
                "{\"schemaVersion\":\"tweede\",\"catalogId\":\"gemeente\",\"templateId\":\"besluit\"}",
                "epistolaLetter",
                objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void readsTheEnvelopeShapeMultipleLettersWillUse() {
        // Nothing writes this yet — letting an employee choose how many letters go out is unbuilt
        // — but reading it is what keeps the shape open, so that feature is a behaviour change
        // rather than a breaking one.
        var letters = DynamicDocument.allFrom(
                Map.of("schemaVersion", 1, "letters", List.of(
                        Map.of("catalogId", "gemeente", "templateId", "besluit", "data", Map.of("a", 1)),
                        Map.of("catalogId", "landelijk", "templateId", "aanmaning"))),
                "epistolaLetter",
                objectMapper);

        assertThat(letters).hasSize(2);
        assertThat(letters.get(0).templateId()).isEqualTo("besluit");
        assertThat(letters.get(1).catalogId()).isEqualTo("landelijk");
        assertThat(letters.get(0).schemaVersion()).isEqualTo(1);
    }

    @Test
    void readsASingleLetterAsAListOfOne() {
        var letters = DynamicDocument.allFrom(
                Map.of("catalogId", "gemeente", "templateId", "besluit"), "epistolaLetter", objectMapper);

        assertThat(letters).hasSize(1);
    }

    @Test
    void refusesMoreThanOneLetterWithASentenceRatherThanDroppingTheRest() {
        // The shape is readable, the behaviour is not built. Generating the first and silently
        // discarding the others would be the worst of both.
        assertThatThrownBy(() -> DynamicDocument.from(
                Map.of("letters", List.of(
                        Map.of("catalogId", "gemeente", "templateId", "besluit"),
                        Map.of("catalogId", "gemeente", "templateId", "aanmaning"))),
                "epistolaLetter",
                objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holds 2 letters")
                .hasMessageContaining("a composer per letter");
    }

    @Test
    void readsOneLetterFromTheEnvelopeShape() {
        DynamicDocument letter = DynamicDocument.from(
                Map.of("letters", List.of(Map.of("catalogId", "gemeente", "templateId", "besluit"))),
                "epistolaLetter",
                objectMapper);

        assertThat(letter.templateId()).isEqualTo("besluit");
    }

    @Test
    void refusesAnEmptyEnvelope() {
        assertThatThrownBy(() -> DynamicDocument.from(
                Map.of("letters", List.of()), "epistolaLetter", objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no letters");
    }
}
