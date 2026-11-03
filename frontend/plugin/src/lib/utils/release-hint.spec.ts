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

import { firstValueFrom, of, throwError } from 'rxjs';
import { toArray } from 'rxjs/operators';
import { INVALID_RANGE, loadReleases, releaseHintOf } from './release-hint';

describe('releaseHintOf', () => {
  const state = (range: string, data: object | null, error: string | null = null) => ({
    range,
    resource: { data: data as any, loading: false, error },
  });

  it('names the release a range selects', () => {
    expect(
      releaseHintOf(state('^1.0.0', { versions: ['1.9.0'], selected: '1.9.0', draft: false })),
    ).toEqual({
      key: 'versionRangeSelects',
      version: '1.9.0',
      problem: false,
    });
  });

  it('warns when no release satisfies the range', () => {
    expect(
      releaseHintOf(state('^3.0.0', { versions: ['1.9.0'], selected: null, draft: false })),
    ).toEqual({
      key: 'versionRangeMatchesNothing',
      problem: true,
    });
  });

  it('without a range, says the environment renders its draft, else the highest release', () => {
    expect(
      releaseHintOf(state('', { versions: ['1.9.0'], selected: null, draft: true }))?.key,
    ).toBe('versionRangeNoneDraft');
    expect(
      releaseHintOf(state('', { versions: ['1.9.0'], selected: '1.9.0', draft: false })),
    ).toEqual({
      key: 'versionRangeNoneHighest',
      version: '1.9.0',
      problem: false,
    });
  });

  it('says nothing while loading, or when the lookup failed for another reason', () => {
    expect(
      releaseHintOf({ range: '1.0.0', resource: { data: null, loading: true, error: null } }),
    ).toBeNull();
    expect(releaseHintOf(state('1.0.0', null, 'Failed to load releases'))).toBeNull();
  });
});

describe('loadReleases', () => {
  const releases = { versions: ['1.9.0'], selected: '1.9.0', draft: false };

  it('starts loading and ends with the releases the range was loaded for', async () => {
    const states = await firstValueFrom(
      loadReleases(() => of(releases), 'gemeente', 'prod', '^1.0.0').pipe(toArray()),
    );

    expect(states.map((state) => state.resource.loading)).toEqual([true, false]);
    expect(states[1]).toEqual({
      range: '^1.0.0',
      resource: expect.objectContaining({ data: releases, error: null }),
    });
  });

  it('reports a range the server refused as an invalid range, and anything else as a failure', async () => {
    const refused = await firstValueFrom(
      loadReleases(() => throwError(() => ({ status: 400 })), 'gemeente', undefined, 'nope').pipe(
        toArray(),
      ),
    );
    const failed = await firstValueFrom(
      loadReleases(() => throwError(() => ({ status: 502 })), 'gemeente', undefined, '').pipe(
        toArray(),
      ),
    );

    expect(refused[1].resource.error).toBe(INVALID_RANGE);
    expect(failed[1].resource.error).toBe('Failed to load releases');
  });
});
