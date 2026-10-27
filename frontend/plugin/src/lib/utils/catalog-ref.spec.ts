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

import { CatalogInfo } from '../models';
import { catalogLabel, matchCatalogId, sortCatalogs } from './catalog-ref';

const catalog = (registry: string, namespace: string, key: string): CatalogInfo => ({
  id: `${registry}/${namespace}/${key}`,
  name: key,
  registry,
  namespace,
  key,
  editable: true,
});

describe('matchCatalogId', () => {
  const demo = catalog('local', 'valtimo', 'municipality-demo');
  const letters = catalog('exchange.epistola.app', 'acme', 'letters');

  it('keeps a reference stored in full', () => {
    expect(matchCatalogId('local/valtimo/municipality-demo', [demo, letters])).toBe(demo.id);
  });

  it('finds the catalog a key saved against contract 1.x names', () => {
    expect(matchCatalogId('municipality-demo', [demo, letters])).toBe(demo.id);
  });

  it('finds the catalog a namespace and key name', () => {
    expect(matchCatalogId('acme/letters', [demo, letters])).toBe(letters.id);
  });

  it('leaves a key two namespaces share as it is, rather than guessing', () => {
    const other = catalog('local', 'other', 'municipality-demo');
    expect(matchCatalogId('municipality-demo', [demo, other])).toBe('municipality-demo');
  });

  it('leaves a reference to a catalog the tenant does not have as it is', () => {
    expect(matchCatalogId('elsewhere', [demo])).toBe('elsewhere');
    expect(matchCatalogId('other/municipality-demo', [demo])).toBe('other/municipality-demo');
  });

  it('has nothing to match without a reference', () => {
    expect(matchCatalogId('', [demo])).toBeNull();
    expect(matchCatalogId(undefined, [demo])).toBeNull();
  });
});

describe('catalogLabel', () => {
  it('names the namespace of a tenant’s own catalog', () => {
    expect(catalogLabel({ ...catalog('local', 'valtimo', 'brieven'), name: 'Brieven' })).toBe(
      'Brieven (valtimo)',
    );
  });

  it('names the registry too when the catalog is not the tenant’s own', () => {
    expect(
      catalogLabel({ ...catalog('exchange.epistola.app', 'vng', 'brieven'), name: 'Brieven' }),
    ).toBe('Brieven (vng · exchange.epistola.app)');
  });
});

describe('sortCatalogs', () => {
  it('offers the tenant’s own catalogs first, grouped by namespace', () => {
    const sorted = sortCatalogs([
      catalog('exchange.epistola.app', 'vng', 'a'),
      catalog('local', 'zaken', 'b'),
      catalog('local', 'valtimo', 'c'),
    ]);

    expect(sorted.map((c) => c.id)).toEqual([
      'local/valtimo/c',
      'local/zaken/b',
      'exchange.epistola.app/vng/a',
    ]);
  });
});
