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
package app.epistola.valtimo.composer.web;

import app.epistola.valtimo.composer.LetterReaderService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the letter composer's <em>configurator</em> asks, as opposed to what a running form asks.
 *
 * <p>A resource of its own, in the composer's own module, rather than endpoints added to the
 * plugin-wide tooling resource. The composer is switchable with
 * {@code epistola.composer.enabled=false} and nothing else in the plugin depends on it; a core
 * resource taking a composer bean as a constructor argument would break that — the plugin would
 * fail to start with the composer switched off. This bean simply does not exist then, and the
 * endpoint 404s, which is what an author of a disabled feature should see.
 *
 * <p>Gated by {@code ROLE_ADMIN} at the HTTP layer like the other configurator endpoints, which is
 * Valtimo's de-facto "may author process links" authority.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/plugin/epistola")
@RequiredArgsConstructor
public class EpistolaComposerToolingResource {

    private final LetterReaderService letterReaderService;

    /**
     * Which generate tasks on a case type read a letter variable.
     *
     * <p>The configurator shows this beside the composer's key, so an author who renames the key
     * watches the line go empty. Deliberately a <b>statement, not a verdict</b>: an empty list means
     * none were found on this case type, not that the configuration is wrong. Generating in another
     * process is a supported design — read {@link LetterReaderService} before turning this into a
     * validation.
     *
     * @param caseDefinitionKey the case type whose processes to look through
     * @param letterVariable    the composer's key, with or without its {@code pv:} prefix
     */
    @GetMapping("/letter-readers")
    public ResponseEntity<List<LetterReaderService.LetterReader>> getLetterReaders(
            @RequestParam("caseDefinitionKey") String caseDefinitionKey,
            @RequestParam("letterVariable") String letterVariable
    ) {
        log.debug("Listing generate tasks reading '{}' on case type '{}'", letterVariable, caseDefinitionKey);
        return ResponseEntity.ok(letterReaderService.readersOf(caseDefinitionKey, letterVariable));
    }
}
