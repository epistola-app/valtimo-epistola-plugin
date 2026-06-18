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

import java.time.Instant;
import java.util.List;

/**
 * The result of the BPMN race-safety validator, plus metadata the admin UI needs to
 * tell the operator how fresh and how complete the result is.
 *
 * <ul>
 *   <li>{@code lastCheckedAt} — when the last scan finished, or {@code null} if no scan
 *       has completed yet (e.g. just after startup, before the first tick).</li>
 *   <li>{@code refreshIntervalMs} — the fixed-delay scan cadence, so the UI can state how
 *       often the result is refreshed without hard-coding the number.</li>
 *   <li>{@code violations} — the current snapshot; empty means healthy. Only the latest
 *       deployed version of each process definition is inspected.</li>
 * </ul>
 */
public record BpmnValidationReport(
        Instant lastCheckedAt,
        long refreshIntervalMs,
        List<BpmnValidationViolation> violations
) {}
