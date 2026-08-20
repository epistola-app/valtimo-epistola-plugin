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

import {
  encodeSingleQuotedJsonataString,
  functionReferenceExpressionSegment,
  parseSimpleJsonataExpression,
  referenceExpressionSegment,
  serializeSimpleJsonataExpression,
  serializeSimpleJsonataSegments,
  textExpressionSegment,
  typedExpressionSegment,
} from './simple-jsonata-expression';

describe('simple JSONata expression model', () => {
  it.each([
    ['"letter.pdf"', 'letter.pdf'],
    ["'letter.pdf'", 'letter.pdf'],
    ['42', 42],
    ['-42.5', -42.5],
    ['true', true],
    ['false', false],
    ['null', null],
  ])('parses the supported literal %s', (source, expected) => {
    const result = parseSimpleJsonataExpression(source);

    expect(result.representable).toBe(true);
    expect(result.expression?.segments[0]).toMatchObject(
      typeof expected === 'string'
        ? { kind: 'text', value: expected }
        : expected === null
          ? { kind: 'typed', valueType: 'null', value: null }
          : {
              kind: 'typed',
              valueType: typeof expected === 'boolean' ? 'boolean' : 'number',
              value: expected,
            },
    );
  });

  it('parses references and mixed concatenation', () => {
    const source = ` "prefix-"  & $doc.person.\`last-name\` & $pv.sequence & '.pdf' `;
    const result = parseSimpleJsonataExpression(source);

    expect(result.expression?.segments).toEqual([
      textExpressionSegment('prefix-'),
      referenceExpressionSegment('doc', 'person.`last-name`'),
      referenceExpressionSegment('pv', 'sequence'),
      textExpressionSegment('.pdf'),
    ]);
    expect(serializeSimpleJsonataExpression(result.expression!)).toBe(source);
  });

  it('supports array-preserving and numeric-index reference paths', () => {
    expect(parseSimpleJsonataExpression('$doc.items[].name').representable).toBe(true);
    expect(parseSimpleJsonataExpression('$doc.items[0].name').representable).toBe(true);
    expect(parseSimpleJsonataExpression('$external.payload').expression?.segments).toEqual([
      referenceExpressionSegment('external', 'payload'),
    ]);
    expect(parseSimpleJsonataExpression('$paymentReference').expression?.segments).toEqual([
      referenceExpressionSegment('paymentReference', ''),
    ]);
  });

  it('parses and serializes schema-backed zero-argument function references', () => {
    const result = parseSimpleJsonataExpression('$inwonerplan().activiteiten[].titel');

    expect(result.expression?.segments).toEqual([
      functionReferenceExpressionSegment('inwonerplan', 'activiteiten[].titel'),
    ]);
    expect(
      serializeSimpleJsonataSegments([
        functionReferenceExpressionSegment('inwonerplan', 'activiteiten'),
      ]),
    ).toBe('$inwonerplan().activiteiten');
  });

  it('serializes schema paths without splitting dots inside property names', () => {
    expect(
      serializeSimpleJsonataSegments([
        functionReferenceExpressionSegment(
          'lookup',
          'address.city.postal-code',
          '`address.city`.`postal-code`',
        ),
      ]),
    ).toBe('$lookup().`address.city`.`postal-code`');
  });

  it.each([
    'value.pdf',
    '$uppercase($doc.name)',
    '$doc.amount + 1',
    '$doc.enabled ? "yes" : "no"',
    '($doc.name & ".pdf")',
    '{"name": $doc.name}',
    '$doc.items[$pv.index].name',
    '$lookup().items[$pv.index].name',
  ])('leaves unsupported expression %s in Advanced mode', (source) => {
    expect(parseSimpleJsonataExpression(source).representable).toBe(false);
  });

  it('returns the JSONata syntax error for invalid source', () => {
    const result = parseSimpleJsonataExpression('$doc.[broken');

    expect(result.representable).toBe(false);
    expect(result.error).toBeTruthy();
  });

  it('preserves an untouched expression exactly and canonicalizes edited segments', () => {
    const result = parseSimpleJsonataExpression(`"prefix-"&$doc.name`);

    expect(serializeSimpleJsonataExpression(result.expression!)).toBe(`"prefix-"&$doc.name`);
    expect(
      serializeSimpleJsonataExpression({
        ...result.expression!,
        segments: [textExpressionSegment('new-'), referenceExpressionSegment('doc', 'name')],
        dirty: true,
      }),
    ).toBe(`'new-' & $doc.name`);
  });

  it('always serializes ordinary typed text as a string instead of detecting an expression', () => {
    expect(serializeSimpleJsonataSegments([textExpressionSegment('$pv.filename')])).toBe(
      "'$pv.filename'",
    );
    expect(serializeSimpleJsonataSegments([textExpressionSegment('value.pdf')])).toBe(
      "'value.pdf'",
    );
  });

  it('serializes explicit typed values and references', () => {
    expect(
      serializeSimpleJsonataSegments([
        typedExpressionSegment('number', 12.5),
        typedExpressionSegment('boolean', false),
        typedExpressionSegment('null', null),
        referenceExpressionSegment('case', ''),
      ]),
    ).toBe('12.5 & false & null & $case');
  });

  it('escapes new single-quoted strings without rewriting printable characters', () => {
    expect(encodeSingleQuotedJsonataString("O'Brien\\draft\n\t\u0001")).toBe(
      "'O\\'Brien\\\\draft\\n\\t\\u0001'",
    );
  });
});
