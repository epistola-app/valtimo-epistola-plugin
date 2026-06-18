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

import { filterAttributeKeys } from './attribute-key-filter';

describe('filterAttributeKeys', () => {
  const available = ['language', 'brand', 'region'];

  it('returns all keys when no input and none used', () => {
    expect(filterAttributeKeys(available, [], '')).toEqual(['language', 'brand', 'region']);
  });

  it('filters by current input (case-insensitive)', () => {
    expect(filterAttributeKeys(available, [], 'lang')).toEqual(['language']);
    expect(filterAttributeKeys(available, [], 'LANG')).toEqual(['language']);
    expect(filterAttributeKeys(available, [], 'r')).toEqual(['brand', 'region']);
  });

  it('excludes already-used keys', () => {
    expect(filterAttributeKeys(available, ['language'], '')).toEqual(['brand', 'region']);
    expect(filterAttributeKeys(available, ['language', 'brand'], '')).toEqual(['region']);
  });

  it('combines filtering and exclusion', () => {
    expect(filterAttributeKeys(available, ['brand'], 'r')).toEqual(['region']);
  });

  it('returns empty when all keys are used', () => {
    expect(filterAttributeKeys(available, ['language', 'brand', 'region'], '')).toEqual([]);
  });

  it('returns empty when no keys match input', () => {
    expect(filterAttributeKeys(available, [], 'xyz')).toEqual([]);
  });

  it('handles empty available keys', () => {
    expect(filterAttributeKeys([], [], '')).toEqual([]);
    expect(filterAttributeKeys([], ['language'], 'lang')).toEqual([]);
  });
});
