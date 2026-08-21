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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */

jest.mock('@angular/core', () => ({
  Component: () => (target: unknown) => target,
  Input: () => () => undefined,
  Output: () => () => undefined,
  EventEmitter: class {
    emit() {}
  },
}));
jest.mock('@angular/common', () => ({ CommonModule: class {} }));
jest.mock('@valtimo/plugin', () => ({ PluginTranslatePipeModule: class {} }));
jest.mock('./builder-field/builder-field.component', () => ({
  BuilderFieldComponent: class {},
}));

import { MappingBuilderComponent } from './mapping-builder.component';

describe('MappingBuilderComponent', () => {
  it('merges missing nested schema fields into a partial persisted mapping', () => {
    const component = new MappingBuilderComponent();
    component.expression = '{"customer": {"email": $doc.email}}';
    component.templateFields = [
      {
        name: 'customer',
        path: 'customer',
        type: 'object',
        fieldType: 'OBJECT',
        required: true,
        children: [
          {
            name: 'name',
            path: 'customer.name',
            type: 'string',
            fieldType: 'SCALAR',
            required: true,
          },
          {
            name: 'email',
            path: 'customer.email',
            type: 'string',
            fieldType: 'SCALAR',
            required: false,
          },
        ],
      },
    ];

    component.ngOnChanges({
      expression: {
        currentValue: component.expression,
        previousValue: '',
        firstChange: true,
        isFirstChange: () => true,
      },
      templateFields: {
        currentValue: component.templateFields,
        previousValue: [],
        firstChange: true,
        isFirstChange: () => true,
      },
    });

    expect(component.fields[0]).toMatchObject({
      name: 'customer',
      required: true,
      children: [
        { name: 'name', value: '', present: false, required: true },
        { name: 'email', value: '$doc.email', required: false },
      ],
    });
  });

  it('preserves a direct expression for a required object field', () => {
    const component = new MappingBuilderComponent();
    component.expression = '{"customer": $doc.customer}';
    component.templateFields = [
      {
        name: 'customer',
        path: 'customer',
        type: 'object',
        fieldType: 'OBJECT',
        required: true,
        children: [
          {
            name: 'name',
            path: 'customer.name',
            type: 'string',
            fieldType: 'SCALAR',
            required: true,
          },
        ],
      },
    ];

    component.ngOnChanges({
      expression: {
        currentValue: component.expression,
        previousValue: '',
        firstChange: true,
        isFirstChange: () => true,
      },
      templateFields: {
        currentValue: component.templateFields,
        previousValue: [],
        firstChange: true,
        isFirstChange: () => true,
      },
    });

    expect(component.fields[0]).toEqual({
      name: 'customer',
      mode: 'ref',
      value: '$doc.customer',
      required: true,
    });
  });
});
