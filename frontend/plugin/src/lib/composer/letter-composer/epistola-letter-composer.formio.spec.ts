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

jest.mock('@valtimo/components', () => ({}));
jest.mock('../../components/valtimo-formio-adapter', () => ({
  registerEpistolaFormioComponent: jest.fn(),
  withPrefilledCarriers: jest.fn((base: unknown) => base),
}));
jest.mock('./epistola-letter-composer.component', () => ({
  EpistolaLetterComposerComponent: class {},
}));

import { composerKeyOf, EPISTOLA_LETTER_COMPOSER_OPTIONS } from './epistola-letter-composer.formio';

describe('letter composer component schema', () => {
  it('excludes itself from Valtimo prefill', () => {
    // Its key is a pv: one so the chosen letter becomes a process variable on submit. Reading it
    // back is another matter: Valtimo resolves a pv: key against the case's process instances and
    // fails the whole form with a 500 once more than one holds that variable — and there is
    // nothing to prefill anyway, since the letter is what the employee is about to choose.
    expect(EPISTOLA_LETTER_COMPOSER_OPTIONS.schema).toMatchObject({ prefill: false });
  });

  it('carries the hidden task and document carriers', () => {
    const keys = (EPISTOLA_LETTER_COMPOSER_OPTIONS.schema as any).components.map(
      (component: any) => component.key,
    );
    expect(keys).toEqual(['epistolaTaskId', 'epistolaDocumentId']);
  });
});

describe('property name', () => {
  const keyField = () =>
    (EPISTOLA_LETTER_COMPOSER_OPTIONS.editForm as any)().components.find(
      (component: any) => component.key === 'key',
    );

  /**
   * Formio derives a dropped component's key from the palette title — it ignores a schema `key`
   * and an editForm `defaultValue` alike — so the author types this field either way. What they no
   * longer type is the `pv:` prefix: the component applies it on save (see `composerKeyOf`), since
   * there is no case where the key could be anything else. A name is asked for, and refused only
   * when a process variable could not carry it.
   */
  it('asks for a name, with or without the prefix, and refuses what cannot be one', () => {
    const field = keyField();
    const pattern = new RegExp(field.validate.pattern);

    expect(field.defaultValue).toBeUndefined();
    expect(field.validate.required).toBe(true);
    expect(pattern.test('epistolaLetter')).toBe(true);
    expect(pattern.test('pv:epistolaLetter')).toBe(true);
    expect(pattern.test('doc:/brief')).toBe(false);
    expect(pattern.test('two words')).toBe(false);
    expect(pattern.test('1brief')).toBe(false);
    // The message names what to fix, not the prefix the author no longer owns.
    expect(field.validate.customMessage).not.toContain('pv:');
  });

  it('prefixes a name on save and leaves an already-prefixed key alone', () => {
    expect(composerKeyOf('epistolaLetter')).toBe('pv:epistolaLetter');
    expect(composerKeyOf('pv:epistolaLetter')).toBe('pv:epistolaLetter');
  });

  it('hands back anything a process variable could not be named, for the editForm to refuse', () => {
    // Prefixing junk would bury a message the author can act on inside a variable nobody meant.
    expect(composerKeyOf('doc:/brief')).toBe('doc:/brief');
    expect(composerKeyOf('')).toBe('');
    expect(composerKeyOf(undefined)).toBeUndefined();
  });

  /** Formio does honour a schema label, so the drop at least arrives named. */
  it('is labelled from the schema, which Formio does honour', () => {
    expect((EPISTOLA_LETTER_COMPOSER_OPTIONS.schema as any).label).toBe('Choose a letter');
  });
});
