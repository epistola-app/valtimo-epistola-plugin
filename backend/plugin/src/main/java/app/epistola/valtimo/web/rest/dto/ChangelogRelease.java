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
 * One release block parsed from the bundled CHANGELOG.md, in file order
 * (newest first). {@code version} is the bracketed heading (e.g. "Unreleased"
 * or "0.8.0"); {@code date} is the ISO date after it, or {@code null} when none
 * (e.g. the Unreleased block).
 */
public record ChangelogRelease(
        String version,
        String date,
        List<ChangelogSection> sections
) {}
