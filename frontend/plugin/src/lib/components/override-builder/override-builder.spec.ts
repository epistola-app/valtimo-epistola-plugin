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
 * Tests for the override builder's pure conversion logic: the simple-table <-> JSONata
 * serializer/parser ({@link override-jsonata}) and the legacy object -> JSONata migration
 * shim ({@link legacy-override-converter}).
 */
import {
  OverrideRow,
  isRoundTrippable,
  parseOverrideJsonata,
  serializeOverrideRows,
} from './override-jsonata';
import { legacyOverrideToJsonata, isLegacyOverrideMapping } from './legacy-override-converter';

import * as _jsonata from 'jsonata';
const jsonata = (_jsonata as any).default || _jsonata;

describe('override-jsonata', () => {
  describe('serializeOverrideRows', () => {
    it('renders a $form reference grouped by scope', () => {
      const rows: OverrideRow[] = [{ scope: 'doc', inputPath: 'name', formFieldKey: 'nameField' }];
      expect(serializeOverrideRows(rows)).toBe(
        '{\n  "doc": {\n    "name": $form.nameField\n  }\n}',
      );
    });

    it('backtick-quotes form keys that are not bare identifiers', () => {
      const rows: OverrideRow[] = [
        { scope: 'pv', inputPath: 'motivation', formFieldKey: 'pv:motivation' },
      ];
      expect(serializeOverrideRows(rows)).toContain('$form.`pv:motivation`');
    });

    it('expands dot-notation input paths into nested objects', () => {
      const rows: OverrideRow[] = [
        { scope: 'doc', inputPath: 'address.street', formFieldKey: 'streetField' },
        { scope: 'doc', inputPath: 'address.city', formFieldKey: 'cityField' },
      ];
      const expr = serializeOverrideRows(rows);
      expect(expr).toContain('"address": {');
      expect(expr).toContain('"street": $form.streetField');
      expect(expr).toContain('"city": $form.cityField');
    });

    it('skips rows with an empty path or field', () => {
      const rows: OverrideRow[] = [
        { scope: 'doc', inputPath: '', formFieldKey: 'x' },
        { scope: 'doc', inputPath: 'y', formFieldKey: '' },
      ];
      expect(serializeOverrideRows(rows)).toBe('');
    });

    it('returns an empty string for no rows', () => {
      expect(serializeOverrideRows([])).toBe('');
    });
  });

  describe('parseOverrideJsonata', () => {
    it('round-trips a serialized expression back to rows', () => {
      const rows: OverrideRow[] = [
        { scope: 'doc', inputPath: 'name', formFieldKey: 'nameField' },
        { scope: 'pv', inputPath: 'motivation', formFieldKey: 'pv:motivation' },
      ];
      const expr = serializeOverrideRows(rows);
      expect(parseOverrideJsonata(expr)).toEqual(rows);
    });

    it('rebuilds dot-notation paths from nested objects', () => {
      const expr = '{ "doc": { "address": { "street": $form.streetField } } }';
      expect(parseOverrideJsonata(expr)).toEqual([
        { scope: 'doc', inputPath: 'address.street', formFieldKey: 'streetField' },
      ]);
    });

    it('returns [] for an empty expression', () => {
      expect(parseOverrideJsonata('')).toEqual([]);
      expect(parseOverrideJsonata('   ')).toEqual([]);
    });

    it('returns null for non-$form leaves (not representable in the simple table)', () => {
      expect(parseOverrideJsonata('{ "doc": { "name": $form.a & $form.b } }')).toBeNull();
      expect(parseOverrideJsonata('{ "doc": { "name": $doc.x } }')).toBeNull();
    });

    it('returns null for scopes other than doc/pv', () => {
      expect(parseOverrideJsonata('{ "case": { "x": $form.y } }')).toBeNull();
    });

    it('returns null for invalid JSONata', () => {
      expect(parseOverrideJsonata('{ not valid ::')).toBeNull();
    });
  });

  describe('isRoundTrippable', () => {
    it('is true for a simple $form mapping and false for a transform', () => {
      expect(isRoundTrippable('{ "doc": { "name": $form.x } }')).toBe(true);
      expect(isRoundTrippable('{ "doc": { "name": $uppercase($form.x) } }')).toBe(false);
    });
  });
});

describe('legacy-override-converter', () => {
  describe('isLegacyOverrideMapping', () => {
    it('detects the legacy object format vs the new string format', () => {
      expect(isLegacyOverrideMapping({ doc: { name: 'form:x' } })).toBe(true);
      expect(isLegacyOverrideMapping('{ "doc": { "name": $form.x } }')).toBe(false);
      expect(isLegacyOverrideMapping(null)).toBe(false);
      expect(isLegacyOverrideMapping(undefined)).toBe(false);
    });
  });

  describe('legacyOverrideToJsonata', () => {
    it('converts a legacy mapping to an equivalent JSONata expression', () => {
      const expr = legacyOverrideToJsonata({ doc: { name: 'form:nameField' } });
      expect(expr).toBe('{\n  "doc": {\n    "name": $form.nameField\n  }\n}');
    });

    it('strips the form: prefix and backtick-quotes special keys', () => {
      const expr = legacyOverrideToJsonata({ pv: { motivation: 'form:pv:motivation' } });
      expect(expr).toContain('$form.`pv:motivation`');
    });

    it('ignores scopes other than doc/pv', () => {
      const expr = legacyOverrideToJsonata({
        doc: { name: 'form:nameField' },
        case: { owner: 'form:ownerField' },
      });
      expect(expr).not.toContain('owner');
    });

    it('produces an expression that evaluates to the same overlay the legacy resolver did', async () => {
      const expr = legacyOverrideToJsonata({
        doc: { name: 'form:nameField' },
        pv: { motivation: 'form:pv:motivation' },
      });
      const result = await jsonata(expr).evaluate(
        {},
        { form: { nameField: 'Alice', 'pv:motivation': 'because' } },
      );
      expect(result).toEqual({ doc: { name: 'Alice' }, pv: { motivation: 'because' } });
    });
  });
});
