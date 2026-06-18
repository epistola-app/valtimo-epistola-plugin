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

import * as _jsonata from 'jsonata';
import {
  isLegacyOverrideMapping,
  legacyOverrideToJsonata,
} from '../override-builder/legacy-override-converter';

const jsonata = (_jsonata as any).default || _jsonata;

/** Re-exported for backward compatibility with existing imports/tests. */
export { FORM_REF_PREFIX } from '../override-builder/legacy-override-converter';

/**
 * An override mapping is either the new JSONata expression **string** (over
 * `$form`) or — for not-yet-re-saved forms — the legacy `form:`-ref **object**.
 */
export type OverrideMappingValue = string | Record<string, any> | null | undefined;

/**
 * Detect if a string value is a JSONata expression (vs a plain literal).
 * Checks for characters that indicate JSONata operators: $, &, (, {, ?, [
 */
export function isExpression(value: string): boolean {
  return /[$&({?\[]/.test(value);
}

/**
 * Expand dot-notation keys into nested objects.
 * e.g. { "beslissing.tekst": "value" } -> { beslissing: { tekst: "value" } }
 */
export function expandDotNotation(flat: Record<string, any>): Record<string, any> {
  const result: Record<string, any> = {};
  for (const [key, value] of Object.entries(flat)) {
    const parts = key.split('.');
    let current = result;
    for (let i = 0; i < parts.length - 1; i++) {
      if (!current[parts[i]] || typeof current[parts[i]] !== 'object') {
        current[parts[i]] = {};
      }
      current = current[parts[i]];
    }
    current[parts[parts.length - 1]] = value;
  }
  return result;
}

/**
 * A preview is "override-driven" when it has a non-empty override mapping: its
 * input data comes from the form via the mapping, so it must wait for that data
 * before it can render. Previews without a mapping load straight from the base
 * doc/case data.
 */
export function isOverrideDriven(mapping?: OverrideMappingValue): boolean {
  if (!mapping) return false;
  if (typeof mapping === 'string') return mapping.trim().length > 0;
  return Object.keys(mapping).length > 0;
}

/**
 * Whether the computed input overrides carry any usable data yet.
 */
export function hasUsableOverrides(overrides?: Record<string, any> | null): boolean {
  return !!overrides && Object.keys(overrides).length > 0;
}

/**
 * Decide whether a preview request should fire given the configured override
 * mapping and the currently computed overrides.
 *
 * - Override-driven previews only load once the mapped form data is present;
 *   before that they show a "complete the form" placeholder and fire nothing
 *   (avoids a doomed request that Epistola rejects with a 400 for missing
 *   required fields).
 * - Previews without a mapping always load (base data is the whole input).
 */
export function shouldLoadPreview(
  mapping?: OverrideMappingValue,
  overrides?: Record<string, any> | null,
): boolean {
  if (isOverrideDriven(mapping)) {
    return hasUsableOverrides(overrides);
  }
  return true;
}

/**
 * Given an override mapping and the live form data, produce the inputOverrides
 * object (`{ doc, pv }`) the backend overlays onto the real document / process
 * variables before the data mapping runs.
 *
 * The mapping is a JSONata expression over `$form`; legacy `form:`-ref objects
 * are converted on the fly via {@link legacyOverrideToJsonata}. Evaluation is
 * asynchronous because `jsonata().evaluate()` returns a Promise. Only `doc` and
 * `pv` scopes (with at least one resolved field) are kept — matching what the
 * backend consumes.
 */
export async function computeInputOverrides(
  mapping: OverrideMappingValue,
  formData: Record<string, any>,
): Promise<Record<string, any>> {
  if (!mapping) {
    return {};
  }
  const expression = isLegacyOverrideMapping(mapping)
    ? legacyOverrideToJsonata(mapping)
    : String(mapping);
  if (!expression.trim()) {
    return {};
  }

  let evaluated: any;
  try {
    evaluated = await jsonata(expression).evaluate({}, { form: formData ?? {} });
  } catch {
    return {};
  }
  if (!evaluated || typeof evaluated !== 'object' || Array.isArray(evaluated)) {
    return {};
  }

  const result: Record<string, any> = {};
  for (const scope of ['doc', 'pv']) {
    const value = evaluated[scope];
    if (value && typeof value === 'object' && Object.keys(value).length > 0) {
      result[scope] = value;
    }
  }
  return result;
}
