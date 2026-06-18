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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Detailed information about a document generation job.
 */
@Value
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class GenerationJobDetail {

    /**
     * The unique request/job ID.
     */
    String requestId;

    /**
     * Current status of the job.
     */
    GenerationJobStatus status;

    /**
     * The generated document ID (available when status is COMPLETED).
     */
    String documentId;

    /**
     * Error message (available when status is FAILED).
     */
    String errorMessage;

    /**
     * Timestamp when the job was created.
     */
    Instant createdAt;

    /**
     * Timestamp when the job was completed (or failed/cancelled).
     */
    Instant completedAt;
}
