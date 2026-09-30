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

/**
 * The version the composer stamps on what it stores — the mirror of the backend's
 * `ComposerSchema`, which is where the rule for reading it back is written.
 *
 * Two structures carry it: a component's settings in the form definition, and the composed letter
 * on the process variable. Both outlive the code that wrote them, and the composer is alpha, so
 * the version is what makes changing their shape safe: an older plugin refuses what a newer one
 * wrote rather than half-understanding it.
 *
 * Keep in step with `ComposerSchema.CURRENT`.
 */
export const COMPOSER_SCHEMA_VERSION = 1;

/** The field both structures carry, spelled as Epistola's own catalogs spell it. */
export const COMPOSER_SCHEMA_FIELD = 'schemaVersion';
