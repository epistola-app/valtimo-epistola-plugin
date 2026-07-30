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
import app.epistola.valtimo.action.generate.GenerateDocumentActionConfiguration.LiteralScalar;

import java.util.regex.Pattern;

import static com.dashjoin.jsonata.Jsonata.jsonata;

final class GenerateDocumentActionV0Handler extends AbstractGenerateDocumentActionVersionHandler {

    private static final Pattern JSONATA_MARKER = Pattern.compile("[$&({?\\[]");

    @Override
    public int version() {
        return 0;
    }

    @Override
    protected ConfiguredScalar scalar(String field, String value) {
        if (value == null || !JSONATA_MARKER.matcher(value).find()) {
            return new LiteralScalar(value);
        }
        try {
            jsonata(value);
            return new JsonataScalar(value);
        } catch (RuntimeException ignored) {
            return new LiteralScalar(value);
        }
    }
}
