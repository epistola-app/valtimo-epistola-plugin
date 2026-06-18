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

import { OverrideRow, serializeOverrideRows } from './override-jsonata';

/**
 * Prefix that marked a form-field reference in the legacy override-mapping
 * object format (e.g. `"form:motivationField"`).
 */
export const FORM_REF_PREFIX = 'form:';

/**
 * Whether a stored override-mapping value is in the legacy **object** format
 * (`{ scope: { inputPath: "form:fieldKey" } }`) rather than the new JSONata
 * **string** format.
 */
export function isLegacyOverrideMapping(value: unknown): value is Record<string, any> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

/**
 * TEMPORARY migration shim.
 *
 * Converts a legacy override-mapping object into the equivalent JSONata string
 * over `$form`. Funnelling every legacy value through this one function keeps
 * the rest of the codebase JSONata-only.
 *
 * @deprecated Remove once all deployed forms have been re-saved in the JSONata
 *   format. The admin page's "legacy override format" warning tracks which
 *   forms still need migrating.
 */
export function legacyOverrideToJsonata(mapping: Record<string, any>): string {
  const rows: OverrideRow[] = [];
  for (const [scope, fields] of Object.entries(mapping || {})) {
    if (scope !== 'doc' && scope !== 'pv') continue;
    if (!fields || typeof fields !== 'object') continue;
    for (const [inputPath, ref] of Object.entries(fields as Record<string, unknown>)) {
      const raw = String(ref);
      const formFieldKey = raw.startsWith(FORM_REF_PREFIX)
        ? raw.substring(FORM_REF_PREFIX.length)
        : raw;
      rows.push({ scope, inputPath, formFieldKey });
    }
  }
  return serializeOverrideRows(rows);
}
