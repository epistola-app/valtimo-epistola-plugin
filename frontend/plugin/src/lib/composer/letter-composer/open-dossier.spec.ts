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

import { readOpenDossierId } from './open-dossier';

describe('readOpenDossierId', () => {
  it('reads the dossier from a case route', () => {
    expect(
      readOpenDossierId(
        '/cases/correspondentie/document/da0e0dd2-6cae-4ed1-87cd-b24aa7a7c884/algemeen',
      ),
    ).toBe('da0e0dd2-6cae-4ed1-87cd-b24aa7a7c884');
  });

  it('reads it from the route without a trailing tab', () => {
    expect(readOpenDossierId('/cases/x/document/da0e0dd2-6cae-4ed1-87cd-b24aa7a7c884')).toBe(
      'da0e0dd2-6cae-4ed1-87cd-b24aa7a7c884',
    );
  });

  it('returns null where no dossier is open', () => {
    expect(readOpenDossierId('/cases/correspondentie')).toBeNull();
    expect(readOpenDossierId('/')).toBeNull();
    expect(readOpenDossierId(null)).toBeNull();
    expect(readOpenDossierId(undefined)).toBeNull();
  });

  it('ignores a path segment that is not an id', () => {
    expect(readOpenDossierId('/cases/x/document/not-a-uuid/algemeen')).toBeNull();
  });
});
