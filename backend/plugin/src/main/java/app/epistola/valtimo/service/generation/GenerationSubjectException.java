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

/** Raised when a generation activity cannot say what it was rendering. */
public class GenerationSubjectException extends RuntimeException {

    /** Why the subject could not be worked out, so a caller can answer with the right status. */
    public enum Reason {
        /** The activity names no template, and nothing else can supply one. */
        NO_TEMPLATE,
        /** The document a process was told to render could not be read. */
        UNREADABLE_DOCUMENT,
        /** The mapping that produces the data could not be evaluated. */
        MAPPING_FAILED
    }

    private final transient Reason reason;

    public GenerationSubjectException(Reason reason, String message) {
        this(reason, message, null);
    }

    public GenerationSubjectException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
