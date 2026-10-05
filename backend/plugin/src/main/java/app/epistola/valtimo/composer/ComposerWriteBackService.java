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

import app.epistola.valtimo.domain.DynamicDocument;
import app.epistola.valtimo.mapping.EvaluationContext;
import app.epistola.valtimo.mapping.JsonataMappingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.document.domain.impl.JsonSchemaDocumentId;
import com.ritense.document.service.DocumentService;
import com.ritense.valueresolver.ValueResolverService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        apply(documentId, submittedLetter, null);
    }

    /**
     * Apply the write-back, using the rules of the composer that wrote {@code letterVariable}.
     *
     * @param letterVariable the process variable the letter arrived on, or {@code null} when the
     *                       caller cannot say — then every composer on the case type is consulted,
     *                       which is what this did before composers could be told apart
     */
    public void apply(UUID documentId, Object submittedLetter, String letterVariable) {
        if (documentId == null || submittedLetter == null) {
            return;
        }

        // A caller that has already read the letter passes it as it is. The generate action does
        // exactly that, and handing its parsed letter to the raw-value branch is how this silently
        // wrote nothing: `from` is for a value that arrived on a submission and rejects anything
        // that is not one — including, absurdly, a DynamicDocument.
        if (submittedLetter instanceof DynamicDocument composed) {
            apply(documentId, composed, letterVariable);
            return;
        }

        DynamicDocument letter;
        try {
            // Reading the letter before looking for rules: cheap, and it keeps every path that can
            // throw inside the overload that guarantees it does not. Looking for rules first used
            // to short-circuit a letter nobody writes back, and bought a hole in that guarantee.
            letter = DynamicDocument.from(submittedLetter, "epistola:" + SUBMITTED_KEY, objectMapper);
        } catch (RuntimeException e) {
            log.warn("Letter composer write-back skipped for case {}: the submitted letter could not "
                    + "be read ({})", documentId, e.getMessage());
            return;
        }

        apply(documentId, letter, letterVariable);
    }

    /**
     * Apply the write-back for a letter that has already been read.
     *
     * <p>This is the shape the generate action has: it parsed the letter to decide what to render,
     * and the values written back must be the ones that were actually sent.
     *
     * @param documentId the case document the letter was composed for
     * @param letter     the letter as it was sent to Epistola
     */
    public void apply(UUID documentId, DynamicDocument letter) {
        apply(documentId, letter, null);
    }

    /**
     * Apply the write-back for a letter that has already been read, using the rules of the composer
     * that wrote {@code letterVariable}.
     */
    public void apply(UUID documentId, DynamicDocument letter, String letterVariable) {
        if (documentId == null || letter == null) {
            return;
        }

        // The whole method, because of what "never throws" is protecting: by the time this runs the
        // letter is with Epistola and cannot be unsent, so throwing would fail the activity and a
        // retry would send a second one. Reading the configuration touches a database and can fail
        // like anything else; a lost write-back is the cheaper failure.
        try {
            Map<String, String> rules = rulesFor(documentId, letterVariable);
            if (rules.isEmpty()) {
                return;
            }

            Map<String, Object> resolved = resolve(rules, letter, documentId);
            if (resolved.isEmpty()) {
                log.debug("Letter composer write-back for case {} resolved no values", documentId);
                return;
            }

            write(documentId, resolved);
        } catch (RuntimeException e) {
            log.error("Letter composer write-back failed for case {}: {}", documentId, e.getMessage(), e);
        }
    }

    /**
     * Write the resolved values, keeping whatever can be written.
     *
     * <p>In one call, because several {@code doc:} destinations then land in a single document
     * save rather than one version of the case per rule. But one destination this case cannot
     * accept — a path its schema does not declare, a resolver that refuses — fails that whole
     * call, and every other value is lost with it. {@link #resolve} already holds the opposite
     * principle for expressions ("one bad expression should not cost the others"); this is the
     * same principle applied to the write.
     *
     * <p>So on failure each destination is attempted on its own, which both salvages the good ones
     * and names the bad one. Re-writing a value that already landed in the failed batch is
     * harmless: writing the same value twice says the same thing.
     */
    private void write(UUID documentId, Map<String, Object> resolved) {
        try {
            valueResolverService.handleValues(documentId, resolved);
            log.debug("Letter composer write-back wrote {} value(s) to case {}: {}",
                    resolved.size(), documentId, resolved.keySet());
            return;
        } catch (RuntimeException e) {
            if (resolved.size() == 1) {
                log.error("Letter composer write-back could not write {} to case {}: {}",
                        resolved.keySet(), documentId, e.getMessage(), e);
                return;
            }
            log.warn("Letter composer write-back could not write {} value(s) to case {} in one go "
                            + "({}); trying them one at a time", resolved.size(), documentId, e.getMessage());
        }

        List<String> failed = new ArrayList<>();
        List<String> written = new ArrayList<>();
        resolved.forEach((destination, value) -> {
            try {
                valueResolverService.handleValues(documentId, Map.of(destination, value));
                written.add(destination);
            } catch (RuntimeException e) {
                failed.add(destination);
                log.error("Letter composer write-back could not write '{}' to case {}: {}",
                        destination, documentId, e.getMessage());
            }
        });
        log.info("Letter composer write-back on case {}: wrote {}, could not write {}",
                documentId, written, failed);
    }

    /**
     * Every rule declared by every composer on this case type, keyed by destination.
     *
     * <p>Two composers naming the same destination is an authoring mistake rather than something to
     * resolve: keying by destination makes one writer per case path the representable thing, and
     * the first rule found wins with a warning so the result is at least stable.
     */
    private Map<String, String> rulesFor(UUID documentId, String letterVariable) {
        String caseDefinitionKey = caseDefinitionKeyOf(documentId);
        if (caseDefinitionKey == null) {
            return Map.of();
        }

        List<LetterComposerConfiguration> composers =
                configurationResolver.forCaseDefinition(caseDefinitionKey);
        if (letterVariable != null && !letterVariable.isBlank()) {
            List<LetterComposerConfiguration> claiming = composers.stream()
                    .filter(c -> writes(c, letterVariable))
                    .toList();
            if (claiming.isEmpty()) {
                // Deliberately not falling back to every composer: applying rules written for a
                // different letter is worse than applying none, and silently doing it would be
                // very hard to see from the case afterwards.
                if (composers.stream().anyMatch(c -> c.writeBack() != null && !c.writeBack().isEmpty())) {
                    log.warn("Letter composer write-back on case type '{}' found no composer writing "
                                    + "to '{}'; the rules declared by {} are not applied",
                            caseDefinitionKey, letterVariable,
                            composers.stream().map(LetterComposerConfiguration::componentKey).toList());
                }
                return Map.of();
            }
            composers = claiming;
        }

        Map<String, String> rules = new LinkedHashMap<>();
        for (LetterComposerConfiguration configuration : composers) {
            if (configuration.writeBack() == null) {
                continue;
            }
            configuration.writeBack().forEach((destination, expression) -> {
                // Also checked when the form is parsed, where it can be reported against the
                // component that declared it. Repeated here because this is the last point before
                // a write: a destination naming no resolver fails the call, and a configuration
                // can reach this service without having come through the parser.
                if (destination == null || destination.indexOf(':') <= 0
                        || destination.indexOf(':') == destination.length() - 1) {
                    log.warn("Letter composer write-back on case type '{}' ignores destination '{}': "
                            + "it names no value resolver (expected doc:/… or pv:…)",
                            caseDefinitionKey, destination);
                    return;
                }
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
    private Map<String, Object> resolve(Map<String, String> rules, DynamicDocument letter, UUID documentId) {
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
                        //
                        // $letter is the same object as $data, under the name that reads better in
                        // a rule — "the letter's phone number" rather than "the data's". $data is
                        // kept because it is the contract's own word: Epistola's API field is
                        // `data`, and the stored letter has a `data` key, so an author reading the
                        // process variable sees that name and not this one. There is deliberately
                        // no $form alias for $inputs: a form flow's `$form` already means one
                        // step's submission, and these are the composer's own generated fields
                        // rather than the Valtimo form around them.
                        .extraBindings(Map.of(
                                "data", letter.data(),
                                "letter", letter.data(),
                                "inputs", letter.inputs()))
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

    /**
     * Whether this composer is the one that wrote that process variable.
     *
     * <p>A composer's key is the form field it occupies, which for a composer is a {@code pv:}
     * destination — that is how the letter reaches the process at all. The generate action knows
     * the variable by its bare name, so both spellings match.
     */
    private static boolean writes(LetterComposerConfiguration configuration, String letterVariable) {
        String key = configuration.componentKey();
        return key != null && (key.equals(letterVariable) || key.equals("pv:" + letterVariable));
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
