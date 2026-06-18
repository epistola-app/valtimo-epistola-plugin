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
package app.epistola.valtimo.web.rest.dto;

import java.util.List;

/**
 * Result of validating JSONata expressions for an action config.
 * {@code valid} is true iff {@code errors} is empty.
 */
public record JsonataValidationResult(boolean valid, List<FieldError> errors) {

    /**
     * A single field-level validation error.
     *
     * @param field      the logical field name (e.g. "dataMapping", "filename",
     *                   "variantAttributes.{key}")
     * @param expression the offending expression (echoed back so the frontend can match
     *                   it to the current form state)
     * @param message    the parser error message
     */
    public record FieldError(String field, String expression, String message) {}
}
