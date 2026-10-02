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

import { problemWith, rulesFrom, storedFrom, type WriteBackRule } from './write-back';

describe('rulesFrom', () => {
  it('reads the stored map as rows', () => {
    expect(rulesFrom({ 'doc:/aanvrager/telefoon': '$inputs.telefoon' })).toEqual([
      { destination: 'doc:/aanvrager/telefoon', expression: '$inputs.telefoon' },
    ]);
  });

  it('survives anything that is not a map', () => {
    // A form authored by hand, or by an older plugin, may hold anything here.
    expect(rulesFrom(null)).toEqual([]);
    expect(rulesFrom(undefined)).toEqual([]);
    expect(rulesFrom('doc:/x')).toEqual([]);
    expect(rulesFrom(['doc:/x'])).toEqual([]);
  });

  it('keeps a destination whose value is not a string, so it can be corrected', () => {
    expect(rulesFrom({ 'doc:/x': 42 })).toEqual([{ destination: 'doc:/x', expression: '' }]);
  });
});

describe('storedFrom', () => {
  it('stores complete rules as a destination-keyed map', () => {
    expect(
      storedFrom([
        { destination: 'doc:/a', expression: '$inputs.a' },
        { destination: 'pv:b', expression: '$data.b' },
      ]),
    ).toEqual({ 'doc:/a': '$inputs.a', 'pv:b': '$data.b' });
  });

  it('drops a rule that is only half written', () => {
    // The backend drops these too, with a warning. Storing one would be a form that looks
    // configured and writes nothing.
    expect(
      storedFrom([
        { destination: 'doc:/a', expression: '' },
        { destination: '', expression: '$inputs.b' },
      ]),
    ).toBeNull();
  });

  it('trims, so a stray space does not become part of a case path', () => {
    expect(storedFrom([{ destination: ' doc:/a ', expression: ' $inputs.a ' }])).toEqual({
      'doc:/a': '$inputs.a',
    });
  });

  it('stores nothing rather than an empty map when there are no rules', () => {
    // So a composer with no write-back has no `writeBack` key at all.
    expect(storedFrom([])).toBeNull();
    expect(storedFrom([{ destination: '', expression: '' }])).toBeNull();
  });
});

describe('problemWith', () => {
  const only = (rule: WriteBackRule) => problemWith(rule, 0, [rule]);

  it('accepts a case path and a process variable', () => {
    expect(
      only({ destination: 'doc:/aanvrager/telefoon', expression: '$inputs.telefoon' }),
    ).toEqual({
      destination: null,
      expression: null,
    });
    expect(only({ destination: 'pv:someValue', expression: '$data.some.property' })).toEqual({
      destination: null,
      expression: null,
    });
  });

  it('refuses a destination with no resolver prefix', () => {
    // Without one Valtimo has nobody to hand the value to, and the rule would do nothing at all.
    expect(only({ destination: 'aanvrager/telefoon', expression: '$inputs.x' }).destination).toBe(
      'writeBackDestinationInvalid',
    );
    expect(only({ destination: 'case:/x', expression: '$inputs.x' }).destination).toBe(
      'writeBackDestinationInvalid',
    );
  });

  it('refuses a doc: destination that is not a pointer', () => {
    expect(
      only({ destination: 'doc:aanvrager.telefoon', expression: '$inputs.x' }).destination,
    ).toBe('writeBackDestinationInvalid');
  });

  it('reports a second rule for the same destination', () => {
    // One writer per case path: two rules have no defined order, and the backend keeps the first.
    const rules: WriteBackRule[] = [
      { destination: 'doc:/a', expression: '$inputs.one' },
      { destination: 'doc:/a', expression: '$inputs.two' },
    ];

    expect(problemWith(rules[0]!, 0, rules).destination).toBeNull();
    expect(problemWith(rules[1]!, 1, rules).destination).toBe('writeBackDestinationDuplicate');
  });

  it('reports an expression that does not parse', () => {
    expect(only({ destination: 'doc:/a', expression: '$inputs.(' }).expression).toBe(
      'writeBackExpressionInvalid',
    );
  });

  it('reports each half of a rule the other half needs', () => {
    expect(only({ destination: '', expression: '$inputs.a' }).destination).toBe(
      'writeBackDestinationMissing',
    );
    expect(only({ destination: 'doc:/a', expression: '' }).expression).toBe(
      'writeBackExpressionMissing',
    );
  });

  it('says nothing about an untouched row', () => {
    // Adding a row should not immediately look like a mistake.
    expect(only({ destination: '', expression: '' })).toEqual({
      destination: null,
      expression: null,
    });
  });
});
