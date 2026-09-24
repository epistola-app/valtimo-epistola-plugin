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

/**
 * Version 2 of the generate-document configuration: identical to v1, except that the catalog and
 * the template are JSONata expressions rather than fixed ids.
 *
 * <p>This is what lets one service task generate whichever letter an employee chose. A letter
 * composer stores {@code {templateId, catalogId, data}} on a process variable, and the link reads
 * it back with {@code $pv.epistolaLetter.templateId} and friends, instead of a process needing one
 * service task per letter.
 *
 * <p>A literal id still works, written as a JSONata string: {@code "besluit-bezwaar"}. Bare words
 * do not, because JSONata reads them as a path — which is exactly why v0 and v1 configurations
 * keep taking their catalog and template literally rather than being reinterpreted here.
 */
final class GenerateDocumentActionV2Parser extends GenerateDocumentActionV1Parser {

    @Override
    public int version() {
        return 2;
    }

    @Override
    protected ConfiguredScalar templateReference(String field, String value) {
        return scalar(field, value);
    }
}
