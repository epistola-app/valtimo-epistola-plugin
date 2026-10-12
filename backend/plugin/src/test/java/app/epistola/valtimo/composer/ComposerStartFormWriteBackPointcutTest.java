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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.form.service.FormSubmissionService;
import com.ritense.form.web.rest.dto.FormSubmissionResult;
import com.ritense.form.web.rest.dto.FormSubmissionResultSucceeded;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Would we notice if Valtimo changed the method this aspect hooks?
 *
 * <p>{@link ComposerStartFormWriteBackAspectTest} calls the advice as a plain method, which says
 * nothing about whether it is ever <em>reached</em>. The pointcut is a string: no compiler looks at
 * it, so a renamed method, an added parameter or two swapped parameters would stop it matching and
 * write-back would silently stop happening on start forms — the exact silence this whole mechanism
 * exists to end. The browser suites would notice, and they run nightly rather than on a pull
 * request, which is a blind spot that once hid a broken test for five days.
 *
 * <p>Two halves, and they catch different things:
 *
 * <ul>
 *   <li>{@link StubSubmissionService} <b>implements</b> {@code FormSubmissionService}, so the
 *       signature is compile-checked. A parameter added, removed or retyped upstream fails the build
 *       here rather than going quiet in production.</li>
 *   <li>The call goes through the Spring proxy, so the pointcut and its {@code args(…)} binding are
 *       exercised for real. A rename, a reordering, or a sixth parameter shows up as this test
 *       failing.</li>
 * </ul>
 */
class ComposerStartFormWriteBackPointcutTest {

    private static final UUID CASE = UUID.randomUUID();
    private static final UUID PROCESS_LINK = UUID.randomUUID();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProxiedSubmissionConfiguration.class);

    @Test
    void theAdviceIsReachedThroughTheProxy() {
        runner.run(context -> {
            ComposerWriteBackService writeBack = context.getBean(ComposerWriteBackService.class);
            FormSubmissionService submissions = context.getBean(FormSubmissionService.class);

            // A start form on an open dossier: a case, no task, one letter on a pv: key.
            JsonNode formData = new ObjectMapper().readTree(
                    "{\"pv:epistolaLetter\": {\"schemaVersion\": 1, \"catalogId\": \"gemeente\","
                            + " \"templateId\": \"besluit\", \"data\": {}, \"inputs\": {}}}");

            submissions.handleSubmission(
                    PROCESS_LINK, formData, "correspondentie", CASE.toString(), null);

            verify(writeBack).applyFromSubmission(eq(CASE), any(), any());
        });
    }

    @Test
    void theStubIsActuallyProxied() {
        // Otherwise the test above could pass for the wrong reason: if nothing were proxied, the
        // advice would not run and the verification would fail — but if the bean were not the stub
        // either, this test would be asserting about something else entirely.
        runner.run(context -> assertThat(context.getBean(FormSubmissionService.class))
                .isNotInstanceOf(StubSubmissionService.class));
    }

    @Configuration
    @EnableAspectJAutoProxy
    static class ProxiedSubmissionConfiguration {

        @Bean
        ComposerWriteBackService writeBackService() {
            return mock(ComposerWriteBackService.class);
        }

        @Bean
        ComposerStartFormWriteBackAspect aspect(ComposerWriteBackService writeBackService) {
            return new ComposerStartFormWriteBackAspect(writeBackService, mock(ComposerConfigurationResolver.class), new ObjectMapper());
        }

        @Bean
        FormSubmissionService submissionService() {
            return new StubSubmissionService();
        }
    }

    /**
     * Stands in for Valtimo's own service. Implementing the interface is the point: the signature
     * below has to keep matching theirs or this file stops compiling.
     */
    static class StubSubmissionService implements FormSubmissionService {

        @Override
        public FormSubmissionResult handleSubmission(
                UUID processLinkId,
                JsonNode formData,
                String documentDefinitionName,
                String documentId,
                String taskInstanceId
        ) {
            return new FormSubmissionResultSucceeded(
                    documentId == null ? CASE.toString() : documentId);
        }
    }
}
