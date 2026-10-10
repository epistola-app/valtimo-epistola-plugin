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

/** Raised when a letter cannot be prepared or previewed, carrying why so the API can map it. */
public class ComposerException extends RuntimeException {

    public enum Reason {
        /** The task's form has no letter composer on it. */
        NO_COMPOSER,
        /** The composer on that form does not offer the requested template. */
        TEMPLATE_NOT_OFFERED,
        /** The composer is configured incompletely (no plugin configuration, catalog or mapping). */
        MISSING_CONTEXT,
        /** Two composers on the form answer to the same name, so whose settings apply is a guess. */
        AMBIGUOUS_COMPOSER,
        /** Epistola refused to render the letter with this data. */
        RENDER_FAILED,
        /** Written by a newer plugin than this one, so reading it could mean misreading it. */
        UNSUPPORTED_SCHEMA,
        /** The letter needs a value the composer cannot generate an input for. */
        UNSUPPORTED_FIELD,
        /**
         * Epistola could not describe the template: a catalog removed from the connection, a
         * template renamed, or Epistola being unreachable. Nothing the caller can fix by asking
         * differently, so it is reported like the other environment problems rather than as a
         * server error.
         */
        TEMPLATE_UNAVAILABLE,
        /**
         * The composer's own mapping could not be evaluated. The form author's mistake rather than
         * the employee's, and the message should make that plain so the right person is called.
         */
        MAPPING_FAILED
    }

    private final Reason reason;

    public ComposerException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ComposerException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
