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

import { hasValuesFor, mergeComposerData, pruneEmpty, requiredKeys } from './composer-data';

describe('composer-data', () => {
  describe('mergeComposerData', () => {
    it('lays the employee input over the mapped data', () => {
      expect(mergeComposerData({ naam: 'Jansen', motivatie: '' }, { motivatie: 'omdat' })).toEqual({
        naam: 'Jansen',
        motivatie: 'omdat',
      });
    });

    it('merges nested objects key by key', () => {
      expect(
        mergeComposerData(
          { besluit: { datum: '2026-01-01', motivatie: '' } },
          { besluit: { motivatie: 'omdat' } },
        ),
      ).toEqual({ besluit: { datum: '2026-01-01', motivatie: 'omdat' } });
    });

    it('replaces an array whole, since a partial merge has no meaning for template data', () => {
      expect(mergeComposerData({ bijlagen: ['a', 'b'] }, { bijlagen: ['c'] })).toEqual({
        bijlagen: ['c'],
      });
    });

    it('never mutates the mapped data it was given', () => {
      const base = { besluit: { motivatie: '' } };
      mergeComposerData(base, { besluit: { motivatie: 'omdat' } });
      expect(base).toEqual({ besluit: { motivatie: '' } });
    });

    it('handles missing sides', () => {
      expect(mergeComposerData(undefined as any, { a: 1 })).toEqual({ a: 1 });
      expect(mergeComposerData({ a: 1 }, undefined as any)).toEqual({ a: 1 });
    });
  });

  describe('pruneEmpty', () => {
    it('drops what the employee left empty so it cannot overwrite mapped data', () => {
      expect(pruneEmpty({ motivatie: '', datum: null, naam: 'Jansen' })).toEqual({
        naam: 'Jansen',
      });
    });

    it('keeps values that are falsy but real', () => {
      expect(pruneEmpty({ aantal: 0, akkoord: false })).toEqual({ aantal: 0, akkoord: false });
    });

    it('drops an object that is empty after pruning', () => {
      expect(pruneEmpty({ besluit: { motivatie: '' }, naam: 'Jansen' })).toEqual({
        naam: 'Jansen',
      });
    });

    it('keeps the filled part of a partly filled object', () => {
      expect(pruneEmpty({ besluit: { motivatie: '', datum: '2026-01-01' } })).toEqual({
        besluit: { datum: '2026-01-01' },
      });
    });
  });
});

describe('what the preview waits for', () => {
  /**
   * A grid's columns are keyed inside a row. Demanded against the submission they can never be
   * satisfied and the composer waits for them forever — which is exactly what the permit letter
   * did: everything filled in, and the preview still asking for the form to be filled in.
   */
  const gridForm = {
    display: 'form',
    components: [
      {
        type: 'datagrid',
        key: 'activities',
        validate: { required: true },
        components: [
          { type: 'textfield', key: 'type', validate: { required: true } },
          { type: 'textfield', key: 'description', validate: { required: true } },
        ],
      },
    ],
  };

  it('asks for a grid as a whole, not for its columns separately', () => {
    expect(requiredKeys(gridForm)).toEqual(['activities']);
  });

  it('counts a grid as filled once it has a row', () => {
    expect(hasValuesFor({ activities: [{ type: 'bouw' }] }, requiredKeys(gridForm))).toBe(true);
  });

  it('counts a grid as empty while it has none', () => {
    expect(hasValuesFor({ activities: [] }, requiredKeys(gridForm))).toBe(false);
    expect(hasValuesFor({}, requiredKeys(gridForm))).toBe(false);
  });

  it('still descends through layout, which keys its children against the submission', () => {
    const layout = {
      display: 'wizard',
      components: [
        {
          type: 'panel',
          key: 'step1',
          components: [
            {
              type: 'fieldset',
              key: 'applicant',
              components: [
                { type: 'textfield', key: 'applicant.bsn', validate: { required: true } },
              ],
            },
          ],
        },
      ],
    };

    expect(requiredKeys(layout)).toEqual(['applicant.bsn']);
  });
});
