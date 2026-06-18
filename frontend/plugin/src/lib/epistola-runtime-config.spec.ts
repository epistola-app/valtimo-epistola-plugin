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

import { isEpistolaEnabled } from './epistola-runtime-config';

describe('isEpistolaEnabled', () => {
  const originalWindow: unknown = Reflect.get(globalThis, 'window');

  afterEach(() => {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: originalWindow,
    });
  });

  it('defaults to enabled when window is unavailable', () => {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: undefined,
    });

    expect(isEpistolaEnabled()).toBe(true);
  });

  it('defaults to enabled when the runtime flag is missing', () => {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: {},
    });

    expect(isEpistolaEnabled()).toBe(true);
  });

  it('disables only for boolean false or string false', () => {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: { env: { epistolaEnabled: false } },
    });
    expect(isEpistolaEnabled()).toBe(false);

    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: { env: { epistolaEnabled: 'false' } },
    });
    expect(isEpistolaEnabled()).toBe(false);
  });

  it('enables for all other configured values', () => {
    for (const epistolaEnabled of [true, 'true', '', 'FALSE']) {
      Object.defineProperty(globalThis, 'window', {
        configurable: true,
        value: { env: { epistolaEnabled } },
      });

      expect(isEpistolaEnabled()).toBe(true);
    }
  });
});
