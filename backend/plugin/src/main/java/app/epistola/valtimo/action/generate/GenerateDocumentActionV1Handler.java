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
package app.epistola.valtimo.action.generate;

import app.epistola.valtimo.action.generate.GenerateDocumentActionConfiguration.ConfiguredScalar;
import app.epistola.valtimo.action.generate.GenerateDocumentActionConfiguration.JsonataScalar;

import static com.dashjoin.jsonata.Jsonata.jsonata;

final class GenerateDocumentActionV1Handler extends AbstractGenerateDocumentActionVersionHandler {

    @Override
    public int version() {
        return 1;
    }

    @Override
    protected ConfiguredScalar scalar(String field, String value) {
        if (value == null || value.isBlank()) {
            return new JsonataScalar(value);
        }
        try {
            jsonata(value);
            return new JsonataScalar(value);
        } catch (RuntimeException exception) {
            throw invalid(field + " must contain valid JSONata", exception);
        }
    }

    private IllegalArgumentException invalid(String message, RuntimeException cause) {
        return new IllegalArgumentException(
                "Invalid epistola-generate-document action configuration v1: " + message, cause);
    }
}
