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

import { Observable, of } from 'rxjs';
import { catchError, map, startWith } from 'rxjs/operators';
import {
  AsyncResource,
  CatalogReleases,
  errorResource,
  initialResource,
  loadingResource,
  successResource,
} from '../models';
import { EnvironmentScope } from '../services';

/**
 * What a version range field says: which release of a catalog a range selects, shared by every
 * place a release is pinned (the generate-document configurator and the letter composer's letter
 * set), so the two explain the same range the same way.
 */

/** The error a release lookup reports for a range that is not one (the server answers 400). */
export const INVALID_RANGE = 'invalidRange';

/** The catalog's releases as last loaded, with the range they were loaded for. */
export interface ReleasesState {
  range: string;
  resource: AsyncResource<CatalogReleases | null>;
}

/** What a version range field says is rendered: a translation key and the version it names. */
export interface ReleaseHint {
  key: string;
  version?: string;
  /** True when saving this would fail, or render nothing, at run time. */
  problem: boolean;
}

/** No catalog chosen, so there is nothing to look up. */
export function noReleases(range: string): ReleasesState {
  return { range, resource: initialResource(null) };
}

/**
 * A catalog's releases and the one `range` selects, as a stream that starts loading and ends in
 * either the releases or an error — {@link INVALID_RANGE} when the server refused the range itself.
 *
 * @param getReleases the lookup, as `EpistolaPluginService.getReleases` makes it
 */
export function loadReleases(
  getReleases: (
    catalogId: string,
    environment: EnvironmentScope,
    range: string,
  ) => Observable<CatalogReleases>,
  catalogId: string,
  environment: EnvironmentScope,
  range: string,
): Observable<ReleasesState> {
  return getReleases(catalogId, environment, range).pipe(
    map((releases): ReleasesState => ({ range, resource: successResource(releases) })),
    catchError((error: unknown) =>
      of<ReleasesState>({
        range,
        resource: errorResource<CatalogReleases | null>(
          null,
          (error as { status?: number } | null)?.status === 400
            ? INVALID_RANGE
            : 'Failed to load releases',
        ),
      }),
    ),
    startWith<ReleasesState>({ range, resource: loadingResource(null) }),
  );
}

/**
 * What a link with this range renders, as the field explains it: the release it selects, the
 * draft an environment serves, or why it renders nothing. Null while loading, and when a lookup
 * failed for another reason than the range itself (the template field reports that).
 */
export function releaseHintOf(state: ReleasesState): ReleaseHint | null {
  const { range, resource } = state;
  if (resource.error === INVALID_RANGE) return { key: 'versionRangeInvalid', problem: true };
  const releases = resource.data;
  if (resource.loading || !releases) return null;
  if (range) {
    return releases.selected
      ? { key: 'versionRangeSelects', version: releases.selected, problem: false }
      : { key: 'versionRangeMatchesNothing', problem: true };
  }
  if (releases.draft) return { key: 'versionRangeNoneDraft', problem: false };
  return releases.selected
    ? { key: 'versionRangeNoneHighest', version: releases.selected, problem: false }
    : null;
}
