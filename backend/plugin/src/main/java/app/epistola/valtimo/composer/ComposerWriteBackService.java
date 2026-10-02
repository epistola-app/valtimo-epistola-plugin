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

import app.epistola.valtimo.mapping.EvaluationContext;
import app.epistola.valtimo.mapping.JsonataMappingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.document.domain.impl.JsonSchemaDocumentId;
import com.ritense.document.service.DocumentService;
import com.ritense.valueresolver.ValueResolverService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Puts the values a composed letter carries into the case, where its composer says they belong.
 *
 * <p>An input the employee types is, by default, part of that letter and nothing else. Some values
 * belong in the case as well — a corrected phone number is worth keeping — so a composer may
 * declare where they go, and this applies that declaration.
 *
 * <h2>It happens when the form is submitted</h2>
 *
 * <p>Which is where every other Valtimo form field writes. A form with a {@code doc:}-keyed field
 * updates the case on submit, before any process step runs, and a composer is a field on a form:
 * behaving differently would be the surprise. ADR 0006 originally chose Epistola's acceptance of
 * the letter instead, to avoid updating a case for a letter that was never sent; that is a real
 * difference, but the ADR already conceded the same residual for its own choice ("accepted is not
 * rendered"), and a value the employee corrected is a fact about the case whether or not one
 * particular letter went out.
 *
 * <h2>Who decides what</h2>
 *
 * <p>The division is the point of this class, and it is not the one the ADR described.
 *
 * <ul>
 *   <li><b>The form definition decides where data may go, and by which rule.</b> Both the
 *       destinations and the expressions are read from the stored composer, found from the case
 *       definition the document belongs to — never from the submission.</li>
 *   <li><b>The browser decides only what the letter contains.</b> It already does: it assembles
 *       the data, which is what makes the previewed letter the generated one. Here that data is an
 *       input to the rules, not a source of them.</li>
 * </ul>
 *
 * <p>So a crafted submission can change the values a rule sees. It cannot introduce a rule, name a
 * destination, or reach a case path no composer on this case type declares — which is the
 * distinction that matters, because writing to an arbitrary {@code doc:} path is a silent edit to
 * the case rather than merely a letter with odd data.
 *
 * <h2>How the values are written</h2>
 *
 * <p>Through {@link ValueResolverService}, the same way Valtimo writes a submitted
 * {@code doc:}-keyed field. That is deliberate rather than convenient: it means {@code pv:}
 * destinations work too, because Valtimo's own process-variable resolver finds the instance by
 * business key when all it has is a document id, and it means this plugin owns no case-writing code
 * of its own.
 */
@Slf4j
@RequiredArgsConstructor
public class ComposerWriteBackService {

    /**
     * The submitted key, after the {@code epistola:} prefix the SPI strips.
     *
     * <p>Valtimo filters a submission down to the keys the form definition declares, so this key
     * reaches the plugin only because a composer put it there — the browser cannot introduce it.
     */
    public static final String SUBMITTED_KEY = "writeBack";

    private final ComposerConfigurationResolver configurationResolver;
    private final DocumentService documentService;
    private final JsonataMappingService jsonataMappingService;
    private final ValueResolverService valueResolverService;
    private final ObjectMapper objectMapper;

    /**
     * Apply every write-back rule the composers on this case type declare, against the letter that
     * was submitted.
     *
     * <p>Never throws. A submission that has already been accepted must not fail because a case
     * write could not be worked out: the employee has finished, and a half-applied correction is
     * worth more than a lost form. Everything that goes wrong is logged with enough to find it.
     *
     * @param documentId      the case document the form was submitted against
     * @param submittedLetter the composer's value, as it arrived on the submission
     */
    public void apply(UUID documentId, Object submittedLetter) {
        if (documentId == null || submittedLetter == null) {
            return;
        }

        Map<String, String> rules = rulesFor(documentId);
        if (rules.isEmpty()) {
            return;
        }

        ComposedLetter letter;
        try {
            letter = ComposedLetter.from(submittedLetter, "epistola:" + SUBMITTED_KEY, objectMapper);
        } catch (RuntimeException e) {
            log.warn("Letter composer write-back skipped for case {}: the submitted letter could not "
                    + "be read ({})", documentId, e.getMessage());
            return;
        }

        Map<String, Object> resolved = resolve(rules, letter, documentId);
        if (resolved.isEmpty()) {
            log.debug("Letter composer write-back for case {} resolved no values", documentId);
            return;
        }

        try {
            valueResolverService.handleValues(documentId, resolved);
            log.debug("Letter composer write-back wrote {} value(s) to case {}: {}",
                    resolved.size(), documentId, resolved.keySet());
        } catch (RuntimeException e) {
            log.error("Letter composer write-back could not write {} to case {}: {}",
                    resolved.keySet(), documentId, e.getMessage(), e);
        }
    }

    /**
     * Every rule declared by every composer on this case type, keyed by destination.
     *
     * <p>Two composers naming the same destination is an authoring mistake rather than something to
     * resolve: keying by destination makes one writer per case path the representable thing, and
     * the first rule found wins with a warning so the result is at least stable.
     */
    private Map<String, String> rulesFor(UUID documentId) {
        String caseDefinitionKey = caseDefinitionKeyOf(documentId);
        if (caseDefinitionKey == null) {
            return Map.of();
        }

        Map<String, String> rules = new LinkedHashMap<>();
        for (LetterComposerConfiguration configuration : configurationResolver.forCaseDefinition(caseDefinitionKey)) {
            if (configuration.writeBack() == null) {
                continue;
            }
            configuration.writeBack().forEach((destination, expression) -> {
                String existing = rules.putIfAbsent(destination, expression);
                if (existing != null && !existing.equals(expression)) {
                    log.warn("Two letter composers on case type '{}' write to '{}' with different "
                            + "rules; keeping the first", caseDefinitionKey, destination);
                }
            });
        }
        return rules;
    }

    /**
     * The value for each destination, from the rule that names it.
     *
     * <p>A rule yielding nothing contributes nothing, which is what makes "only write what was
     * actually supplied" the default instead of clobbering good case data with nulls. A rule that
     * fails to evaluate is dropped with a warning rather than failing the rest: one bad expression
     * should not cost the others.
     */
    private Map<String, Object> resolve(Map<String, String> rules, ComposedLetter letter, UUID documentId) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        rules.forEach((destination, expression) -> {
            try {
                Map<String, Object> result = jsonataMappingService.evaluate(EvaluationContext.builder()
                        .expression("{ \"value\": " + expression + " }")
                        .documentResolver(this::documentContent)
                        .documentId(documentId.toString())
                        .operation("write-back")
                        // $data is the letter as it will be sent; $inputs only what a person
                        // typed, so a rule can write back a correction without also writing back
                        // everything the mapping already knew.
                        .extraBindings(Map.of("data", letter.data(), "inputs", letter.inputs()))
                        .build());
                if (result.containsKey("value") && result.get("value") != null) {
                    resolved.put(destination, result.get("value"));
                }
            } catch (RuntimeException e) {
                log.warn("Letter composer write-back rule for '{}' on case {} could not be evaluated: {}",
                        destination, documentId, e.getMessage());
            }
        });
        return resolved;
    }

    private String caseDefinitionKeyOf(UUID documentId) {
        try {
            var document = documentService.findBy(JsonSchemaDocumentId.existingId(documentId));
            return document.map(found -> found.definitionId().name()).orElse(null);
        } catch (RuntimeException e) {
            log.debug("Could not read case {} for letter composer write-back: {}", documentId, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> documentContent(String documentId) {
        try {
            var document = documentService.findBy(JsonSchemaDocumentId.existingId(UUID.fromString(documentId)));
            return document
                    .map(found -> (Map<String, Object>) objectMapper.convertValue(found.content().asJson(), Map.class))
                    .orElse(Map.of());
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
