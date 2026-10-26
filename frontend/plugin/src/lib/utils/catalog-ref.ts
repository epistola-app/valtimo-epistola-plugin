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

/**
 * The catalog in `catalogs` that a stored catalog reference names, by the id it is listed under.
 *
 * Contract 2.0 names a catalog by registry, namespace and key, and the configurators store
 * `registry/namespace/key`. A process link saved against contract 1.x holds the key alone, and a
 * hand-written one may hold `namespace/key`. Such a reference is matched like the backend matches
 * it (`CatalogRef`), so the catalog it names shows as selected: exactly one catalog must match, and
 * re-saving the link then stores it in full. When none or several match, the reference is returned
 * unchanged — the select shows nothing chosen, and choosing one fixes the link.
 */
export function matchCatalogId(
  stored: string | null | undefined,
  catalogs: readonly CatalogInfo[],
): string | null {
  if (!stored) {
    return null;
  }
  if (catalogs.some((catalog) => catalog.id === stored)) {
    return stored;
  }
  const parts = stored.split('/');
  if (parts.length > 3 || parts.some((part) => part.length === 0)) {
    return stored;
  }
  const key = parts[parts.length - 1];
  const namespace = parts.length >= 2 ? parts[parts.length - 2] : null;
  const registry = parts.length === 3 ? parts[0] : null;
  const matching = catalogs.filter(
    (catalog) =>
      catalog.key === key &&
      (namespace === null || catalog.namespace === namespace) &&
      (registry === null || catalog.registry === registry),
  );
  return matching.length === 1 ? matching[0].id : stored;
}
