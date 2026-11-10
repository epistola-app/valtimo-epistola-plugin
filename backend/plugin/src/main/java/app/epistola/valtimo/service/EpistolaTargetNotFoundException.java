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

import app.epistola.valtimo.domain.GenerationTargetProblem;

import java.util.Map;

/**
 * Epistola refused a generation or preview because part of what it asks for does not exist (404
 * {@code not-found} or {@code no-matching-variant}). Permanent: asking again changes nothing until
 * the configuration or what is deployed changes.
 * <p>
 * The message is the plugin's own, built from configuration ({@link GenerationTargetProblem#describe}),
 * so it is safe to log and to put in an incident. Epistola's {@code detail} is kept apart, for the
 * case only ({@link #getEpistolaDetail}), and no cause is attached: a cause's message would carry it
 * into every log that prints the stack trace ("Never log personal data", CLAUDE.md).
 */
public class EpistolaTargetNotFoundException extends EpistolaApiException {

    private final transient GenerationTargetProblem problem;
    private final String epistolaDetail;

    public EpistolaTargetNotFoundException(GenerationTargetProblem problem, String epistolaDetail, String problemType) {
        super(problem.describe(), null, 404, problemType, Map.of());
        this.problem = problem;
        this.epistolaDetail = epistolaDetail;
    }

    public GenerationTargetProblem getProblem() {
        return problem;
    }

    /** Epistola's own explanation, to show or store on the case; never to log. May be null. */
    public String getEpistolaDetail() {
        return epistolaDetail;
    }
}
