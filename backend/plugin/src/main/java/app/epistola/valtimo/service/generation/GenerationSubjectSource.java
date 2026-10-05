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
package app.epistola.valtimo.service.generation;

import com.ritense.plugin.domain.PluginProcessLink;

/**
 * Works out what a generation activity was rendering, for one kind of activity.
 *
 * <p>One implementation per <em>configuration source</em>: a process link that carries its own
 * template and mapping, or a document left on a process variable. Which one answers is decided by
 * the link's action key, so adding a source is adding an implementation rather than editing the
 * caller — which is the point of the seam (ADR 0007).
 */
public interface GenerationSubjectSource {

    /** Whether this source can answer for that plugin action. */
    boolean supports(String pluginActionDefinitionKey);

    /**
     * What that activity was rendering.
     *
     * @param link              the generation activity's process link
     * @param processInstanceId the running instance, which the data is resolved against
     * @param documentId        the case document, where one applies
     * @throws GenerationSubjectException when the activity cannot say what it was rendering
     */
    GenerationSubject resolve(PluginProcessLink link, String processInstanceId, String documentId);
}
