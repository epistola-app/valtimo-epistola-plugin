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

import { EPISTOLA_LETTER_COMPOSER_OPTIONS } from './epistola-letter-composer.formio';

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
