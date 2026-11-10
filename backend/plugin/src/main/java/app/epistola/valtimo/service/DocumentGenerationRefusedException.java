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

/**
 * A generation that cannot start: Epistola refused it (nothing it asks for exists, or the request
 * is not allowed), an expression of the link failed or produced nothing where a deliberate value is
 * needed, or the case's rich text cannot be sent. Thrown from the step, so the engine raises an
 * incident: nothing is lost, it is visible, and once the cause is fixed — the configuration or the
 * case's data — retrying the incident runs the step again with what was corrected (ADR 0009, third
 * addendum).
 * <p>
 * The message is the incident's text and reaches every log the engine writes about it, so it is
 * built from configuration only — catalog, template, release, environment, range, field and
 * expression — and never carries Epistola's own text or a value the case supplied. No cause is
 * attached, for the same reason: a cause's message would print with the stack trace.
 */
public class DocumentGenerationRefusedException extends RuntimeException {

    public DocumentGenerationRefusedException(String message) {
        super(message);
    }
}
