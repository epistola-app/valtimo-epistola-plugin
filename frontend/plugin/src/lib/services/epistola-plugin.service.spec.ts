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

import { of } from 'rxjs';
jest.mock('@angular/core', () => ({ Injectable: () => (target: unknown) => target }));
jest.mock('@valtimo/shared', () => ({}));
jest.mock('@angular/common/http', () => ({ HttpClient: class {}, HttpHeaders: class {} }));

import { EpistolaPluginService } from './epistola-plugin.service';
import { ANY_ENVIRONMENT, ANY_RANGE } from './release-scope';

/**
 * How lookups address a release (ADR 0009). The catalog-wide case is the one that matters: sent
 * without an environment, the server substitutes the configuration's default — exactly where a
 * catalog that is not deployed would 404 again.
 */
describe('EpistolaPluginService release addressing', () => {
  const http = { get: jest.fn().mockReturnValue(of([])) };
  const service = new EpistolaPluginService(
    http as any,
    { config: { valtimoApi: { endpointUri: '/api/' } } } as any,
  );
  const paramsOfLastCall = () => http.get.mock.calls[http.get.mock.calls.length - 1][1].params;

  beforeEach(() => http.get.mockClear());

  it('reads catalog-wide by saying so, never by leaving the environment out', () => {
    service.getTemplates('config', 'local/demo/brieven', {
      environmentId: null,
      versionRange: '2.1.0',
    });

    expect(http.get).toHaveBeenCalledWith(
      '/api/v1/plugin/epistola/configurations/config/templates',
      {
        params: { anyEnvironment: 'true', versionRange: '2.1.0', catalogId: 'local/demo/brieven' },
      },
    );
  });

  it('reads an environment draft by the environment alone', () => {
    service.getTemplateDetails('config', 'besluit', 'local/demo/brieven', {
      environmentId: 'staging',
      versionRange: null,
    });

    expect(paramsOfLastCall()).toEqual({
      environmentId: 'staging',
      catalogId: 'local/demo/brieven',
    });
  });

  it.each([
    [
      'a literal environment and range',
      'staging',
      '^1.4.0',
      { environmentId: 'staging', versionRange: '^1.4.0' },
    ],
    ['the default environment and no range', undefined, undefined, {}],
    [
      'expressions for both',
      ANY_ENVIRONMENT,
      ANY_RANGE,
      { anyEnvironment: 'true', anyRange: 'true' },
    ],
  ])('asks for the design scope with %s', (_name, environment, range, expected) => {
    service.getDesignScope('config', 'brieven', environment as any, range as any);

    expect(http.get).toHaveBeenCalledWith(
      '/api/v1/plugin/epistola/configurations/config/design-scope',
      { params: { ...expected, catalogId: 'brieven' } },
    );
  });
});
