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

/**
 * The version field on a **composed letter**, which lives in a process variable of its own.
 *
 * Unprefixed, because nothing shares that variable — the letter is the whole value — and because a
 * process may write one by hand, where a name nobody has to look up is worth more than a namespace
 * nothing would collide with.
 */
export const COMPOSER_SCHEMA_FIELD = 'schemaVersion';

/**
 * The one key this component claims on a Form.io component, holding every setting it owns.
 *
 * One namespace rather than a prefix per setting: that object is shared with Form.io's own
 * properties — `key`, `label`, `validate`, `prefill` — and with whatever another custom component
 * puts there, and `dataMapping` or `schemaVersion` are names anyone could reasonably claim.
 * Mirrors `ComposerSchema.COMPONENT_NAMESPACE`.
 */
export const COMPOSER_COMPONENT_NAMESPACE = 'epistola';

/** Where the version sits inside that namespace. */
export const COMPOSER_COMPONENT_SCHEMA_FIELD = 'schemaVersion';
