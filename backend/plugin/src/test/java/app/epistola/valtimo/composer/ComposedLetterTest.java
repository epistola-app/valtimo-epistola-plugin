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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComposedLetterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsTheLetterAnObjectVariableHolds() {
        ComposedLetter letter = ComposedLetter.from(
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
        ComposedLetter letter = ComposedLetter.from(
                "{\"catalogId\":\"gemeente\",\"templateId\":\"besluit\",\"data\":{\"naam\":\"Jansen\"}}",
                "epistolaLetter",
                objectMapper);

        assertThat(letter.templateId()).isEqualTo("besluit");
        assertThat(letter.data()).containsEntry("naam", "Jansen");
    }

    @Test
    void acceptsALetterWithoutData() {
        ComposedLetter letter = ComposedLetter.from(
                Map.of("catalogId", "gemeente", "templateId", "besluit"), "epistolaLetter", objectMapper);

        assertThat(letter.data()).isEmpty();
    }

    @Test
    void failsLoudlyWhenTheVariableIsEmpty() {
        // Generating nothing would leave a process that looks like it sent a letter.
        assertThatThrownBy(() -> ComposedLetter.from(null, "epistolaLetter", objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epistolaLetter");
    }

    @Test
    void failsWhenTheLetterNamesNoTemplate() {
        assertThatThrownBy(() -> ComposedLetter.from(
                Map.of("data", Map.of("naam", "Jansen")), "epistolaLetter", objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no catalog and template");
    }

    @Test
    void failsOnAValueThatIsNotALetterAtAll() {
        assertThatThrownBy(() -> ComposedLetter.from(42, "epistolaLetter", objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a composed letter");
        assertThatThrownBy(() -> ComposedLetter.from("not json", "epistolaLetter", objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid JSON");
    }
}
