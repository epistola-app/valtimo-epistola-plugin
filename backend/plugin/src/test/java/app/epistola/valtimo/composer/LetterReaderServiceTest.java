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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.processlink.service.ProcessLinkService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;

/**
 * The configurator's question: which generate tasks read this letter?
 *
 * <p>Every case here is a <em>statement</em> about configuration, never a verdict — an empty answer
 * means none were found on this case type, which is a thing an author should see and decide about.
 * See {@link LetterReaderService} for why this is not a validation.
 */
class LetterReaderServiceTest {

    private static final String CASE_KEY = "correspondentie";
    private static final String PROCESS_ID = "correspondentie-letter-composer:1:abc";

    private final ComposerConfigurationResolver resolver = mock(ComposerConfigurationResolver.class);
    private final ProcessLinkService processLinkService = mock(ProcessLinkService.class);
    private final RepositoryService repositoryService = mock(RepositoryService.class);

    private LetterReaderService service;

    @BeforeEach
    void setUp() {
        service = new LetterReaderService(resolver, processLinkService, repositoryService);
        when(resolver.processesOf(CASE_KEY)).thenReturn(List.of(PROCESS_ID));

        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getKey()).thenReturn("correspondentie-letter-composer");
        when(definition.getName()).thenReturn("Brief kiezen en genereren");
        when(repositoryService.getProcessDefinition(anyString())).thenReturn(definition);
        when(processLinkService.getProcessLinks(PROCESS_ID)).thenReturn(List.of());
    }

    @Test
    @DisplayName("names the generate task that reads the letter, as the author sees it")
    void findsTheReader() {
        givenGeneratingLink("generate-chosen-letter", "epistolaLetter");

        assertThat(service.readersOf(CASE_KEY, "pv:epistolaLetter"))
                .containsExactly(new LetterReaderService.LetterReader(
                        "correspondentie-letter-composer", "Brief kiezen en genereren", "generate-chosen-letter"));
    }

    @Test
    @DisplayName("takes the key with or without its pv: prefix, because that is how it is written")
    void acceptsEitherForm() {
        givenGeneratingLink("generate-chosen-letter", "epistolaLetter");

        assertThat(service.readersOf(CASE_KEY, "epistolaLetter")).hasSize(1);
        assertThat(service.readersOf(CASE_KEY, "pv:epistolaLetter")).hasSize(1);
    }

    @Test
    @DisplayName("a generate task that names no variable still reads the default one")
    void appliesTheActionsOwnDefault() {
        // An author who left the field empty is reading `epistolaLetter` and expects to see it here;
        // anything else would report a working configuration as unread.
        givenGeneratingLink("generate-chosen-letter", null);

        assertThat(service.readersOf(CASE_KEY, "pv:epistolaLetter")).hasSize(1);
        assertThat(service.readersOf(CASE_KEY, "pv:epistolaAdhocLetter")).isEmpty();
    }

    @Test
    @DisplayName("says nothing was found when no generate task reads this key")
    void findsNothing() {
        // The case the hint exists for: a renamed composer, or a second composer on a form whose
        // own generate task was never configured. A statement, not a verdict.
        givenGeneratingLink("generate-chosen-letter", "epistolaLetter");

        assertThat(service.readersOf(CASE_KEY, "pv:epistolaDirectLetter")).isEmpty();
    }

    @Test
    @DisplayName("ignores links that are not generate tasks")
    void ignoresOtherActions() {
        PluginProcessLink downloading = link("download-it", "download-document", "epistolaLetter");
        when(processLinkService.getProcessLinks(PROCESS_ID)).thenReturn(List.of(downloading));

        assertThat(service.readersOf(CASE_KEY, "pv:epistolaLetter")).isEmpty();
    }

    @Test
    @DisplayName("nothing to look through without a case type or a key")
    void needsBoth() {
        assertThat(service.readersOf(null, "pv:epistolaLetter")).isEmpty();
        assertThat(service.readersOf(CASE_KEY, null)).isEmpty();
        assertThat(service.readersOf(CASE_KEY, "  ")).isEmpty();
        assertThat(service.readersOf(CASE_KEY, "pv:")).isEmpty();
    }

    @Test
    @DisplayName("an unreadable process or link answers nothing instead of failing the screen")
    void survivesAnUnreadableEngine() {
        // This runs behind an authoring screen. A broken lookup should leave the hint blank, not
        // take the configurator down with it.
        when(processLinkService.getProcessLinks(anyString())).thenThrow(new IllegalStateException("gone"));

        assertThat(service.readersOf(CASE_KEY, "pv:epistolaLetter")).isEmpty();
    }

    private void givenGeneratingLink(String activityId, String letterVariable) {
        // Built before the stubbing, not inside it: a mock() or when() evaluated as an argument to
        // an outer when() is an UnfinishedStubbingException.
        PluginProcessLink generating = link(activityId, "epistola-generate-dynamic-document", letterVariable);
        when(processLinkService.getProcessLinks(PROCESS_ID)).thenReturn(List.of(generating));
    }

    private static PluginProcessLink link(String activityId, String actionKey, String letterVariable) {
        PluginProcessLink link = mock(PluginProcessLink.class);
        when(link.getActivityId()).thenReturn(activityId);
        when(link.getPluginActionDefinitionKey()).thenReturn(actionKey);
        ObjectNode properties = new ObjectMapper().createObjectNode();
        if (letterVariable != null) {
            properties.put("letterVariable", letterVariable);
        }
        when(link.getActionProperties()).thenReturn(properties);
        return link;
    }
}
