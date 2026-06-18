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

import { hideFormioComponentFromBuilder } from './formio-builder-utils';

describe('hideFormioComponentFromBuilder', () => {
  afterEach(() => {
    delete (globalThis as any).window;
  });

  it('overrides the registered component builderInfo getter to false', () => {
    class Cmp {
      static get builderInfo() {
        return { schema: { type: 'epistola-override-builder' }, group: 'basic' };
      }
    }
    (globalThis as any).window = { Formio: { Components: { components: { 'epistola-x': Cmp } } } };

    expect((Cmp as any).builderInfo).toBeTruthy();
    hideFormioComponentFromBuilder('epistola-x');
    expect((Cmp as any).builderInfo).toBe(false);
  });

  it('is a no-op when the component is not registered', () => {
    (globalThis as any).window = { Formio: { Components: { components: {} } } };
    expect(() => hideFormioComponentFromBuilder('missing')).not.toThrow();
  });

  it('is a no-op when Formio is not present', () => {
    (globalThis as any).window = {};
    expect(() => hideFormioComponentFromBuilder('epistola-x')).not.toThrow();
  });
});
