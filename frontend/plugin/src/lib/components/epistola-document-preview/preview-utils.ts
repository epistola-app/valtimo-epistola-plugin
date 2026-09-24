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
 * Valtimo value-resolver prefixes a form field key can carry. A field keyed
 * `pv:motivatie` is saved to the `motivatie` process variable on submit; one
 * keyed `doc:/aanvrager/naam` is written to that path in the case document.
 */
const SCOPE_PREFIXES: ReadonlyArray<{ prefix: string; scope: 'doc' | 'pv' }> = [
  { prefix: 'doc:', scope: 'doc' },
  { prefix: 'pv:', scope: 'pv' },
];

/**
 * Split the path part of a prefixed key into segments.
 *
 * Valtimo accepts both notations and treats them identically — its
 * `CaseDocumentJsonValueResolverFactory.toJsonPointer` prefixes a missing `/`
 * and replaces `.` with `/` — so `doc:/aanvrager/naam` and `doc:aanvrager.naam`
 * address the same field. Formio itself nests on `.`, so a dotted key arrives
 * here already split by Formio, with the remainder carried in the value.
 */
function pathSegments(path: string): string[] {
  const raw = path.startsWith('/') ? path.slice(1).split('/') : path.split('.');
  return raw.map((segment) => segment.trim()).filter((segment) => segment.length > 0);
}

function setNested(target: Record<string, any>, segments: string[], value: any): void {
  let current = target;
  for (let i = 0; i < segments.length - 1; i++) {
    const segment = segments[i];
    if (
      !current[segment] ||
      typeof current[segment] !== 'object' ||
      Array.isArray(current[segment])
    ) {
      current[segment] = {};
    }
    current = current[segment];
  }
  current[segments[segments.length - 1]] = value;
}

/**
 * Derive input overrides from the form's own field keys.
 *
 * A `pv:`/`doc:`-prefixed key already states where Valtimo saves that field, so
 * the preview can show exactly what the letter becomes once the form is saved,
 * without an author repeating those targets in an override mapping. Keys with
 * no value-resolver prefix are ignored: they are plain form fields (or Formio
 * plumbing such as `submit`) and say nothing about where their value lands.
 *
 * Note this is only sound where a field key equals its save target. Inside a
 * Form Flow the step's data is saved by its `onComplete` expression instead —
 * see docs/form-flows.md — so those forms keep their explicit mapping.
 */
export function deriveInputOverridesFromKeys(
  formData: Record<string, any> | null | undefined,
): Record<string, any> {
  if (!formData || typeof formData !== 'object') {
    return {};
  }

  const result: Record<string, any> = {};
  for (const [key, value] of Object.entries(formData)) {
    if (value === undefined) {
      continue;
    }
    const match = SCOPE_PREFIXES.find(({ prefix }) => key.startsWith(prefix));
    if (!match) {
      continue;
    }
    const segments = pathSegments(key.slice(match.prefix.length));
    if (segments.length === 0) {
      continue;
    }
    if (!result[match.scope]) {
      result[match.scope] = {};
    }
    setNested(result[match.scope], segments, value);
  }
  return result;
}

/**
 * Deep-merge two `{ doc, pv }` override objects. `explicit` wins on conflicts:
 * a hand-written override mapping stays authoritative over what was derived
 * from the field keys, so existing forms keep behaving exactly as before.
 */
export function mergeInputOverrides(
  derived: Record<string, any>,
  explicit: Record<string, any>,
): Record<string, any> {
  const result: Record<string, any> = { ...derived };
  for (const [key, value] of Object.entries(explicit)) {
    const existing = result[key];
    const bothPlainObjects =
      existing &&
      typeof existing === 'object' &&
      !Array.isArray(existing) &&
      value &&
      typeof value === 'object' &&
      !Array.isArray(value);
    result[key] = bothPlainObjects ? mergeInputOverrides(existing, value) : value;
  }
  return result;
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
 *
 * With `deriveFromKeys`, the form's own `pv:`/`doc:` field keys contribute
 * overrides too (see {@link deriveInputOverridesFromKeys}); the mapping wins
 * wherever both address the same field.
 */
export async function computeInputOverrides(
  mapping: OverrideMappingValue,
  formData: Record<string, any>,
  deriveFromKeys = false,
): Promise<Record<string, any>> {
  const derived = deriveFromKeys ? deriveInputOverridesFromKeys(formData) : {};
  const explicit = await evaluateOverrideMapping(mapping, formData);
  return mergeInputOverrides(derived, explicit);
}

/**
 * Evaluate the hand-written override mapping alone. Returns `{}` for a missing,
 * empty or failing expression, so a broken mapping never blocks the overrides
 * derived from the field keys.
 */
async function evaluateOverrideMapping(
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
