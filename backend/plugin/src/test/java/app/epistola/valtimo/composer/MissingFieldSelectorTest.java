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

        List<TemplateField> missing = MissingFieldSelector.select(
                fields, Map.of("naam", "Jansen"), true).askable();

        assertThat(namesOf(missing)).containsExactly("motivatie");
    }

    @Test
    void treatsBlankAndEmptyValuesAsMissing() {
        List<TemplateField> fields = List.of(
                scalar("leeg", true), scalar("spaties", true), scalar("gevuld", true));

        List<TemplateField> missing = MissingFieldSelector.select(
                fields, Map.of("leeg", "", "spaties", "   ", "gevuld", "x"), true).askable();

        assertThat(namesOf(missing)).containsExactly("leeg", "spaties");
    }

    @Test
    void treatsAnExplicitNullAsMissing() {
        Map<String, Object> data = new HashMap<>();
        data.put("motivatie", null);

        List<TemplateField> missing = MissingFieldSelector.select(
                List.of(scalar("motivatie", true)), data, true).askable();

        assertThat(namesOf(missing)).containsExactly("motivatie");
    }

    @Test
    void skipsOptionalFieldsUnlessAskedForThemToo() {
        List<TemplateField> fields = List.of(scalar("verplicht", true), scalar("optioneel", false));

        assertThat(namesOf(MissingFieldSelector.select(fields, Map.of(), true).askable()))
                .containsExactly("verplicht");
        assertThat(namesOf(MissingFieldSelector.select(fields, Map.of(), false).askable()))
                .containsExactly("verplicht", "optioneel");
    }

    @Test
    void keepsTheTreeShapeForNestedObjects() {
        List<TemplateField> fields = List.of(
                object("besluit", true, scalar("datum", true), scalar("motivatie", true)));

        List<TemplateField> missing = MissingFieldSelector.select(
                fields, Map.of("besluit", Map.of("datum", "2026-01-01")), true).askable();

        assertThat(namesOf(missing)).containsExactly("besluit");
        assertThat(namesOf(missing.get(0).children())).containsExactly("motivatie");
    }

    @Test
    void dropsAnObjectWhoseChildrenAreAllFilled() {
        List<TemplateField> fields = List.of(object("besluit", true, scalar("datum", true)));

        List<TemplateField> missing = MissingFieldSelector.select(
                fields, Map.of("besluit", Map.of("datum", "2026-01-01")), true).askable();

        assertThat(missing).isEmpty();
    }

    @Test
    void leavesAnAbsentOptionalObjectAlone() {
        // Asking for the parts of a block the letter does not use would be noise.
        List<TemplateField> fields = List.of(object("correspondentie", false, scalar("adres", true)));

        assertThat(MissingFieldSelector.select(fields, Map.of(), true).askable()).isEmpty();
        assertThat(namesOf(MissingFieldSelector.select(fields, Map.of(), false).askable()))
                .containsExactly("correspondentie");
    }

    @Test
    void asksForTheChildrenOfARequiredObjectTheMappingSkipped() {
        List<TemplateField> fields = List.of(object("besluit", true, scalar("motivatie", true)));

        List<TemplateField> missing = MissingFieldSelector.select(fields, Map.of(), true).askable();

        assertThat(namesOf(missing.get(0).children())).containsExactly("motivatie");
    }

    @Test
    void asksForAnEmptyRequiredArrayButNotAFilledOne() {
        TemplateField array = new TemplateField(
                "bijlagen", "bijlagen", "array", FieldType.ARRAY, true, null, List.of(scalar("naam", true)));

        assertThat(namesOf(MissingFieldSelector.select(List.of(array), Map.of(), true).askable()))
                .containsExactly("bijlagen");
        assertThat(MissingFieldSelector.select(
                List.of(array), Map.of("bijlagen", List.of(Map.of("naam", "x"))), true).askable()).isEmpty();
    }

    @Test
    void handlesMissingInputsWithoutBlowingUp() {
        assertThat(MissingFieldSelector.select(null, Map.of(), true).askable()).isEmpty();
        assertThat(namesOf(MissingFieldSelector.select(List.of(scalar("x", true)), null, true).askable()))
                .containsExactly("x");
    }

    /**
     * A value with no parts to fill in — a rich-text body, a recursive structure, anything the
     * analyzer marked as needing a complete-value mapping. It used to be dropped, which read as
     * "this letter needs no further input" right before Epistola refused to render it.
     */
    private TemplateField undecomposable(String name, boolean required) {
        return new TemplateField(
                name, name, "object", FieldType.OBJECT, required, null, List.of(), true,
                "Recursive JSON Schema references require a complete-value mapping.", false, null);
    }

    @Test
    void reportsARequiredValueItCannotAskFor() {
        var selection = MissingFieldSelector.select(List.of(undecomposable("body", true)), Map.of(), true);

        assertThat(namesOf(selection.unsupported())).containsExactly("body");
        assertThat(selection.askable()).isEmpty();
    }

    @Test
    void doesNotReportOneTheMappingAlreadyFilled() {
        // The normal case: the letter body comes from the case, so nothing has to be asked.
        var selection = MissingFieldSelector.select(
                List.of(undecomposable("body", true)),
                Map.of("body", Map.of("type", "doc")),
                true);

        assertThat(selection.unsupported()).isEmpty();
        assertThat(selection.complete()).isTrue();
    }

    @Test
    void doesNotReportAnOptionalOne() {
        // Not being able to offer an optional field costs nothing; refusing the letter over it
        // would cost the whole letter.
        var selection = MissingFieldSelector.select(
                List.of(undecomposable("body", false)), Map.of(), false);

        assertThat(selection.unsupported()).isEmpty();
        assertThat(selection.askable()).isEmpty();
    }

    @Test
    void reportsOneNestedInsideARequiredObject() {
        TemplateField parent = new TemplateField(
                "brief", "brief", "object", FieldType.OBJECT, true, null,
                List.of(undecomposable("body", true)));

        var selection = MissingFieldSelector.select(List.of(parent), Map.of(), true);

        assertThat(namesOf(selection.unsupported())).containsExactly("body");
    }

    @Test
    void reportsARequiredArrayWithNoItemFields() {
        TemplateField array = new TemplateField(
                "blokken", "blokken", "array", FieldType.ARRAY, true, null, List.of(), true,
                "A oneOf of item shapes requires a complete-value mapping.", false, null);

        var selection = MissingFieldSelector.select(List.of(array), Map.of(), true);

        assertThat(namesOf(selection.unsupported())).containsExactly("blokken");
        assertThat(selection.askable()).isEmpty();
    }

    @Test
    void stillAsksForAnOrdinaryArrayOfScalars() {
        // A SCALAR typed array has no children by design and must not be mistaken for one of these.
        var selection = MissingFieldSelector.select(
                List.of(new TemplateField("tags", "tags", "array", FieldType.SCALAR, true, null, List.of())),
                Map.of(),
                true);

        assertThat(selection.unsupported()).isEmpty();
        assertThat(namesOf(selection.askable())).containsExactly("tags");
    }

    @Test
    void reportsAnExternalReferenceEvenThoughItInfersScalar() {
        // What the analyzer produces for {"$ref": "https://…/rich-text.json"}: no type and no
        // properties to infer from, so SCALAR — and left to the scalar branch it would become a
        // single-line text box for a value that is not text.
        TemplateField externalRef = new TemplateField(
                "body", "body", "rich-text.json", FieldType.SCALAR, true, null, List.of(), true,
                "External JSON Schema references require a complete-value mapping.", false, null);

        var selection = MissingFieldSelector.select(List.of(externalRef), Map.of(), true);

        assertThat(namesOf(selection.unsupported())).containsExactly("body");
        assertThat(selection.askable()).isEmpty();
    }

    @Test
    void asksForAnExternalReferenceNever_butOnlyWhenTheMappingLeftItEmpty() {
        TemplateField externalRef = new TemplateField(
                "body", "body", "rich-text.json", FieldType.SCALAR, true, null, List.of(), true,
                "External JSON Schema references require a complete-value mapping.", false, null);

        var selection = MissingFieldSelector.select(
                List.of(externalRef), Map.of("body", Map.of("type", "doc")), true);

        assertThat(selection.unsupported()).isEmpty();
        assertThat(selection.complete()).isTrue();
    }

    @Test
    void stillAsksForAnOrdinaryArrayOfObjects() {
        // The analyzer marks every array of objects complex — "must be mapped as a complete value"
        // is the mapping builder's rule, not a rendering one — and it decomposes into a data grid
        // perfectly well. Refusing letters over it would have refused the permit demo.
        TemplateField activities = new TemplateField(
                "activities", "activities", "array", FieldType.ARRAY, true, null,
                List.of(scalar("type", true), scalar("description", true)), true,
                "Arrays of objects must be mapped as a complete value.", false, null);

        var selection = MissingFieldSelector.select(List.of(activities), Map.of(), true);

        assertThat(selection.unsupported()).isEmpty();
        assertThat(namesOf(selection.askable())).containsExactly("activities");
    }

    @Test
    void stillAsksForAnObjectTheAnalyzerFlaggedButCouldStillDecompose() {
        TemplateField block = new TemplateField(
                "besluit", "besluit", "object", FieldType.OBJECT, true, null,
                List.of(scalar("grond", true)), true,
                "Conditional schemas must be mapped as a complete value.", false, null);

        var selection = MissingFieldSelector.select(List.of(block), Map.of(), true);

        assertThat(selection.unsupported()).isEmpty();
        assertThat(namesOf(selection.askable())).containsExactly("besluit");
    }
}
