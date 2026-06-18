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
package app.epistola.valtimo.domain;

/**
 * Where the {@code download-document} action materializes the downloaded PDF.
 *
 * <p>See {@code docs/adr/0001-download-document-content-storage.md}. A download action always
 * materializes the bytes somewhere; this enum selects where. None of the targets is a hard
 * dependency — each only touches the backend it needs.
 */
public enum DocumentStorageTarget {

    /**
     * Store the PDF in Valtimo's temporary resource storage and put the resulting resource id in
     * the content variable. The id is small (no task-response leak, no {@code varchar(4000)} limit)
     * and is the bridge to the Documenten API via {@code documenten-api:store-temp-document}.
     * Requires the {@code temporary-resource-storage} module (ubiquitous in Valtimo). Default.
     */
    TEMPORARY_RESOURCE,

    /**
     * Store the raw PDF bytes inline as a process variable ({@code byte[]}).
     *
     * <p>Supported, but not the default. Suitable for <em>small, non-sensitive</em> documents where
     * having the bytes directly on the process is convenient. Caveats: the bytes are serialized into
     * the task-detail HTTP response (Valtimo serializes the full variable map) and persist in process
     * state, so prefer {@link #TEMPORARY_RESOURCE} for large or private documents. (A {@code byte[]}
     * is used rather than a Base64 String to avoid Operaton's {@code varchar(4000)} variable limit.)
     */
    PROCESS_VARIABLE
}
