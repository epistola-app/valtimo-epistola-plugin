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

/**
 * A letter's values could not be written to the case, so the submission must not stand.
 *
 * <p>Write-back runs inside the submission's transaction, which means throwing rolls the whole
 * submission back — the task stays open, the case is untouched, and the employee is told. That is
 * the point: a value a case worker approved either reaches the case or the submission does not
 * happen. Silently dropping it would leave them believing the case records something it does not.
 *
 * <p>The message names <b>destinations</b> and never values. A letter's data is case data — names,
 * identifiers, addresses, the text of a decision — and this exception is logged and may surface in
 * an error response.
 */
public class ComposerWriteBackException extends RuntimeException {

    public ComposerWriteBackException(String message) {
        super(message);
    }

    public ComposerWriteBackException(String message, Throwable cause) {
        super(message, cause);
    }
}
