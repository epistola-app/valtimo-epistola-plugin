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

import { expressionFunctionSchemaSources } from './expression-function-schema';

describe('expression function schema sources', () => {
  it('flattens nested objects, arrays, required fields, descriptions, and nullability', () => {
    const sources = expressionFunctionSchemaSources([
      {
        name: 'inwonerplan',
        description: 'Loads the resident plan',
        overloads: [
          {
            arguments: [],
            returnType: 'Map',
            resultSchema: {
              type: 'object',
              required: ['inwoner'],
              properties: {
                inwoner: {
                  type: 'object',
                  description: 'Resident details',
                  required: ['naam'],
                  properties: {
                    naam: { type: 'string', description: 'Full name' },
                    roepnaam: { type: ['string', 'null'] },
                  },
                },
                activiteiten: {
                  type: 'array',
                  items: {
                    type: 'object',
                    properties: { titel: { type: 'string' } },
                  },
                },
              },
            },
          },
        ],
      },
    ]);

    expect(sources).toHaveLength(1);
    expect(sources[0].signature).toBe('$inwonerplan()');
    expect(sources[0].fields).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          path: 'inwoner',
          expression: '$inwonerplan().inwoner',
          required: true,
          expandable: true,
          description: 'Resident details',
        }),
        expect.objectContaining({
          path: 'inwoner.naam',
          expression: '$inwonerplan().inwoner.naam',
          required: true,
          description: 'Full name',
        }),
        expect.objectContaining({ path: 'inwoner.roepnaam', nullable: true }),
        expect.objectContaining({
          path: 'activiteiten',
          expression: '$inwonerplan().activiteiten',
          type: 'array<object>',
          expandable: true,
        }),
        expect.objectContaining({
          path: 'activiteiten[].titel',
          expression: '$inwonerplan().activiteiten[].titel',
          depth: 1,
        }),
      ]),
    );
  });

  it('resolves local definitions and keeps argument-taking overloads visible but non-insertable', () => {
    const [source] = expressionFunctionSchemaSources([
      {
        name: 'lookup',
        description: '',
        overloads: [
          {
            arguments: [{ name: 'id', type: 'String' }],
            returnType: 'Person',
            resultSchema: {
              type: 'object',
              properties: { person: { $ref: '#/$defs/person' } },
              $defs: {
                person: { type: 'object', properties: { name: { type: 'string' } } },
              },
            },
          },
        ],
      },
    ]);

    expect(source.signature).toBe('$lookup(id: String)');
    expect(source.fields).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ path: 'person', expandable: true, insertable: false }),
        expect.objectContaining({ path: 'person.name', insertable: false }),
      ]),
    );
  });

  it('ignores absent and boolean result schemas', () => {
    expect(
      expressionFunctionSchemaSources([
        {
          name: 'plain',
          description: '',
          overloads: [
            { arguments: [], returnType: 'String' },
            { arguments: [], returnType: 'Object', resultSchema: true },
          ],
        },
      ]),
    ).toEqual([]);
  });

  it('keeps alternative-only required fields optional', () => {
    const [source] = expressionFunctionSchemaSources([
      {
        name: 'choice',
        description: '',
        overloads: [
          {
            arguments: [],
            returnType: 'Object',
            resultSchema: {
              oneOf: [
                {
                  type: 'object',
                  required: ['personalName'],
                  properties: { personalName: { type: 'string' } },
                },
                {
                  type: 'object',
                  required: ['companyName'],
                  properties: { companyName: { type: 'string' } },
                },
              ],
            },
          },
        ],
      },
    ]);

    expect(source.fields).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ name: 'personalName', required: false }),
        expect.objectContaining({ name: 'companyName', required: false }),
      ]),
    );
  });

  it('preserves property boundaries for dots and other unsafe JSONata names', () => {
    const [source] = expressionFunctionSchemaSources([
      {
        name: 'unsafe',
        description: '',
        overloads: [
          {
            arguments: [],
            returnType: 'Object',
            resultSchema: {
              type: 'object',
              properties: {
                'address.city': {
                  type: 'object',
                  properties: { 'postal-code': { type: 'string' } },
                },
              },
            },
          },
        ],
      },
    ]);

    expect(source.fields[0]).toEqual(
      expect.objectContaining({
        expression: '$unsafe().`address.city`',
        pathSegments: [{ name: 'address.city' }],
      }),
    );
    expect(source.fields[1]).toEqual(
      expect.objectContaining({
        expression: '$unsafe().`address.city`.`postal-code`',
        pathSegments: [{ name: 'address.city' }, { name: 'postal-code' }],
      }),
    );
    expect(source.fields[0].id).not.toBe(source.fields[1].id);
  });

  it('stops recursive local references without dropping the recursive property', () => {
    const [source] = expressionFunctionSchemaSources([
      {
        name: 'tree',
        description: '',
        overloads: [
          {
            arguments: [],
            returnType: 'Node',
            resultSchema: {
              $ref: '#/$defs/node',
              $defs: {
                node: {
                  type: 'object',
                  properties: {
                    value: { type: 'string' },
                    next: { $ref: '#/$defs/node' },
                  },
                },
              },
            },
          },
        ],
      },
    ]);

    expect(source.fields.map((field) => field.path)).toEqual(['value', 'next']);
    expect(source.fields[1].expandable).toBe(false);
  });

  it('combines local reference fields with sibling fields', () => {
    const [source] = expressionFunctionSchemaSources([
      {
        name: 'person',
        description: '',
        overloads: [
          {
            arguments: [],
            returnType: 'Person',
            resultSchema: {
              type: 'object',
              properties: {
                person: {
                  $ref: '#/$defs/basePerson',
                  properties: { nickname: { type: 'string' } },
                  required: ['nickname'],
                },
              },
              $defs: {
                basePerson: {
                  type: 'object',
                  properties: { legalName: { type: 'string' } },
                  required: ['legalName'],
                },
              },
            },
          },
        ],
      },
    ]);

    expect(source.fields).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ path: 'person.legalName', required: true }),
        expect.objectContaining({ path: 'person.nickname', required: true }),
      ]),
    );
  });
});
