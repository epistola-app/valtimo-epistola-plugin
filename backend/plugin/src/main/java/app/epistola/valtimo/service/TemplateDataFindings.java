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
package app.epistola.valtimo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Which fields Epistola refused, read off a failed preview.
 *
 * <p>A generated input form checks what the contract says about the fields it offered, in the
 * browser, as the employee types. It cannot check the rest: most of a letter's data comes from the
 * baseline mapping, and the browser neither computed that nor knows what the template requires of
 * it. So a letter can be refused for a field nobody was asked about, and until now that arrived as
 * one sentence above the preview — "Voorbeeld kon niet worden gegenereerd" — with the detail
 * flattened into a string.
 *
 * <p>Contract <b>1.4.0</b> made that answerable. A refused preview comes back as
 * {@code template-data-invalid} carrying {@code invalidFields} and {@code missingFields}, each
 * entry a <b>JSON Pointer into the data</b> plus that field's schema, so the complaint can be put
 * under the field it is about instead of above the letter.
 *
 * <p>This reads them out of the problem's extension members, which the contract client already
 * parses and {@link EpistolaApiException} already carries. Nothing is interpreted: a finding is
 * passed on as the pointer, the keyword and whatever Epistola said about it, and the wording a
 * person reads is chosen in the browser, where the rest of the composer's messages live.
 *
 * @param path    JSON Pointer (RFC 6901) into the request's {@code data}, e.g. {@code /customer/email}
 * @param keyword The JSON Schema keyword that failed, or {@code required} for an absent field
 * @param message What Epistola said, when it said anything; null for an absent field, which it
 *                reports as a location rather than a sentence
 */
public record TemplateDataFindings(String path, String keyword, String message) {

    /** The problem type that describes template data field by field. */
    private static final String TEMPLATE_DATA_INVALID = "template-data-invalid";

    private static final String MISSING_FIELDS = "missingFields";
    private static final String INVALID_FIELDS = "invalidFields";

    /**
     * The findings a failure carries, or empty when it carries none.
     *
     * <p>Walks the cause chain rather than taking the top exception: a preview failure is wrapped
     * by {@code PreviewService} and may be wrapped again, and the problem detail lives on the
     * {@link EpistolaApiException} underneath. Empty is the ordinary answer — a preview can fail
     * for reasons that have nothing to do with the data, and a server older than contract 1.4.0
     * sends no such members at all.
     */
    public static List<TemplateDataFindings> of(Throwable failure) {
        EpistolaApiException problem = problemIn(failure);
        if (problem == null || !TEMPLATE_DATA_INVALID.equals(problem.getProblemTypeSlug())) {
            return List.of();
        }

        Map<String, Object> extensions = problem.getProblemExtensions();
        if (extensions == null || extensions.isEmpty()) {
            return List.of();
        }

        List<TemplateDataFindings> findings = new ArrayList<>();
        for (Map<String, Object> field : members(extensions.get(INVALID_FIELDS))) {
            String path = text(field.get("path"));
            if (path != null) {
                findings.add(new TemplateDataFindings(path, text(field.get("keyword")), text(field.get("message"))));
            }
        }
        for (Map<String, Object> field : members(extensions.get(MISSING_FIELDS))) {
            String path = text(field.get("path"));
            // An absent *optional* field is not a failure — the contract says so, and Epistola
            // lists those too so a client can offer them. Reporting one as an error would complain
            // about something nobody requires.
            if (path != null && !Boolean.FALSE.equals(field.get("required"))) {
                findings.add(new TemplateDataFindings(path, "required", null));
            }
        }
        return List.copyOf(findings);
    }

    /** The first {@link EpistolaApiException} in the cause chain, or null when there is none. */
    private static EpistolaApiException problemIn(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof EpistolaApiException problem) {
                return problem;
            }
            if (cause.getCause() == cause) {
                return null;
            }
        }
        return null;
    }

    /**
     * An extension member's array of objects, as the client's problem parser left it.
     *
     * <p>Anything of an unexpected shape is skipped rather than guessed at: a malformed problem
     * body should not become a confident claim about a particular field, and it must not hide the
     * failure it decorates.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> members(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> members = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                members.add((Map<String, Object>) map);
            }
        }
        return members;
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }
}
