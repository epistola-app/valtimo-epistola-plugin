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

import { firstValueFrom, of, throwError, toArray } from 'rxjs';
import { DesignScope } from '../models';
import { ANY_ENVIRONMENT, ANY_RANGE } from '../services/release-scope';
import {
  designHintsOf,
  environmentScopeOf,
  INVALID_RANGE,
  loadDesignScope,
  offersTemplatesAcrossReleases,
  rangeScopeOf,
  templateReleaseMark,
} from './design-scope';
import { successResource } from '../models';

const scope = (patch: Partial<DesignScope> = {}): DesignScope => ({
  catalogId: 'local/demo/brieven',
  versions: ['2.1.0', '1.9.0'],
  version: '2.1.0',
  draft: false,
  environment: 'staging',
  problems: [],
  read: { environmentId: 'staging', versionRange: null },
  ...patch,
});

describe('rangeScopeOf', () => {
  it('reads a field as generation does: none, a literal range, or only known at run time', () => {
    expect(rangeScopeOf('')).toBeUndefined();
    expect(rangeScopeOf(undefined)).toBeUndefined();
    expect(rangeScopeOf('"^1.4.0"')).toBe('^1.4.0');
    expect(rangeScopeOf(" ' 1.9.0 ' ")).toBe('1.9.0');
    expect(rangeScopeOf('""')).toBeUndefined();
    expect(rangeScopeOf('$pv.briefversie')).toBe(ANY_RANGE);
    // Not a literal: the number 1.4, which generation refuses. Nothing to design a range from.
    expect(rangeScopeOf('1.4')).toBe(ANY_RANGE);
  });
});

describe('environmentScopeOf', () => {
  it('reads a field as generation does', () => {
    expect(environmentScopeOf(' ')).toBeUndefined();
    expect(environmentScopeOf('"staging"')).toBe('staging');
    expect(environmentScopeOf('$pv.omgeving')).toBe(ANY_ENVIRONMENT);
  });
});

describe('loadDesignScope', () => {
  it('starts loading and ends in the scope', async () => {
    const states = await firstValueFrom(loadDesignScope(() => of(scope())).pipe(toArray()));

    expect(states.map((state) => state.loading)).toEqual([true, false]);
    expect(states[1].data).toEqual(scope());
  });

  it('tells a refused range apart from any other failure', async () => {
    const refused = await firstValueFrom(
      loadDesignScope(() => throwError(() => ({ status: 400 }))).pipe(toArray()),
    );
    const failed = await firstValueFrom(
      loadDesignScope(() => throwError(() => ({ status: 502 }))).pipe(toArray()),
    );

    expect(refused[1].error).toBe(INVALID_RANGE);
    expect(failed[1].error).not.toBe(INVALID_RANGE);
  });
});

describe('designHintsOf', () => {
  const hints = (patch: Partial<DesignScope>, environment?: any, range?: any) =>
    designHintsOf(successResource<DesignScope | null>(scope(patch)), environment, range);

  it('says which release the template and its fields come from', () => {
    expect(hints({})).toEqual({
      catalog: [],
      release: [{ key: 'designBasedOnRelease', version: '2.1.0', problem: false }],
    });
    expect(hints({ version: null, draft: true }).release).toEqual([
      { key: 'designBasedOnDeployedDraft', problem: false },
    ]);
    expect(hints({ version: null, draft: true, environment: null }).release).toEqual([
      { key: 'designBasedOnNewestDraft', problem: false },
    ]);
  });

  it('puts each problem once, under the field it concerns', () => {
    const result = hints({
      environment: null,
      problems: ['NOT_DEPLOYED', 'RANGE_UNSATISFIED'],
    });

    expect(result.catalog).toEqual([{ key: 'designCatalogNotDeployed', problem: true }]);
    expect(result.release).toEqual([
      { key: 'designRangeUnsatisfied', problem: true },
      { key: 'designBasedOnRelease', version: '2.1.0', problem: false },
    ]);
    expect(hints({ problems: ['UNKNOWN_ENVIRONMENT'] }).catalog).toEqual([
      { key: 'designEnvironmentUnknown', problem: true },
    ]);
    expect(hints({ problems: ['RANGE_NOT_DEPLOYED'] }).release[0]).toEqual({
      key: 'designRangeNotDeployed',
      problem: true,
    });
  });

  it('calls the basis an assumption when the environment or the range is an expression', () => {
    for (const [environment, range] of [
      [ANY_ENVIRONMENT, undefined],
      [undefined, ANY_RANGE],
    ]) {
      expect(hints({}, environment, range).release).toContainEqual({
        key: 'designBasedOnAssumption',
        problem: false,
      });
    }
    expect(hints({}, 'staging', '^1.0.0').release).not.toContainEqual(
      expect.objectContaining({ key: 'designBasedOnAssumption' }),
    );
  });

  it('says only that a refused range is not one', () => {
    expect(
      designHintsOf(
        { loading: false, data: null, error: INVALID_RANGE } as any,
        undefined,
        '1.x.y',
      ),
    ).toEqual({ catalog: [], release: [{ key: 'versionRangeInvalid', problem: true }] });
  });
});

describe('templateReleaseMark', () => {
  const reachable = ['2.1.0', '2.0.0', '1.9.0'];

  it('marks nothing for a template in every reachable release', () => {
    expect(templateReleaseMark(['2.1.0', '2.0.0', '1.9.0'], reachable)).toBeNull();
    expect(templateReleaseMark(['draft', '2.1.0', '1.9.0'], reachable)).toBeNull();
  });

  it('says from which release a newer template exists', () => {
    expect(templateReleaseMark(['2.1.0', '2.0.0'], reachable)).toEqual({
      key: 'templateFromRelease',
      version: '2.0.0',
    });
  });

  it('says up to which release a removed template exists', () => {
    expect(templateReleaseMark(['2.0.0', '1.9.0'], reachable)).toEqual({
      key: 'templateUntilRelease',
      version: '2.0.0',
    });
  });

  it('says when a template exists only in the draft', () => {
    expect(templateReleaseMark(['draft'], reachable)).toEqual({ key: 'templateOnlyDraft' });
  });
});

describe('offersTemplatesAcrossReleases', () => {
  it('when the release or the environment is an expression, and only then', () => {
    expect(offersTemplatesAcrossReleases(undefined, ANY_RANGE)).toBe(true);
    expect(offersTemplatesAcrossReleases(ANY_ENVIRONMENT, '^1.0.0')).toBe(true);
    expect(offersTemplatesAcrossReleases('staging', '^1.0.0')).toBe(false);
    expect(offersTemplatesAcrossReleases(undefined, undefined)).toBe(false);
  });
});
