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

import {
  isEpistolaComposerEnabled,
  isEpistolaEnabled,
  isEpistolaFeatureEnabled,
} from './epistola-runtime-config';

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

describe('isEpistolaFeatureEnabled', () => {
  const originalWindow: unknown = Reflect.get(globalThis, 'window');

  function withFeatures(epistolaFeatures: unknown, rest: Record<string, unknown> = {}): void {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: { env: { epistolaFeatures, ...rest } },
    });
  }

  afterEach(() => {
    Object.defineProperty(globalThis, 'window', { configurable: true, value: originalWindow });
  });

  it('uses the declared default when nothing is requested', () => {
    // Including the empty string, which is what a container serves when EPISTOLA_FEATURES is
    // unset: the entrypoint runs a plain envsubst. An installation that has not heard of the flag
    // must behave exactly as before.
    for (const value of [undefined, '', '   ', 42]) {
      withFeatures(value);
      expect(isEpistolaFeatureEnabled('composer')).toBe(true);
    }
  });

  it('switches a feature off with a minus', () => {
    withFeatures('-composer');

    expect(isEpistolaFeatureEnabled('composer')).toBe(false);
  });

  it('takes several features in one value, space- or comma-separated', () => {
    for (const value of ['-composer +other', '-composer,+other', ' -composer ,  +other ']) {
      withFeatures(value);
      expect(isEpistolaFeatureEnabled('composer')).toBe(false);
    }
  });

  it('treats a bare name as a plus, and ignores case', () => {
    withFeatures('-composer');
    expect(isEpistolaFeatureEnabled('composer')).toBe(false);

    withFeatures('composer');
    expect(isEpistolaFeatureEnabled('composer')).toBe(true);

    withFeatures('-COMPOSER');
    expect(isEpistolaFeatureEnabled('composer')).toBe(false);
  });

  it('lets the last entry win', () => {
    withFeatures('-composer +composer');
    expect(isEpistolaFeatureEnabled('composer')).toBe(true);

    withFeatures('+composer -composer');
    expect(isEpistolaFeatureEnabled('composer')).toBe(false);
  });

  it('ignores an unknown feature rather than failing', () => {
    // A typo in a deployment variable must not decide whether the application renders.
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    withFeatures('-composr -composer');

    expect(isEpistolaFeatureEnabled('composer')).toBe(false);
    expect(warn).toHaveBeenCalledWith(expect.stringContaining("unknown feature 'composr'"));

    warn.mockRestore();
  });

  it('follows the plugin-wide flag, so switching everything off takes every feature with it', () => {
    withFeatures('+composer', { epistolaEnabled: false });

    expect(isEpistolaFeatureEnabled('composer')).toBe(false);
  });

  it('leaves the rest of the plugin alone', () => {
    // Independent in the useful direction: an installation can keep the plugin and drop one
    // feature, which is the whole point of a per-feature switch.
    withFeatures('-composer');

    expect(isEpistolaEnabled()).toBe(true);
    expect(isEpistolaComposerEnabled()).toBe(false);
  });
});
