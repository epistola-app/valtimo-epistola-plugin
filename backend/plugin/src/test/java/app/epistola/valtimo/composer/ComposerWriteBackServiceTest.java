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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    @DisplayName("a destination the form does not declare is not written")
    void ignoresAnUndeclaredDestination() {
        // The property this design exists for. The letter is assembled in the browser, so a
        // crafted one can name any path it likes; the form stays the authority on where data goes.
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
    @DisplayName("one broken rule does not cost the others")
    void oneBrokenRule() {
        declaring(Map.of(
                "doc:/goed", "$inputs.veld",
                "doc:/stuk", "this is ( not jsonata"));

        service.apply(DOCUMENT_ID, letter(Map.of(), Map.of("veld", "waarde")));

        assertThat(written()).containsExactly(Map.entry("doc:/goed", "waarde"));
    }

    @Test
    @DisplayName("a failing write is logged, never thrown")
    void failingWrite() {
        // The submission has already succeeded by the time this runs. Throwing would lose a form
        // the employee has finished, to save a correction.
        declaring(Map.of("doc:/x", "$inputs.veld"));
        org.mockito.Mockito.doThrow(new RuntimeException("database is on fire"))
                .when(valueResolverService).handleValues(any(UUID.class), any());

        service.apply(DOCUMENT_ID, letter(Map.of(), Map.of("veld", "waarde")));
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
