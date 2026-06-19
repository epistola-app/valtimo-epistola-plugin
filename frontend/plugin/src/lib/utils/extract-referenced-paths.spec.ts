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

import { extractReferencedPaths } from './extract-referenced-paths';

describe('extractReferencedPaths', () => {
  it('extracts doc/pv paths from a top-level object mapping', () => {
    const expr = '{ "name": $doc.aanvrager.naam, "date": $pv.startDate }';

    expect(extractReferencedPaths(expr)).toEqual([
      { scope: 'doc', path: 'aanvrager.naam' },
      { scope: 'pv', path: 'startDate' },
    ]);
  });

  it('recurses into nested object literals', () => {
    const expr = '{ "a": { "b": $doc.x, "c": { "d": $pv.y } } }';

    expect(extractReferencedPaths(expr)).toEqual([
      { scope: 'doc', path: 'x' },
      { scope: 'pv', path: 'y' },
    ]);
  });

  it('extracts $case references', () => {
    expect(extractReferencedPaths('{ "owner": $case.assignee }')).toEqual([
      { scope: 'case', path: 'assignee' },
    ]);
  });

  it('finds references inside function calls and conditionals', () => {
    const expr = '{ "x": $uppercase($doc.naam), "y": $pv.flag ? $doc.a : $pv.b }';

    expect(extractReferencedPaths(expr)).toEqual([
      { scope: 'doc', path: 'a' },
      { scope: 'doc', path: 'naam' },
      { scope: 'pv', path: 'b' },
      { scope: 'pv', path: 'flag' },
    ]);
  });

  it('records a whole-scope reference with an empty path', () => {
    expect(extractReferencedPaths('$spread($doc)')).toEqual([{ scope: 'doc', path: '' }]);
  });

  it('deduplicates repeated references', () => {
    const expr = '{ "a": $doc.x, "b": $doc.x }';

    expect(extractReferencedPaths(expr)).toEqual([{ scope: 'doc', path: 'x' }]);
  });

  it('ignores non-doc/pv/case variables like $form', () => {
    expect(extractReferencedPaths('{ "a": $form.field, "b": $doc.x }')).toEqual([
      { scope: 'doc', path: 'x' },
    ]);
  });

  it('returns [] for invalid JSONata', () => {
    expect(extractReferencedPaths('{ "x": $doc.')).toEqual([]);
  });

  it('returns [] for empty or nullish input', () => {
    expect(extractReferencedPaths('')).toEqual([]);
    expect(extractReferencedPaths('   ')).toEqual([]);
    expect(extractReferencedPaths(null)).toEqual([]);
    expect(extractReferencedPaths(undefined)).toEqual([]);
  });
});
