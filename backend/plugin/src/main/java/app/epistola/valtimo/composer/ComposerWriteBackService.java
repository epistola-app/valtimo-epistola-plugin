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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /**
     * Apply the write-back of every composer on this case whose letter the submission carried.
     *
     * <p>This is the entry point for <b>form submission</b>, which is when a letter's values should
     * reach the case: the same moment every other form writes, and the moment the employee who
     * typed them is still there to be told if a write fails. See
     * <a href="../../../../../../../docs/adr/0006-letter-composer-configuration.md">ADR 0006</a>
     * for why it is not the generate action any more, and what was measured to get here.
     *
     * <p>Which variables hold a letter is answered by the <b>configuration</b>, not by inspecting
     * values for a letter-ish shape: each composer on the case declares the {@code pv:} key it
     * writes, which is the variable name to look for. A composer whose letter is absent was not on
     * the form that was just submitted, and one that declares no rules costs nothing to skip.
     *
     * <p>Never throws, for the same reason the overloads below do not: a submission that has already
     * created a case and completed a task cannot be undone by failing afterwards.
     */
    public void applyFromSubmission(UUID documentId, Map<String, Object> variables) {
        if (documentId == null || variables == null || variables.isEmpty()) {
            return;
        }
        try {
            String caseDefinitionKey = caseDefinitionKeyOf(documentId);
            if (caseDefinitionKey == null) {
                return;
            }
            // By variable, not by composer: two composers on a case type may write the same one —
            // the demo's task form and its ad-hoc start form both use `pv:epistolaLetter` — and a
            // submission carries one value for it either way. Applying per composer wrote that
            // value once per composer, which is the same case saved twice. `apply` already narrows
            // to the composer that claims the variable, so one call per variable is the whole job.
            Set<String> letterVariables = new LinkedHashSet<>();
            for (LetterComposerConfiguration composer : configurationResolver.forCaseDefinition(caseDefinitionKey)) {
                String variableName = letterVariableOf(composer);
                if (variableName != null) {
                    letterVariables.add(variableName);
                }
            }
            for (String variableName : letterVariables) {
                Object letter = variables.get(variableName);
                if (letter != null) {
                    apply(documentId, letter, variableName);
                }
            }
        } catch (ComposerWriteBackException e) {
            // Already named and logged where it happened; raised so the submission rolls back.
            throw e;
        } catch (RuntimeException e) {
            // Reading the case or its forms touches a database and can fail like anything else.
            // Blocking the submission is still right: without the configuration there is no way to
            // know whether this letter had values to save.
            log.error("Letter composer write-back could not run for case {} ({})",
                    documentId, e.getClass().getSimpleName());
            log.debug("Letter composer write-back setup failure detail for case {}", documentId, e);
            throw new ComposerWriteBackException(
                    "The letter's values could not be saved on the case: its configuration could "
                            + "not be read. The submission was not completed.");
        }
    }

    /**
     * The process variable a composer writes its letter to, or null when it names none.
     *
     * <p>A composer's Formio key <em>is</em> that destination — {@code pv:epistolaLetter} — because
     * that is how the chosen letter reaches the process at all. The bare name is what the engine
     * knows it by, and what {@link #apply(UUID, Object, String)} expects.
     */
    private static String letterVariableOf(LetterComposerConfiguration composer) {
        String key = composer.componentKey();
        if (key == null || !key.startsWith("pv:")) {
            return null;
        }
        String name = key.substring("pv:".length());
        return name.isBlank() ? null : name;
    }

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
        // that is not one — including, absurdly, a letter that had already been read.
        if (submittedLetter instanceof ComposedLetter composed) {
            apply(documentId, composed, letterVariable);
            return;
        }

        ComposedLetter letter;
        try {
            // Reading the letter before looking for rules: cheap, and it keeps every path that can
            // throw inside the overload that guarantees it does not. Looking for rules first used
            // to short-circuit a letter nobody writes back, and bought a hole in that guarantee.
            letter = ComposedLetter.from(submittedLetter, "epistola:" + SUBMITTED_KEY, objectMapper);
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
    /**
     * Apply the write-back for a letter that has already been read.
     *
     * <p>Package-private on purpose. Callers hand over the raw variable value and let this service
     * read its own view of it — the generate action renders a {@link app.epistola.valtimo.domain
     * .DynamicDocument} and has no business knowing what a composer added to the same variable.
     * Exposing a {@code ComposedLetter} overload also made {@code any()} ambiguous at a call site
     * that meant the other one, which is a small thing that costs an afternoon.
     */
    void apply(UUID documentId, ComposedLetter letter, String letterVariable) {
        if (documentId == null || letter == null) {
            return;
        }

        // Failures propagate. This runs inside the submission's transaction, so a write-back that
        // cannot do its job rolls the submission back — the task stays open, the case is untouched,
        // and the employee is told. The alternative is a case worker believing the case records a
        // decision it does not. (Until 2026-10-10 this ran after Epistola had accepted the letter,
        // where throwing would have made a retry send a second one, so it swallowed everything.)
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
    }

    /**
     * Write the resolved values, or fail the submission.
     *
     * <p>In one call, because several {@code doc:} destinations then land in a single document save
     * rather than one version of the case per rule.
     *
     * <p>If that call fails, each destination is attempted on its own — not to salvage the ones that
     * work, which would be pointless when the transaction is about to roll back, but to <b>name the
     * one at fault</b>. A batch failure says only that something in the set was unacceptable; an
     * operator needs to know which destination, and an author needs it to fix the rule.
     *
     * <p>Then it throws. Write-back runs inside the submission's transaction, so this rolls the
     * submission back: the task stays open and the case is untouched. A value a case worker approved
     * either reaches the case or the submission does not happen.
     *
     * <p>Destinations are named; values never are. A letter's data is case data.
     */
    private void write(UUID documentId, Map<String, Object> resolved) {
        try {
            valueResolverService.handleValues(documentId, resolved);
            log.debug("Letter composer write-back wrote {} value(s) to case {}: {}",
                    resolved.size(), documentId, resolved.keySet());
            return;
        } catch (RuntimeException e) {
            if (resolved.size() == 1) {
                throw refusing(List.copyOf(resolved.keySet()), documentId, e);
            }
            log.warn("Letter composer write-back could not write {} value(s) to case {} in one go; "
                    + "finding which destination is at fault", resolved.size(), documentId);
        }

        List<String> failed = new ArrayList<>();
        RuntimeException firstCause = null;
        for (Map.Entry<String, Object> entry : resolved.entrySet()) {
            try {
                valueResolverService.handleValues(documentId, Map.of(entry.getKey(), entry.getValue()));
            } catch (RuntimeException e) {
                failed.add(entry.getKey());
                if (firstCause == null) {
                    firstCause = e;
                }
            }
        }
        if (!failed.isEmpty()) {
            throw refusing(failed, documentId, firstCause);
        }
        // The batch failed but every destination succeeded alone: the set was the problem, not any
        // one of them — two rules writing overlapping paths of the same document, say. Worth saying
        // out loud rather than passing silently, because nothing is wrong with the rules in
        // isolation and that is confusing to debug.
        log.warn("Letter composer write-back on case {} could not write {} together, though each "
                + "destination was accepted on its own", documentId, resolved.keySet());
    }

    /**
     * The failure to raise when a destination would not accept its value.
     *
     * <p>Names the destinations and the kind of failure, and <b>deliberately does not carry the
     * cause</b>. A letter's data is case data — names, identifiers, addresses, the text of a
     * decision — and the downstream complaint quotes the value it rejected: Valtimo's own message
     * reads {@code Failed to handle values … Values: {/besluit/x=gegrond}}. Attached as a cause,
     * that reaches every log that prints this exception's stack trace, which for a failure that
     * propagates out of a web request is all of them.
     *
     * <p>So the detail goes to {@code DEBUG}, where reading it is a deliberate act, and the
     * exception that travels carries only the destination. The destination is usually the whole
     * diagnosis anyway — a path the case schema does not declare is the common case, and it is
     * named exactly.
     */
    private static ComposerWriteBackException refusing(
            List<String> destinations, UUID documentId, RuntimeException cause) {
        String what = destinations.size() == 1
                ? "destination " + destinations.get(0)
                : "destinations " + destinations;
        log.error("Letter composer write-back could not write {} to case {} ({})",
                what, documentId, cause == null ? "unknown cause" : cause.getClass().getSimpleName());
        if (cause != null) {
            log.debug("Letter composer write-back failure detail for case {} — may quote case data",
                    documentId, cause);
        }
        return new ComposerWriteBackException(
                "The letter's values could not be saved on the case: " + what
                        + " was refused. The submission was not completed.");
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
     * <p>A rule that finds <em>nothing</em> contributes nothing, which is what makes "only write
     * what was actually supplied" the default instead of clobbering good case data. A rule that
     * fails to evaluate is dropped with a warning rather than failing the rest: one bad expression
     * should not cost the others.
     *
     * <p><b>An explicit {@code null} is a value, and it is written.</b> JSONata keeps the two
     * apart — a missing path leaves the key out of the result, while {@code null} puts it in — so
     * {@code null} is how a rule says "clear this field", and nothing else has to be configured to
     * allow it. Only the key's presence is tested here; the old guard also rejected null and so
     * collapsed the distinction, leaving no way to express a deliberate clear. Empty string and
     * {@code false} were always written, being neither missing nor null.
     *
     * <p>Whatever found nothing is named in the log. A rule that quietly resolved to nothing and
     * one that worked used to look identical from outside, which is the harder half of #179.
     */
    private Map<String, Object> resolve(Map<String, String> rules, ComposedLetter letter, UUID documentId) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        List<String> nothing = new ArrayList<>();
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
                if (result.containsKey("value")) {
                    resolved.put(destination, result.get("value"));
                } else {
                    nothing.add(destination);
                }
            } catch (RuntimeException e) {
                // A rule that cannot be evaluated is broken, and the value it was meant to save
                // never arrives. That used to be logged and skipped, which is the silent failure
                // this moved to submission time to end. The expression is named — it is the
                // author's own text, not case data — and the cause is attached rather than quoted.
                log.error("Letter composer write-back rule for '{}' on case {} could not be "
                        + "evaluated ({})", destination, documentId, e.getClass().getSimpleName());
                log.debug("Letter composer write-back rule failure detail for '{}' on case {} — "
                        + "may quote case data", destination, documentId, e);
                throw new ComposerWriteBackException(
                        "The letter's values could not be saved on the case: the rule for "
                                + destination + " could not be evaluated. The submission was not "
                                + "completed.");
            }
        });
        if (!nothing.isEmpty()) {
            // Said out loud rather than left to a reader comparing the case with the rules: a rule
            // whose expression found nothing is indistinguishable from one that worked.
            log.info("Letter composer write-back on case {}: {} resolved to nothing, so the case "
                    + "keeps what it had", documentId, nothing);
        }
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
