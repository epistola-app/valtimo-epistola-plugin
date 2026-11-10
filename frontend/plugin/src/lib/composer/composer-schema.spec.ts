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

import { COMPOSER_SCHEMA_VERSION, migrateLetterSetToExpressions } from './composer-schema';

/**
 * Schema 4 made the letter set's environment and ranges JSONata (ADR 0009). The values and the
 * version must move together: either alone reads `"acceptatie"` as an environment named with its
 * quotes, or `acceptatie` as a path to nothing.
 */
describe('migrateLetterSetToExpressions', () => {
  it('turns plain values into the literals they meant, and says it did', () => {
    expect(
      migrateLetterSetToExpressions({
        schemaVersion: 3,
        dataMapping: '{}',
        letterSet: {
          pluginConfigurationId: 'config',
          environmentId: 'acceptatie',
          catalogId: 'gemeente',
          versionRange: '^2.0.0',
          templates: [
            { templateId: 'besluit' },
            { templateId: 'aanmaning', versionRange: ' 2.1.0 ' },
          ],
        },
      }),
    ).toEqual({
      schemaVersion: 4,
      dataMapping: '{}',
      letterSet: {
        pluginConfigurationId: 'config',
        environmentId: '"acceptatie"',
        catalogId: 'gemeente',
        versionRange: '"^2.0.0"',
        templates: [
          { templateId: 'besluit' },
          { templateId: 'aanmaning', versionRange: '"2.1.0"' },
        ],
      },
    });
  });

  it('reads an absent version as 1, and escapes what a literal must', () => {
    expect(migrateLetterSetToExpressions({ letterSet: { environmentId: 'pro"d' } })).toEqual({
      schemaVersion: 4,
      letterSet: { environmentId: '"pro\\"d"' },
    });
  });

  it('leaves settings already at 4 exactly as they are', () => {
    const current = { schemaVersion: 4, letterSet: { environmentId: '$pv.omgeving' } };

    expect(migrateLetterSetToExpressions(current)).toBe(current);
  });

  it('is what the composer writes now', () => {
    expect(COMPOSER_SCHEMA_VERSION).toBe(4);
  });
});
