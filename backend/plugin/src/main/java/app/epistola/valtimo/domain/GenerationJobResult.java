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

import lombok.Builder;
import lombok.Value;

/**
 * Result of submitting a document generation job to Epistola.
 */
@Value
@Builder
public class GenerationJobResult {

    /**
     * Unique identifier of the generation request (job) in Epistola.
     * This is NOT the document ID — the document ID is only available
     * after the job completes.
     */
    String requestId;

    /**
     * Current status of the generation job (e.g. PENDING, IN_PROGRESS, COMPLETED, FAILED).
     */
    String status;
}
