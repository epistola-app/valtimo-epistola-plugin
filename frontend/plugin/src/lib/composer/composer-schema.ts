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
 * on the process variable. Both outlive the code that wrote them, so the version is what makes
 * changing their shape safe: an older plugin refuses what a newer one wrote rather than
 * half-understanding it, and a newer one keeps reading every older version.
 *
 * Keep in step with `ComposerSchema.CURRENT`.
 */
// 2: rich text in a letter's `data` and `inputs` is tagged with its editor's format (`tagRichText`).
// 3: a letter set names an environment and a version range, and a composed letter carries both.
// 4: the letter set's environment and ranges are JSONata (ADR 0009); see `migrateLetterSetToExpressions`.
export const COMPOSER_SCHEMA_VERSION = 4;

/** The first schema whose letter-set environment and ranges are JSONata. Mirrors `ComposerSchema`. */
export const COMPOSER_SCHEMA_EXPRESSIONS = 4;

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

/**
 * Bring a component's settings (the `epistola` namespace) written before schema 4 up to it: the
 * letter set's environment and ranges were plain values and become the JSONata string literals they
 * meant, and the version says so. Settings already at 4 or later are returned as they are.
 *
 * Done where the settings are edited, and only there. The builder writes JSONata, so a component
 * it touches must say it is at 4 — and a component it does not touch keeps its version and its
 * plain values, which the backend reads as literals (`ComposerParser`). Converting the values
 * without the version, or the version without the values, would read `"acceptatie"` as an
 * environment named with its quotes, or `acceptatie` as a path that evaluates to nothing.
 */
export function migrateLetterSetToExpressions(
  settings: Record<string, unknown> | null | undefined,
): Record<string, unknown> | null | undefined {
  if (!settings || typeof settings !== 'object') return settings;
  const declared = settings[COMPOSER_COMPONENT_SCHEMA_FIELD];
  const version = typeof declared === 'number' ? declared : 1;
  if (version >= COMPOSER_SCHEMA_EXPRESSIONS) return settings;

  const letterSet = settings['letterSet'];
  const migrated: Record<string, unknown> = {
    ...settings,
    [COMPOSER_COMPONENT_SCHEMA_FIELD]: COMPOSER_SCHEMA_EXPRESSIONS,
  };
  if (letterSet && typeof letterSet === 'object') {
    const set = letterSet as Record<string, unknown>;
    migrated['letterSet'] = {
      ...set,
      ...quoted(set, 'environmentId'),
      ...quoted(set, 'versionRange'),
      ...(Array.isArray(set['templates'])
        ? {
            templates: (set['templates'] as unknown[]).map((template) =>
              template && typeof template === 'object'
                ? {
                    ...(template as object),
                    ...quoted(template as Record<string, unknown>, 'versionRange'),
                  }
                : template,
            ),
          }
        : {}),
    };
  }
  return migrated;
}

function quoted(source: Record<string, unknown>, field: string): Record<string, unknown> {
  const value = source[field];
  return typeof value === 'string' && value.trim() ? { [field]: JSON.stringify(value.trim()) } : {};
}
