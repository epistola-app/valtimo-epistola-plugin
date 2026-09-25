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
        /** Epistola refused to render the letter with this data. */
        RENDER_FAILED
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
