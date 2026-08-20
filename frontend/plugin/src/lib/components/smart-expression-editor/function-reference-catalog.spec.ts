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

import { ExpressionFunctionInfo } from '../../models';
import { FunctionReferenceCatalog } from './function-reference-catalog';

function functions(): ExpressionFunctionInfo[] {
  return [
    {
      name: 'customData',
      description: 'Custom data',
      overloads: [
        {
          arguments: [],
          returnType: 'Map',
          resultSchema: {
            type: 'object',
            properties: {
              customer: {
                type: 'object',
                properties: { name: { type: 'string' } },
              },
              status: { type: 'string' },
            },
          },
        },
        {
          arguments: [{ name: 'id', type: 'String' }],
          returnType: 'Map',
          schemaDiagnostic: { code: 'BROKEN', message: 'Broken schema' },
        },
      ],
    },
  ];
}

describe('FunctionReferenceCatalog', () => {
  it('owns expansion, search, insertion, and diagnostic state', () => {
    const catalog = new FunctionReferenceCatalog();
    catalog.update(functions());

    const collapsed = catalog.groups('');
    expect(collapsed).toBe(catalog.groups(''));
    expect(collapsed[0].options.map((option) => option.label)).toEqual(['customer', 'status']);
    expect(catalog.diagnostics).toEqual([
      { signature: '$customData(id: String)', message: 'Broken schema' },
    ]);

    catalog.toggle(collapsed[0].options[0].schemaField.id);
    expect(catalog.groups('')[0].options.map((option) => option.label)).toEqual([
      'customer',
      'customer.name',
      'status',
    ]);
    expect(catalog.groups('name')[0].options[0]).toEqual(
      expect.objectContaining({
        expression: '$customData().customer.name',
        insertable: true,
      }),
    );
  });

  it('drops expansion state that no longer exists after metadata changes', () => {
    const catalog = new FunctionReferenceCatalog();
    catalog.update(functions());
    catalog.toggle(catalog.groups('')[0].options[0].schemaField.id);

    catalog.update([]);
    catalog.update(functions());

    expect(catalog.groups('')[0].options.map((option) => option.label)).toEqual([
      'customer',
      'status',
    ]);
  });
});
