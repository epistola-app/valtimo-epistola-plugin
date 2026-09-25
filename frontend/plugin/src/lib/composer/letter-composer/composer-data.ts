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
 * The two halves of a composed letter's data, and how they are put together.
 *
 * The backend resolves what the case knows (the baseline mapping) and asks the employee only for
 * what is left over. What comes back from the generated form therefore has to be laid over the
 * mapped data rather than replacing it, and it has to happen the same way the backend merges —
 * see `MapMerge` — or the preview would show something the generated letter does not.
 */
export type ComposerData = Record<string, any>;

/**
 * Deep-merge `overlay` onto `base`. Nested objects merge key by key; every other value, arrays
 * included, is replaced whole, because a partial array merge has no meaningful reading for
 * template data.
 */
export function mergeComposerData(base: ComposerData, overlay: ComposerData): ComposerData {
  const result: ComposerData = { ...(base ?? {}) };
  for (const [key, value] of Object.entries(overlay ?? {})) {
    const existing = result[key];
    const bothPlainObjects = isPlainObject(existing) && isPlainObject(value);
    result[key] = bothPlainObjects ? mergeComposerData(existing, value) : value;
  }
  return result;
}

/**
 * Drop what the employee left empty, so an untouched input never overwrites a value the mapping
 * did produce. Formio hands back `''` for a cleared text field and `null` for an unset select.
 */
export function pruneEmpty(data: ComposerData): ComposerData {
  const result: ComposerData = {};
  for (const [key, value] of Object.entries(data ?? {})) {
    if (value === null || value === undefined || value === '') {
      continue;
    }
    if (isPlainObject(value)) {
      const nested = pruneEmpty(value);
      if (Object.keys(nested).length > 0) {
        result[key] = nested;
      }
      continue;
    }
    result[key] = value;
  }
  return result;
}

function isPlainObject(value: unknown): value is Record<string, any> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

/**
 * The dotted keys of the required inputs in a generated form.
 *
 * Previewing a letter whose required fields are still empty is not a preview: Epistola refuses to
 * render it, and the employee is shown a validation error for fields they were just asked to fill.
 * Knowing which keys those are is what lets the composer wait instead.
 */
export function requiredKeys(formDefinition: any): string[] {
  const keys: string[] = [];
  const walk = (components: any[]) => {
    for (const component of components ?? []) {
      if (component?.validate?.required && component?.key) {
        keys.push(component.key);
      }
      walk(component?.components);
    }
  };
  walk(formDefinition?.components);
  return keys;
}

/** Whether `data` holds a usable value at every one of those keys. */
export function hasValuesFor(data: ComposerData, keys: string[]): boolean {
  return keys.every((key) => {
    const value = key
      .split('.')
      .reduce<any>((node, segment) => (node == null ? undefined : node[segment]), data);
    return value !== undefined && value !== null && value !== '';
  });
}
