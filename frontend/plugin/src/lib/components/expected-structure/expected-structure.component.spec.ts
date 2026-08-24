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

jest.mock('@angular/core', () => ({
  Component: () => (target: unknown) => target,
  Input: () => () => undefined,
}));
jest.mock('@angular/common', () => ({ CommonModule: class {} }));
jest.mock('@valtimo/plugin', () => ({ PluginTranslatePipeModule: class {} }));

import { ExpectedStructureComponent } from './expected-structure.component';

describe('ExpectedStructureComponent', () => {
  it('shows resolved complex metadata and preserves the complete raw schema', () => {
    const component = new ExpectedStructureComponent();
    component.templateFields = [
      {
        name: 'subject',
        path: 'subject',
        type: 'oneOf<person | organization>',
        fieldType: 'OBJECT',
        required: true,
        complex: true,
      },
      {
        name: 'address',
        path: 'address',
        type: 'object',
        fieldType: 'OBJECT',
        required: false,
        nullable: true,
        children: [
          {
            name: 'city',
            path: 'address.city',
            type: 'string',
            fieldType: 'SCALAR',
            required: true,
          },
        ],
      },
    ];
    component.schema = {
      type: 'object',
      $defs: { person: { type: 'object' } },
      properties: {
        subject: { oneOf: [{ $ref: '#/$defs/person' }, { type: 'object' }] },
      },
    };

    component.ngOnChanges({
      templateFields: {
        currentValue: component.templateFields,
        previousValue: [],
        firstChange: true,
        isFirstChange: () => true,
      },
      schema: {
        currentValue: component.schema,
        previousValue: null,
        firstChange: true,
        isFirstChange: () => true,
      },
    });

    expect(component.structureText).toContain('oneOf<person | organization> (required) ⚠');
    expect(component.structureText).toContain('(nullable)');
    expect(component.rawSchemaText).toContain('"$defs"');
    expect(component.rawSchemaText).toContain('"oneOf"');
  });
});
