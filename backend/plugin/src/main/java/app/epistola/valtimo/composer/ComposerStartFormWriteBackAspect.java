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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.form.web.rest.dto.FormSubmissionResult;
import com.ritense.form.web.rest.dto.FormSubmissionResultSucceeded;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;

/**
 * Applies write-back for a composer on a <b>start form</b>, which completes no task.
 *
 * <p>{@link ComposerSubmissionListener} covers everything that completes a task — a task form and a
 * form-flow step — by listening for Valtimo's own event. A start form completes nothing, so there
 * is no such event, and the events published around a process start do not yet see the letter.
 *
 * <p>So this hooks the service that handles every regular submission:
 * {@code FormSubmissionService.handleSubmission(processLinkId, formData, documentDefinitionName,
 * documentId, taskInstanceId)}. One method, dispatching to all three shapes, inside the
 * submission's transaction — which is what lets a refused write still roll the submission back.
 *
 * <p><b>The letter comes from the submission, not from the engine.</b> {@code formData} is the form
 * that was just submitted, and a composer's field is keyed {@code pv:<name>} — that prefix is what
 * makes the chosen letter a process variable in the first place. Reading it here needs no process
 * instance id, which a start form does not hand us anyway, and it is the same letter the variable
 * would have held. The <em>rules</em> still come only from the stored form.
 *
 * <p>Deliberately narrow: a submission that carries a task instance id is left to the listener.
 * Both triggers firing would write the same values twice, which is one case version per trigger.
 */
@Aspect
@Slf4j
@RequiredArgsConstructor
public class ComposerStartFormWriteBackAspect {

    private final ComposerWriteBackService writeBackService;

    /** To hand the letter over as the kind of value a process variable holds; see {@link #lettersIn}. */
    private final ObjectMapper objectMapper;

    @AfterReturning(
            pointcut = "execution(* com.ritense.form.service.FormSubmissionService.handleSubmission(..)) "
                    + "&& args(processLinkId, formData, documentDefinitionName, documentId, taskInstanceId)",
            returning = "result")
    public void afterStartFormSubmission(
            Object result,
            UUID processLinkId,
            JsonNode formData,
            String documentDefinitionName,
            String documentId,
            String taskInstanceId
    ) {
        if (taskInstanceId != null && !taskInstanceId.isBlank()) {
            return;
        }
        UUID caseId = caseIdOf(documentId, result);
        if (caseId == null) {
            return;
        }
        Map<String, Object> letters = lettersIn(formData);
        if (letters.isEmpty()) {
            return;
        }
        writeBackService.applyFromSubmission(caseId, letters);
    }

    /**
     * The case the submission landed on.
     *
     * <p>A start form against an open dossier names it; one that <em>creates</em> a case cannot, so
     * the new id is taken from the result. A submission the service refused carries no id and is
     * skipped — there is no case to write to, and nothing was started.
     */
    private static UUID caseIdOf(String documentId, Object result) {
        String id = documentId;
        if ((id == null || id.isBlank()) && result instanceof FormSubmissionResultSucceeded succeeded) {
            id = succeeded.documentId();
        }
        if (id == null || id.isBlank() || !(result instanceof FormSubmissionResult)) {
            return null;
        }
        if (!(result instanceof FormSubmissionResultSucceeded)) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Whatever the submission put on a {@code pv:} key, by the name the engine would know it under.
     *
     * <p>Not "whatever looks like a letter": the composers on this case decide which of these
     * matter, and {@link ComposerWriteBackService#applyFromSubmission} asks them. Anything else on
     * a {@code pv:} key simply has no rules and costs a lookup.
     *
     * <p><b>Converted to a {@code Map}</b>, which is the kind of value a process variable holds and
     * the only thing {@code DynamicDocument.allFrom} accepts besides a JSON string. Handing over the
     * submission's {@code JsonNode} instead failed with "the composed letter could not be read" —
     * and, because a failure now blocks, that broke the submission rather than passing quietly.
     * Converting here keeps the domain's contract narrow and puts the adaptation at the boundary the
     * {@code JsonNode} comes from.
     *
     * <p>A {@code pv:} field that is not an object cannot be a letter, so it is left out rather than
     * converted into a failure.
     */
    private Map<String, Object> lettersIn(JsonNode formData) {
        if (formData == null || !formData.isObject()) {
            return Map.of();
        }
        Map<String, Object> byVariableName = new LinkedHashMap<>();
        formData.fieldNames().forEachRemaining(field -> {
            if (!field.startsWith("pv:") || field.length() <= "pv:".length()) {
                return;
            }
            JsonNode value = formData.get(field);
            if (value == null || !value.isObject()) {
                return;
            }
            byVariableName.put(field.substring("pv:".length()),
                    objectMapper.convertValue(value, Map.class));
        });
        return byVariableName;
    }
}
