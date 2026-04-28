import { OverrideMapping } from '../override-builder/override-builder.component';

export const FORM_REF_PREFIX = 'form:';

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
 * Given an override mapping (scope -> { inputPath -> "form:<componentKey>" })
 * and form data, produce the inputOverrides object for the backend.
 * The "form:" prefix identifies form field references; the remainder is the Formio component key.
 */
export function computeInputOverrides(
  mapping: OverrideMapping,
  formData: Record<string, any>,
): Record<string, any> {
  const result: Record<string, any> = {};
  for (const [scope, fields] of Object.entries(mapping)) {
    if (scope !== 'doc' && scope !== 'pv') continue;
    const flatOverrides: Record<string, any> = {};
    for (const [inputPath, ref] of Object.entries(fields)) {
      const formFieldKey = String(ref).startsWith(FORM_REF_PREFIX)
        ? String(ref).substring(FORM_REF_PREFIX.length)
        : String(ref);
      const value = formData[formFieldKey];
      if (value !== undefined) {
        flatOverrides[inputPath] = value;
      }
    }
    if (Object.keys(flatOverrides).length > 0) {
      result[scope] = expandDotNotation(flatOverrides);
    }
  }
  return result;
}
