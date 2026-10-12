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

import { Injector } from '@angular/core';

const registered: string[] = [];

jest.mock('./letter-composer/epistola-letter-composer.formio', () => ({
  registerEpistolaLetterComposerComponent: () => registered.push('letter-composer'),
}));
jest.mock('./letter-set-builder/letter-set-builder.formio', () => ({
  registerEpistolaLetterSetBuilderComponent: () => registered.push('letter-set-builder'),
}));
jest.mock('./mapping-field/mapping-field.formio', () => ({
  registerEpistolaMappingFieldComponent: () => registered.push('mapping-field'),
}));
jest.mock('./write-back-builder/write-back-builder.formio', () => ({
  registerEpistolaWriteBackBuilderComponent: () => registered.push('write-back-builder'),
}));

import { registerEpistolaComposerComponents } from './composer.registration';

describe('registerEpistolaComposerComponents', () => {
  const originalWindow: unknown = Reflect.get(globalThis, 'window');
  const injector = {} as Injector;

  function withEnv(env: unknown): void {
    Object.defineProperty(globalThis, 'window', { configurable: true, value: { env } });
  }

  beforeEach(() => {
    registered.length = 0;
  });

  afterEach(() => {
    Object.defineProperty(globalThis, 'window', { configurable: true, value: originalWindow });
  });

  it('registers all four components by default', () => {
    withEnv({});

    registerEpistolaComposerComponents(injector);

    expect(registered).toEqual([
      'mapping-field',
      'letter-set-builder',
      'write-back-builder',
      'letter-composer',
    ]);
  });

  it('registers nothing when the composer is switched off', () => {
    // Registration is the chokepoint: Form.io's registry is global, so a component that never
    // registers is absent from the palette, from the builder's edit forms and from any form that
    // renders one. The backend flag of the same name removes the endpoints; before this, an author
    // could configure a composer that had nowhere to call.
    withEnv({ epistolaFeatures: '-composer' });

    registerEpistolaComposerComponents(injector);

    expect(registered).toEqual([]);
  });

  it('registers nothing when the whole plugin is switched off', () => {
    withEnv({ epistolaEnabled: false });

    registerEpistolaComposerComponents(injector);

    expect(registered).toEqual([]);
  });
});
