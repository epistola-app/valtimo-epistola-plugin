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
 * Status of a document generation job in Epistola.
 */
public enum GenerationJobStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED;

    /**
     * Read a status as Epistola sends it. Contract 2.0 sends lowercase kebab-case
     * ({@code in-progress}); 1.x sent the constant's name ({@code IN_PROGRESS}). Both are read.
     *
     * @throws IllegalArgumentException for a status this plugin does not know
     */
    public static GenerationJobStatus fromApi(String status) {
        return valueOf(normalize(status));
    }

    /**
     * The status as the plugin writes it onto process variables: always the 1.x form
     * ({@code COMPLETED}), whatever the server sent, so the BPMN gateways installers have written
     * against it keep matching. An unknown status is normalised the same way rather than refused.
     */
    public static String normalize(String status) {
        if (status == null) {
            return null;
        }
        return status.trim().replace('-', '_').toUpperCase(java.util.Locale.ROOT);
    }
}
