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
  DesignProblem,
  DesignScope,
  errorResource,
  initialResource,
  loadingResource,
  successResource,
} from '../models';
import {
  ANY_ENVIRONMENT,
  ANY_RANGE,
  EnvironmentScope,
  RangeScope,
} from '../services/release-scope';
import { decodeJsonataStringLiteral } from './jsonata-literal';

/**
 * What a configurator says about the release it designs a link against, shared by every place a
 * catalog and release are chosen (the generate-document configurator and the letter composer's
 * letter set), so the two explain the same situation the same way (ADR 0009).
 */

/** The error a design-scope lookup reports for a literal range that is not one (the server answers 400). */
export const INVALID_RANGE = 'invalidRange';

/** The design scope as last loaded. */
export type DesignScopeState = AsyncResource<DesignScope | null>;

/** One line a configurator shows: a translation key, and the version it names, if any. */
export interface DesignHint {
  key: string;
  version?: string;
  /** True for a warning: the link will not render as configured until something changes. */
  problem: boolean;
}

/** What to show under the catalog field and under the release field. */
export interface DesignHints {
  catalog: DesignHint[];
  release: DesignHint[];
}

/** No catalog chosen, so there is nothing to look up. */
export const NO_DESIGN_SCOPE: DesignScopeState = initialResource(null);

/**
 * The environment an environment field's expression names, as generation picks it: blank defers
 * to the plugin configuration's default, a string literal names one, and anything else is
 * evaluated at run time, so no single environment applies.
 */
export function environmentScopeOf(expression: string | null | undefined): EnvironmentScope {
  if (!expression || !expression.trim()) return undefined;
  const literal = decodeJsonataStringLiteral(expression);
  return literal !== undefined && literal.trim() ? literal.trim() : ANY_ENVIRONMENT;
}

/**
 * The release scope of a range field's expression, read the way generation reads it: blank is no
 * range, a string literal is that range, and anything else is only known at run time.
 */
export function rangeScopeOf(expression: string | null | undefined): RangeScope {
  if (!expression || !expression.trim()) return undefined;
  const literal = decodeJsonataStringLiteral(expression);
  if (literal === undefined) return ANY_RANGE;
  return literal.trim() ? literal.trim() : undefined;
}

/**
 * The design scope as a stream that starts loading and ends in the scope or an error —
 * {@link INVALID_RANGE} when the server refused the range itself.
 *
 * @param getDesignScope the lookup, as `EpistolaPluginService.getDesignScope` makes it
 */
export function loadDesignScope(
  getDesignScope: () => Observable<DesignScope>,
): Observable<DesignScopeState> {
  return getDesignScope().pipe(
    map((scope): DesignScopeState => successResource<DesignScope | null>(scope)),
    catchError((error: unknown) =>
      of<DesignScopeState>(
        errorResource<DesignScope | null>(
          null,
          (error as { status?: number } | null)?.status === 400
            ? INVALID_RANGE
            : 'Failed to load the catalog release',
        ),
      ),
    ),
    startWith<DesignScopeState>(loadingResource<DesignScope | null>(null)),
  );
}

const CATALOG_PROBLEMS: Partial<Record<DesignProblem, string>> = {
  UNKNOWN_ENVIRONMENT: 'designEnvironmentUnknown',
  NOT_DEPLOYED: 'designCatalogNotDeployed',
};

const RELEASE_PROBLEMS: Partial<Record<DesignProblem, string>> = {
  RANGE_NOT_DEPLOYED: 'designRangeNotDeployed',
  RANGE_UNSATISFIED: 'designRangeUnsatisfied',
};

/**
 * What a configurator says about a design scope: the problems, as warnings where they concern —
 * the catalog or the release — and, under the release, what the template and its fields are
 * based on. When the environment or the range is an expression, that basis is an assumption, and
 * it says so.
 */
export function designHintsOf(
  state: DesignScopeState,
  environment: EnvironmentScope,
  range: RangeScope,
): DesignHints {
  if (state.error === INVALID_RANGE) {
    return { catalog: [], release: [{ key: 'versionRangeInvalid', problem: true }] };
  }
  const scope = state.data;
  if (state.loading || !scope) return { catalog: [], release: [] };

  const catalog: DesignHint[] = [];
  const release: DesignHint[] = [];
  for (const problem of scope.problems) {
    const catalogKey = CATALOG_PROBLEMS[problem];
    if (catalogKey) catalog.push({ key: catalogKey, problem: true });
    const releaseKey = RELEASE_PROBLEMS[problem];
    if (releaseKey) release.push({ key: releaseKey, problem: true });
  }

  if (scope.version) {
    release.push({ key: 'designBasedOnRelease', version: scope.version, problem: false });
  } else if (scope.draft) {
    release.push({
      key: scope.environment ? 'designBasedOnDeployedDraft' : 'designBasedOnNewestDraft',
      problem: false,
    });
  }
  if (environment === ANY_ENVIRONMENT || range === ANY_RANGE) {
    release.push({ key: 'designBasedOnAssumption', problem: false });
  }
  return { catalog, release };
}

/**
 * Whether a configurator offers every template its link can reach rather than one release's: when
 * the release or the environment is an expression, any release it reaches may render.
 */
export function offersTemplatesAcrossReleases(
  environment: EnvironmentScope,
  range: RangeScope,
): boolean {
  return environment === ANY_ENVIRONMENT || range === ANY_RANGE;
}

/**
 * Where a template exists, as the template list marks it: nothing when it is in every reachable
 * release, else a translation key and the version it starts or ends at.
 *
 * @param releases  the releases containing it, highest first (`draft` first, if any)
 * @param reachable every reachable released version, highest first
 */
export function templateReleaseMark(
  releases: string[],
  reachable: string[],
): { key: string; version?: string } | null {
  const versions = releases.filter((release) => release !== 'draft');
  if (versions.length === 0) return releases.length ? { key: 'templateOnlyDraft' } : null;
  const newest = reachable[0];
  const oldest = reachable[reachable.length - 1];
  const inNewest = !newest || versions.includes(newest);
  const inOldest = !oldest || versions.includes(oldest);
  if (inNewest && inOldest) return null;
  if (inNewest) return { key: 'templateFromRelease', version: versions[versions.length - 1] };
  return { key: 'templateUntilRelease', version: versions[0] };
}
