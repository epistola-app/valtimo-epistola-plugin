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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.epistola.valtimo.mapping.JsonataMappingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import app.epistola.valtimo.expression.ExpressionFunctionRegistry;
import com.ritense.document.domain.impl.JsonSchemaDocument;
import com.ritense.document.domain.impl.JsonSchemaDocumentDefinitionId;
import com.ritense.document.service.DocumentService;
import com.ritense.valueresolver.ValueResolverService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Write-back applied when a form is submitted.
 *
 * <p>Two properties carry the weight. The <b>rules come from the stored form</b> and the values
 * from the submission, so a crafted letter can change what a rule sees but cannot introduce a rule
 * or name a destination. And <b>nothing here throws</b>: the employee has already finished, so a
 * correction that cannot be worked out is dropped rather than taking the submission with it.
 */
class ComposerWriteBackServiceTest {

    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final String CASE_KEY = "correspondentie";

    private final ComposerConfigurationResolver resolver = mock(ComposerConfigurationResolver.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final ValueResolverService valueResolverService = mock(ValueResolverService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ComposerWriteBackService service;

    @BeforeEach
    void setUp() {
        service = new ComposerWriteBackService(
                resolver,
                documentService,
                new JsonataMappingService(new ExpressionFunctionRegistry(List.of())),
                valueResolverService,
                objectMapper);

        var definitionId = mock(JsonSchemaDocumentDefinitionId.class);
        when(definitionId.name()).thenReturn(CASE_KEY);
        var document = mock(JsonSchemaDocument.class);
        when(document.definitionId()).thenReturn(definitionId);
        // doReturn: findBy is declared with a wildcard, which thenReturn cannot satisfy.
        org.mockito.Mockito.doReturn(Optional.of(document)).when(documentService).findBy(any());
    }

    private void declaring(Map<String, String> writeBack) {
        when(resolver.forCaseDefinition(CASE_KEY)).thenReturn(List.of(new LetterComposerConfiguration(
                "pv:epistolaLetter", null, UUID.randomUUID(), "gemeente", null,
                List.of(new LetterComposerConfiguration.OfferedTemplate("gemeente", "besluit", "Besluit", null)),
                false,
                writeBack)));
    }

    private static Map<String, Object> letter(Map<String, Object> data, Map<String, Object> inputs) {
        return Map.of(
                "schemaVersion", 1,
                "catalogId", "gemeente",
                "templateId", "besluit",
                "data", data,
                "inputs", inputs);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> written() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(valueResolverService).handleValues(eq(DOCUMENT_ID), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a failure finding the rules blocks the submission")
    void failingRuleLookupBlocks() {
        // Write-back runs inside the submission's transaction, so this rolls the submission back
        // rather than leaving a case worker believing their decision was recorded. Without the
        // configuration there is no way to know whether this letter had values to save, so
        // carrying on would be a guess. (It swallowed this until write-back moved to submission
        // time, where the letter was already irreversible and a retry would have sent a second.)
        when(resolver.forCaseDefinition(CASE_KEY)).thenThrow(new RuntimeException("database is gone"));

        assertThatThrownBy(() -> service.applyFromSubmission(
                DOCUMENT_ID, Map.of("epistolaLetter", letter(Map.of(), Map.of("decisionType", "gegrond")))))
                .isInstanceOf(ComposerWriteBackException.class)
                .hasMessageContaining("configuration could not be read");

        verify(valueResolverService, never()).handleValues(any(UUID.class), any());
    }

    @Test
    @DisplayName("only the composer that produced this letter decides where its values go")
    void usesTheRulesOfTheComposerTheLetterCameFrom() {
        // A case type may carry several composers — the demo has three. Applying all of their rules
        // to whichever letter was generated means a rule written for one letter is evaluated
        // against another, and `$letter.x` is not nothing just because it came from the wrong
        // letter. The letter arrives on a named process variable, and a composer declares the key
        // it writes to, so there is no need to guess.
        when(resolver.forCaseDefinition(CASE_KEY)).thenReturn(List.of(
                composerWriting("pv:epistolaLetter", Map.of("pv:uitBrief", "$inputs.decisionType")),
                composerWriting("pv:andereBrief", Map.of("pv:uitAndereBrief", "$inputs.decisionType"))));

        service.apply(DOCUMENT_ID, letter(Map.of(), Map.of("decisionType", "gegrond")), "epistolaLetter");

        assertThat(written())
                .containsEntry("pv:uitBrief", "gegrond")
                .describedAs("the other composer's rule is not this letter's business")
                .doesNotContainKey("pv:uitAndereBrief");
    }

    @Test
    @DisplayName("a letter whose variable matches no composer writes nothing")
    void writesNothingWhenNoComposerClaimsTheVariable() {
        // Writing from a composer that did not produce this letter is worse than writing nothing,
        // so the mismatch is reported rather than papered over by falling back to every composer.
        when(resolver.forCaseDefinition(CASE_KEY)).thenReturn(List.of(
                composerWriting("pv:andereBrief", Map.of("pv:uitAndereBrief", "$inputs.decisionType"))));

        service.apply(DOCUMENT_ID, letter(Map.of(), Map.of("decisionType", "gegrond")), "epistolaLetter");

        verify(valueResolverService, never()).handleValues(any(UUID.class), any());
    }

    private static LetterComposerConfiguration composerWriting(String componentKey, Map<String, String> writeBack) {
        return new LetterComposerConfiguration(
                componentKey, null, UUID.randomUUID(), "gemeente", null,
                List.of(new LetterComposerConfiguration.OfferedTemplate("gemeente", "besluit", "Besluit", null)),
                false,
                writeBack);
    }

    @Test
    @DisplayName("one destination Valtimo refuses does not cost the others")
    void salvagesTheWritesThatCanBeMade() {
        // resolve() already promises that one bad expression does not cost the others. The write
        // did not keep that promise: every destination went in one handleValues call, so a single
        // path the case schema refuses threw and took every other value with it — silently, since
        // the failure is logged and swallowed by design.
        declaring(Map.of(
                "doc:/besluit/type", "$inputs.decisionType",
                "doc:/niet/bestaand", "$inputs.motivation"));
        org.mockito.Mockito.doThrow(new RuntimeException("case refuses /niet/bestaand"))
                .when(valueResolverService)
                .handleValues(eq(DOCUMENT_ID), argThat(values -> values != null && values.size() > 1));

        service.apply(DOCUMENT_ID, letter(
                Map.of(),
                Map.of("decisionType", "gegrond", "motivation", "omdat het kan")));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(valueResolverService, atLeastOnce()).handleValues(eq(DOCUMENT_ID), captor.capture());
        Map<String, Object> salvaged = new java.util.LinkedHashMap<>();
        captor.getAllValues().stream().filter(v -> v.size() == 1).forEach(salvaged::putAll);
        assertThat(salvaged)
                .describedAs("the value that could be written still reaches the case")
                .containsEntry("doc:/besluit/type", "gegrond");
    }

    @Test
    @DisplayName("a destination with no resolver prefix is refused before it can fail a write")
    void refusesADestinationWithoutAPrefix() {
        // `besluit` names nothing Valtimo can resolve. Letting it through means the whole write
        // fails at runtime, far from the form that declared it.
        declaring(Map.of("besluit", "$inputs.decisionType"));

        service.apply(DOCUMENT_ID, letter(Map.of(), Map.of("decisionType", "gegrond")));

        verify(valueResolverService, never()).handleValues(any(UUID.class), any());
    }

    @Test
    @DisplayName("writes the value a rule names, to the destination the form declares")
    void writesWhatTheRuleResolves() {
        declaring(Map.of("doc:/aanvrager/telefoon", "$inputs.telefoon"));

        service.apply(DOCUMENT_ID, letter(Map.of("telefoon", "0612345678"), Map.of("telefoon", "0612345678")));

        assertThat(written()).containsExactly(Map.entry("doc:/aanvrager/telefoon", "0612345678"));
    }

    @Test
    @DisplayName("a process variable is as writable as a case path")
    void writesAProcessVariable() {
        // Valtimo's own resolver finds the instance by business key when all it has is a document,
        // which is why submission-time write-back is not limited to the case document.
        declaring(Map.of("pv:some-value", "$data.some.property"));

        service.apply(DOCUMENT_ID, letter(Map.of("some", Map.of("property", "geschreven")), Map.of()));

        assertThat(written()).containsExactly(Map.entry("pv:some-value", "geschreven"));
    }

    @Test
    @DisplayName("$data is the letter and $inputs only what a person typed")
    void bindsBothContexts() {
        declaring(Map.of(
                "doc:/uit-de-brief", "$data.veld",
                "doc:/uit-de-hand", "$inputs.veld"));

        service.apply(DOCUMENT_ID, letter(Map.of("veld", "uit de mapping"), Map.of("veld", "getypt")));

        assertThat(written())
                .containsEntry("doc:/uit-de-brief", "uit de mapping")
                .containsEntry("doc:/uit-de-hand", "getypt");
    }

    @Test
    @DisplayName("$letter is the same letter as $data, under a name that reads better in a rule")
    void letterIsAnAliasForData() {
        // $data is kept because it is the contract's own word for that object; $letter because
        // "the letter's phone number" is what a rule is actually saying.
        declaring(Map.of(
                "doc:/via-data", "$data.veld",
                "doc:/via-letter", "$letter.veld"));

        service.apply(DOCUMENT_ID, letter(Map.of("veld", "zelfde waarde"), Map.of()));

        assertThat(written())
                .containsEntry("doc:/via-data", "zelfde waarde")
                .containsEntry("doc:/via-letter", "zelfde waarde");
    }

    @Test
    @DisplayName("a rule that yields nothing writes nothing")
    void yieldsNothing() {
        // What keeps "only write what was actually supplied" the default, instead of clobbering
        // good case data with nulls.
        declaring(Map.of("doc:/aanvrager/telefoon", "$inputs.telefoon"));

        service.apply(DOCUMENT_ID, letter(Map.of(), Map.of()));

        verify(valueResolverService, never()).handleValues(any(UUID.class), any());
    }

    /**
     * The submission entry point: which of a task's variables held a letter is answered by the
     * configuration, not by inspecting values for a letter-ish shape.
     */
    @Nested
    class FromSubmission {

        @Test
        @DisplayName("applies the rules of the composer whose variable the submission carried")
        void appliesForTheComposersOwnVariable() {
            declaring(Map.of("doc:/besluit/type", "$inputs.decisionType"));

            service.applyFromSubmission(DOCUMENT_ID, Map.of(
                    "epistolaLetter", letter(Map.of(), Map.of("decisionType", "gegrond"))));

            assertThat(written()).containsEntry("doc:/besluit/type", "gegrond");
        }

        @Test
        @DisplayName("two composers writing one variable save the case once, not twice")
        void writesOncePerVariableNotPerComposer() {
            // The demo's task form and its ad-hoc start form both use `pv:epistolaLetter`, and a
            // submission carries one value for it. Applying per composer saved the same values
            // twice, which is one case version per composer.
            var rules = Map.of("doc:/besluit/type", "$inputs.decisionType");
            var offered = List.of(
                    new LetterComposerConfiguration.OfferedTemplate("gemeente", "besluit", "Besluit", null));
            when(resolver.forCaseDefinition(CASE_KEY)).thenReturn(List.of(
                    new LetterComposerConfiguration("pv:epistolaLetter", null, UUID.randomUUID(),
                            "gemeente", null, offered, false, rules),
                    new LetterComposerConfiguration("pv:epistolaLetter", null, UUID.randomUUID(),
                            "gemeente", null, offered, false, rules)));

            service.applyFromSubmission(DOCUMENT_ID, Map.of(
                    "epistolaLetter", letter(Map.of(), Map.of("decisionType", "gegrond"))));

            verify(valueResolverService, org.mockito.Mockito.times(1)).handleValues(any(UUID.class), any());
        }

        @Test
        @DisplayName("a composer whose letter the submission did not carry is skipped")
        void skipsAComposerWithNoLetter() {
            // Another form on the same case type, not the one just submitted.
            declaring(Map.of("doc:/besluit/type", "$inputs.decisionType"));

            service.applyFromSubmission(DOCUMENT_ID, Map.of("ietsAnders", "x"));

            verify(valueResolverService, never()).handleValues(any(UUID.class), any());
        }

        @Test
        @DisplayName("nothing to do without a case or without variables")
        void needsBoth() {
            declaring(Map.of("doc:/besluit/type", "$inputs.decisionType"));

            service.applyFromSubmission(null, Map.of("epistolaLetter", letter(Map.of(), Map.of())));
            service.applyFromSubmission(DOCUMENT_ID, Map.of());
            service.applyFromSubmission(DOCUMENT_ID, null);

            verify(valueResolverService, never()).handleValues(any(UUID.class), any());
        }

        @Test
        @DisplayName("a composer that names no pv: key has no variable to look for")
        void ignoresAComposerWithoutAProcessVariableKey() {
            // Its letter never reaches a process variable, so no submission can carry one.
            when(resolver.forCaseDefinition(CASE_KEY)).thenReturn(List.of(new LetterComposerConfiguration(
                    "brief", null, UUID.randomUUID(), "gemeente", null,
                    List.of(new LetterComposerConfiguration.OfferedTemplate("gemeente", "besluit", "Besluit", null)),
                    false,
                    Map.of("doc:/besluit/type", "$inputs.decisionType"))));

            service.applyFromSubmission(DOCUMENT_ID, Map.of("brief", letter(Map.of(), Map.of("decisionType", "x"))));

            verify(valueResolverService, never()).handleValues(any(UUID.class), any());
        }
    }

    @Test
    @DisplayName("an explicit null is written, so a field can be deliberately cleared")
    void writesAnExplicitNull() {
        // JSONata keeps "nothing" and null apart: a missing path leaves the key out, null puts it
        // in. That is the whole mechanism — writing null needs no setting of its own, and the
        // counterpart is `yieldsNothing` above.
        declaring(Map.of("doc:/aanvrager/telefoon", "null"));

        service.apply(DOCUMENT_ID, letter(Map.of(), Map.of()));

        assertThat(written()).containsEntry("doc:/aanvrager/telefoon", null);
    }

    @Test
    @DisplayName("a rule may choose between a value and clearing the field")
    void clearsConditionally() {
        declaring(Map.of("doc:/aanvrager/telefoon", "$inputs.telefoon ? $inputs.telefoon : null"));

        service.apply(DOCUMENT_ID, letter(Map.of(), Map.of()));

        assertThat(written()).containsEntry("doc:/aanvrager/telefoon", null);
    }

    @Test
    @DisplayName("a null the letter itself carries is written, not skipped")
    void writesANullFromTheLetter() {
        // The edge this changed: a contract may declare a field nullable, and a letter that says
        // the value is empty is saying something. Previously indistinguishable from a rule that
        // found nothing.
        declaring(Map.of("doc:/aanvrager/telefoon", "$data.telefoon"));
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("telefoon", null);

        service.apply(DOCUMENT_ID, letter(data, Map.of()));

        assertThat(written()).containsEntry("doc:/aanvrager/telefoon", null);
    }

    @Test
    @DisplayName("a destination the form does not declare is not written")
    void ignoresAnUndeclaredDestination() {
        // The property this design exists for, and it now holds for a stronger reason than a veto:
        // every destination and every value comes from the stored form's rules, so a `writeBack`
        // key on the letter is not read at all. A crafted letter has nothing to craft with.
        declaring(Map.of("doc:/toegestaan", "$inputs.veld"));

        Map<String, Object> crafted = new java.util.LinkedHashMap<>(
                letter(Map.of("veld", "x"), Map.of("veld", "x")));
        crafted.put("writeBack", Map.of("doc:/stilletjes/overschreven", "kwaad"));

        service.apply(DOCUMENT_ID, crafted);

        assertThat(written()).containsOnlyKeys("doc:/toegestaan");
    }

    @Test
    @DisplayName("no composer on the case type means nothing to apply")
    void noComposers() {
        when(resolver.forCaseDefinition(CASE_KEY)).thenReturn(List.of());

        service.apply(DOCUMENT_ID, letter(Map.of("veld", "x"), Map.of()));

        verify(valueResolverService, never()).handleValues(any(UUID.class), any());
    }

    @Test
    @DisplayName("a composer that declares no rules is left alone")
    void noRules() {
        declaring(Map.of());

        service.apply(DOCUMENT_ID, letter(Map.of("veld", "x"), Map.of()));

        verify(valueResolverService, never()).handleValues(any(UUID.class), any());
    }

    @Test
    @DisplayName("an unreadable letter is skipped, not thrown")
    void unreadableLetter() {
        declaring(Map.of("doc:/x", "$inputs.veld"));

        service.apply(DOCUMENT_ID, "not a letter at all");

        verify(valueResolverService, never()).handleValues(any(UUID.class), any());
    }

    @Test
    @DisplayName("a rule that cannot be evaluated blocks the submission, and is named")
    void oneBrokenRule() {
        // It used to let the others through, which is the silent failure moving to submission time
        // was meant to end: a broken rule means a value the employee approved never arrives, and
        // nothing said so. The destination is named — the author's own text, not case data.
        declaring(Map.of(
                "doc:/goed", "$inputs.veld",
                "doc:/stuk", "this is ( not jsonata"));

        assertThatThrownBy(() -> service.apply(DOCUMENT_ID, letter(Map.of(), Map.of("veld", "waarde"))))
                .isInstanceOf(ComposerWriteBackException.class)
                .hasMessageContaining("doc:/stuk")
                .hasMessageContaining("could not be evaluated");

        verify(valueResolverService, never()).handleValues(any(UUID.class), any());
    }

    @Test
    @DisplayName("a failing write blocks the submission, naming the destination and no values")
    void failingWrite() {
        declaring(Map.of("doc:/x", "$inputs.veld"));
        org.mockito.Mockito.doThrow(new RuntimeException("case rejected 'waarde' for /x"))
                .when(valueResolverService).handleValues(any(UUID.class), any());

        assertThatThrownBy(() -> service.apply(DOCUMENT_ID, letter(Map.of(), Map.of("veld", "waarde"))))
                .isInstanceOf(ComposerWriteBackException.class)
                .hasMessageContaining("doc:/x")
                // A letter's data is case data, and the downstream complaint quotes the value it
                // rejected. So neither the message nor an attached cause carries it: the detail
                // goes to DEBUG, where reading it is a deliberate act.
                .hasMessageNotContaining("waarde")
                .hasNoCause();
    }

    @Test
    @DisplayName("when a batch fails, the destination at fault is the one named")
    void namesTheDestinationAtFault() {
        // A batch failure says only that something in the set was unacceptable. An operator needs
        // to know which one, and an author needs it to fix the rule — so each is tried alone, to
        // identify rather than to salvage: the transaction is about to roll back either way.
        declaring(Map.of("doc:/goed", "$inputs.veld", "doc:/stuk", "$inputs.veld"));
        org.mockito.Mockito.doThrow(new RuntimeException("schema says no"))
                .when(valueResolverService).handleValues(any(UUID.class), argThat(
                        (Map<String, Object> values) -> values != null && values.size() > 1));
        org.mockito.Mockito.doThrow(new RuntimeException("schema says no"))
                .when(valueResolverService).handleValues(any(UUID.class), eq(Map.of("doc:/stuk", "waarde")));

        assertThatThrownBy(() -> service.apply(DOCUMENT_ID, letter(Map.of(), Map.of("veld", "waarde"))))
                .isInstanceOf(ComposerWriteBackException.class)
                .hasMessageContaining("doc:/stuk")
                .hasMessageNotContaining("doc:/goed");
    }

    @Test
    @DisplayName("nothing is attempted without a document or a letter")
    void nothingToDo() {
        service.apply(null, letter(Map.of(), Map.of()));
        service.apply(DOCUMENT_ID, null);

        verify(valueResolverService, never()).handleValues(any(UUID.class), any());
        verify(resolver, never()).forCaseDefinition(anyString());
    }
}
