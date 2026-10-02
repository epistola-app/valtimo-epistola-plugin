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

import static org.assertj.core.api.Assertions.assertThat;

import app.epistola.valtimo.service.preview.PreviewService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reading the fields Epistola named out of a refused preview.
 *
 * <p>The behaviour worth pinning is what is <i>not</i> reported as much as what is: an absent
 * optional field is not a failure, a server that says nothing field-by-field must not produce
 * phantom findings, and a malformed problem body must not become a confident claim about a field.
 */
class TemplateDataFindingsTest {

    private static EpistolaApiException problem(String typeSlug, Map<String, Object> extensions) {
        return new EpistolaApiException(
                "Template data invalid",
                new RuntimeException("downstream"),
                400,
                "https://epistola.app/errors/" + typeSlug,
                extensions);
    }

    /** As the preview path wraps it: PreviewException around the API exception. */
    private static Throwable wrapped(EpistolaApiException cause) {
        return new PreviewService.PreviewException(
                PreviewService.PreviewException.Reason.RENDER_FAILED, cause.getMessage(), cause);
    }

    @Test
    @DisplayName("an invalid value is reported at its pointer, with the keyword that failed")
    void invalidFields() {
        var failure = wrapped(problem("template-data-invalid", Map.of(
                "invalidFields", List.of(
                        Map.of("path", "/customer/email", "keyword", "format",
                                "message", "must be a valid email address"),
                        Map.of("path", "/lineItems/0/quantity", "keyword", "minimum",
                                "message", "must be at least 1")))));

        assertThat(TemplateDataFindings.of(failure))
                .containsExactly(
                        new TemplateDataFindings("/customer/email", "format", "must be a valid email address"),
                        new TemplateDataFindings("/lineItems/0/quantity", "minimum", "must be at least 1"));
    }

    @Test
    @DisplayName("a required field that is absent is a finding; an optional one is not")
    void missingFields() {
        // Epistola lists absent optional fields too, so a client can offer them. Reporting one as
        // an error would complain about something nobody requires.
        var failure = wrapped(problem("template-data-invalid", Map.of(
                "missingFields", List.of(
                        Map.of("path", "/invoiceNumber", "required", true),
                        Map.of("path", "/customer/phone", "required", false)))));

        assertThat(TemplateDataFindings.of(failure))
                .containsExactly(new TemplateDataFindings("/invoiceNumber", "required", null));
    }

    @Test
    @DisplayName("an absent field carries no message, because Epistola reports a location not a sentence")
    void missingFieldHasNoMessage() {
        var failure = wrapped(problem("template-data-invalid", Map.of(
                "missingFields", List.of(Map.of("path", "/invoiceNumber", "required", true)))));

        assertThat(TemplateDataFindings.of(failure))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.keyword()).isEqualTo("required");
                    // The wording a person reads is chosen in the browser, with the rest of the
                    // composer's messages.
                    assertThat(finding.message()).isNull();
                });
    }

    @Test
    @DisplayName("a field with no required flag is treated as required")
    void missingFieldWithoutFlag() {
        // The contract makes `required` mandatory, so this is defensive: an entry that omits it is
        // more usefully reported than dropped.
        var failure = wrapped(problem("template-data-invalid", Map.of(
                "missingFields", List.of(Map.of("path", "/invoiceNumber")))));

        assertThat(TemplateDataFindings.of(failure)).hasSize(1);
    }

    @Nested
    @DisplayName("says nothing when there is nothing to say")
    class Empty {

        @Test
        @DisplayName("another problem type carries no field findings")
        void anotherProblem() {
            var failure = wrapped(problem("validation-error", Map.of(
                    "invalidFields", List.of(Map.of("path", "/customer/email", "keyword", "format")))));

            // Only `template-data-invalid` describes template data. Reading these members off any
            // problem that happened to carry them would attach another endpoint's complaint to a
            // letter's fields.
            assertThat(TemplateDataFindings.of(failure)).isEmpty();
        }

        @Test
        @DisplayName("a server older than contract 1.4.0 sends no members at all")
        void olderServer() {
            var failure = wrapped(problem("template-data-invalid", Map.of()));

            assertThat(TemplateDataFindings.of(failure)).isEmpty();
        }

        @Test
        @DisplayName("a failure with no Epistola problem underneath")
        void noProblem() {
            var failure = new PreviewService.PreviewException(
                    PreviewService.PreviewException.Reason.RENDER_FAILED, "connection refused");

            assertThat(TemplateDataFindings.of(failure)).isEmpty();
        }

        @Test
        @DisplayName("a null failure")
        void nullFailure() {
            assertThat(TemplateDataFindings.of(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("a malformed body yields no findings rather than a wrong one")
    class Malformed {

        @Test
        @DisplayName("a member that is not an array")
        void notAnArray() {
            var failure = wrapped(problem("template-data-invalid", Map.of(
                    "invalidFields", "not an array",
                    "missingFields", 42)));

            assertThat(TemplateDataFindings.of(failure)).isEmpty();
        }

        @Test
        @DisplayName("an entry that is not an object, or has no path")
        void unusableEntries() {
            var entries = new ArrayList<Object>();
            entries.add("just a string");
            entries.add(Map.of("keyword", "format"));
            entries.add(Map.of("path", "   "));
            entries.add(Map.of("path", "/customer/email", "keyword", "format"));

            var failure = wrapped(problem("template-data-invalid", Map.of("invalidFields", entries)));

            // The one usable entry survives; nothing is invented for the rest.
            assertThat(TemplateDataFindings.of(failure))
                    .containsExactly(new TemplateDataFindings("/customer/email", "format", null));
        }
    }

    @Test
    @DisplayName("the problem is found however deeply it is wrapped")
    void deeplyWrapped() {
        var failure = new IllegalStateException("outer",
                wrapped(problem("template-data-invalid", Map.of(
                        "missingFields", List.of(Map.of("path", "/invoiceNumber", "required", true))))));

        assertThat(TemplateDataFindings.of(failure)).hasSize(1);
    }

    @Test
    @DisplayName("a self-referencing cause chain terminates")
    void selfReferencingCause() {
        // Defensive: a chain that points at itself would otherwise spin forever.
        var looping = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(TemplateDataFindings.of(looping)).isEmpty();
    }
}
