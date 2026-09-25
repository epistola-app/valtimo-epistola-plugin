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

import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateField.FieldType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MissingFieldSelectorTest {

    private TemplateField scalar(String name, boolean required) {
        return new TemplateField(name, name, "string", FieldType.SCALAR, required, null, List.of());
    }

    private TemplateField object(String name, boolean required, TemplateField... children) {
        return new TemplateField(name, name, "object", FieldType.OBJECT, required, null, List.of(children));
    }

    private List<String> namesOf(List<TemplateField> fields) {
        return fields.stream().map(TemplateField::name).toList();
    }

    @Test
    void asksOnlyForWhatTheMappingDidNotFill() {
        List<TemplateField> fields = List.of(scalar("naam", true), scalar("motivatie", true));

        List<TemplateField> missing = MissingFieldSelector.selectMissing(
                fields, Map.of("naam", "Jansen"), true);

        assertThat(namesOf(missing)).containsExactly("motivatie");
    }

    @Test
    void treatsBlankAndEmptyValuesAsMissing() {
        List<TemplateField> fields = List.of(
                scalar("leeg", true), scalar("spaties", true), scalar("gevuld", true));

        List<TemplateField> missing = MissingFieldSelector.selectMissing(
                fields, Map.of("leeg", "", "spaties", "   ", "gevuld", "x"), true);

        assertThat(namesOf(missing)).containsExactly("leeg", "spaties");
    }

    @Test
    void treatsAnExplicitNullAsMissing() {
        Map<String, Object> data = new HashMap<>();
        data.put("motivatie", null);

        List<TemplateField> missing = MissingFieldSelector.selectMissing(
                List.of(scalar("motivatie", true)), data, true);

        assertThat(namesOf(missing)).containsExactly("motivatie");
    }

    @Test
    void skipsOptionalFieldsUnlessAskedForThemToo() {
        List<TemplateField> fields = List.of(scalar("verplicht", true), scalar("optioneel", false));

        assertThat(namesOf(MissingFieldSelector.selectMissing(fields, Map.of(), true)))
                .containsExactly("verplicht");
        assertThat(namesOf(MissingFieldSelector.selectMissing(fields, Map.of(), false)))
                .containsExactly("verplicht", "optioneel");
    }

    @Test
    void keepsTheTreeShapeForNestedObjects() {
        List<TemplateField> fields = List.of(
                object("besluit", true, scalar("datum", true), scalar("motivatie", true)));

        List<TemplateField> missing = MissingFieldSelector.selectMissing(
                fields, Map.of("besluit", Map.of("datum", "2026-01-01")), true);

        assertThat(namesOf(missing)).containsExactly("besluit");
        assertThat(namesOf(missing.get(0).children())).containsExactly("motivatie");
    }

    @Test
    void dropsAnObjectWhoseChildrenAreAllFilled() {
        List<TemplateField> fields = List.of(object("besluit", true, scalar("datum", true)));

        List<TemplateField> missing = MissingFieldSelector.selectMissing(
                fields, Map.of("besluit", Map.of("datum", "2026-01-01")), true);

        assertThat(missing).isEmpty();
    }

    @Test
    void leavesAnAbsentOptionalObjectAlone() {
        // Asking for the parts of a block the letter does not use would be noise.
        List<TemplateField> fields = List.of(object("correspondentie", false, scalar("adres", true)));

        assertThat(MissingFieldSelector.selectMissing(fields, Map.of(), true)).isEmpty();
        assertThat(namesOf(MissingFieldSelector.selectMissing(fields, Map.of(), false)))
                .containsExactly("correspondentie");
    }

    @Test
    void asksForTheChildrenOfARequiredObjectTheMappingSkipped() {
        List<TemplateField> fields = List.of(object("besluit", true, scalar("motivatie", true)));

        List<TemplateField> missing = MissingFieldSelector.selectMissing(fields, Map.of(), true);

        assertThat(namesOf(missing.get(0).children())).containsExactly("motivatie");
    }

    @Test
    void asksForAnEmptyRequiredArrayButNotAFilledOne() {
        TemplateField array = new TemplateField(
                "bijlagen", "bijlagen", "array", FieldType.ARRAY, true, null, List.of(scalar("naam", true)));

        assertThat(namesOf(MissingFieldSelector.selectMissing(List.of(array), Map.of(), true)))
                .containsExactly("bijlagen");
        assertThat(MissingFieldSelector.selectMissing(
                List.of(array), Map.of("bijlagen", List.of(Map.of("naam", "x"))), true)).isEmpty();
    }

    @Test
    void handlesMissingInputsWithoutBlowingUp() {
        assertThat(MissingFieldSelector.selectMissing(null, Map.of(), true)).isEmpty();
        assertThat(namesOf(MissingFieldSelector.selectMissing(List.of(scalar("x", true)), null, true)))
                .containsExactly("x");
    }
}
