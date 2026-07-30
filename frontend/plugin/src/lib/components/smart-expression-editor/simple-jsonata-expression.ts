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

import * as _jsonata from 'jsonata';
import { renderJsonataPath } from '../../utils/jsonata-path';

const jsonata = (_jsonata as any).default || _jsonata;

export type SimpleExpressionSegment =
  | TextExpressionSegment
  | ReferenceExpressionSegment
  | TypedExpressionSegment;

export interface TextExpressionSegment {
  kind: 'text';
  value: string;
}

export interface ReferenceExpressionSegment {
  kind: 'reference';
  variable: string;
  path: string;
}

export interface TypedExpressionSegment {
  kind: 'typed';
  valueType: 'number' | 'boolean' | 'null';
  value: number | boolean | null;
}

export interface SimpleJsonataExpression {
  segments: SimpleExpressionSegment[];
  /**
   * The exact persisted expression. It is returned verbatim until the user
   * changes the visual model, preserving whitespace and quote style.
   */
  originalSource?: string;
  dirty: boolean;
}

export interface SimpleExpressionParseResult {
  representable: boolean;
  expression?: SimpleJsonataExpression;
  error?: string;
}

/**
 * Parse the deliberately small JSONata subset supported by the visual editor.
 *
 * This is only used for already-stored JSONata. User input in Simple mode is
 * never passed through this classifier: ordinary typing always creates text.
 */
export function parseSimpleJsonataExpression(
  source: string | null | undefined,
): SimpleExpressionParseResult {
  const expressionSource = source ?? '';
  if (!expressionSource.trim()) {
    return {
      representable: true,
      expression: { segments: [], originalSource: expressionSource, dirty: false },
    };
  }

  try {
    jsonata(expressionSource);
  } catch (error: any) {
    return {
      representable: false,
      error: error?.message || 'Invalid JSONata expression',
    };
  }

  const termSources = splitTopLevelConcatenation(expressionSource);
  if (!termSources || termSources.length === 0) {
    return { representable: false };
  }

  const segments: SimpleExpressionSegment[] = [];
  for (const termSource of termSources) {
    const segment = parseTerm(termSource);
    if (!segment) {
      return { representable: false };
    }
    segments.push(segment);
  }

  return {
    representable: true,
    expression: {
      segments,
      originalSource: expressionSource,
      dirty: false,
    },
  };
}

export function serializeSimpleJsonataExpression(expression: SimpleJsonataExpression): string {
  if (!expression.dirty && expression.originalSource !== undefined) {
    return expression.originalSource;
  }

  return expression.segments
    .filter((segment) => segment.kind !== 'text' || segment.value.length > 0)
    .map(serializeSegment)
    .join(' & ');
}

export function serializeSimpleJsonataSegments(segments: SimpleExpressionSegment[]): string {
  return serializeSimpleJsonataExpression({ segments, dirty: true });
}

export function textExpressionSegment(value: string): TextExpressionSegment {
  return { kind: 'text', value };
}

export function referenceExpressionSegment(
  variable: string,
  path: string,
): ReferenceExpressionSegment {
  return { kind: 'reference', variable, path };
}

export function typedExpressionSegment(
  valueType: TypedExpressionSegment['valueType'],
  value: number | boolean | null,
): TypedExpressionSegment {
  return { kind: 'typed', valueType, value };
}

export function encodeSingleQuotedJsonataString(value: string): string {
  let encoded = "'";
  for (const character of value) {
    switch (character) {
      case "'":
        encoded += "\\'";
        break;
      case '\\':
        encoded += '\\\\';
        break;
      case '\b':
        encoded += '\\b';
        break;
      case '\f':
        encoded += '\\f';
        break;
      case '\n':
        encoded += '\\n';
        break;
      case '\r':
        encoded += '\\r';
        break;
      case '\t':
        encoded += '\\t';
        break;
      default: {
        const codePoint = character.codePointAt(0)!;
        encoded += codePoint < 0x20 ? `\\u${codePoint.toString(16).padStart(4, '0')}` : character;
      }
    }
  }
  return `${encoded}'`;
}

function parseTerm(source: string): SimpleExpressionSegment | null {
  const trimmed = source.trim();
  if (!trimmed) {
    return null;
  }

  let ast: any;
  try {
    ast = (jsonata(trimmed) as any).ast();
  } catch {
    return null;
  }

  if (ast.type === 'string') {
    return textExpressionSegment(ast.value);
  }
  if (ast.type === 'number' && typeof ast.value === 'number' && Number.isFinite(ast.value)) {
    return typedExpressionSegment('number', ast.value);
  }
  if (ast.type === 'value' && typeof ast.value === 'boolean') {
    return typedExpressionSegment('boolean', ast.value);
  }
  if (ast.type === 'value' && ast.value === null) {
    return typedExpressionSegment('null', null);
  }

  const reference = parseReference(ast, trimmed);
  return reference;
}

function parseReference(ast: any, source: string): ReferenceExpressionSegment | null {
  if (ast.type === 'variable') {
    return referenceExpressionSegment(ast.value, '');
  }
  if (
    ast.type !== 'path' ||
    !Array.isArray(ast.steps) ||
    ast.steps.length < 2 ||
    ast.steps[0]?.type !== 'variable'
  ) {
    return null;
  }

  if (!ast.steps.slice(1).every(isSimplePathStep)) {
    return null;
  }

  const variable = ast.steps[0].value as string;
  const prefix = `$${variable}.`;
  if (!source.startsWith(prefix)) {
    return null;
  }
  return referenceExpressionSegment(variable, source.slice(prefix.length));
}

function isSimplePathStep(step: any): boolean {
  if (step?.type !== 'name') {
    return false;
  }
  if (!step.stages) {
    return true;
  }
  return step.stages.every(
    (stage: any) =>
      stage?.type === 'filter' &&
      stage.expr?.type === 'number' &&
      Number.isInteger(stage.expr.value) &&
      stage.expr.value >= 0,
  );
}

function serializeSegment(segment: SimpleExpressionSegment): string {
  switch (segment.kind) {
    case 'text':
      return encodeSingleQuotedJsonataString(segment.value);
    case 'reference':
      return segment.path
        ? renderJsonataPath(segment.variable, segment.path)
        : `$${segment.variable}`;
    case 'typed':
      if (segment.valueType === 'null') {
        return 'null';
      }
      return String(segment.value);
  }
}

/**
 * Split only on top-level JSONata concatenation operators. Operators nested in
 * strings, quoted property names, predicates, objects, arrays, or parentheses
 * remain part of a term and therefore make that term fall back to Advanced.
 */
function splitTopLevelConcatenation(source: string): string[] | null {
  const terms: string[] = [];
  let start = 0;
  let quote: "'" | '"' | '`' | null = null;
  let escaped = false;
  let roundDepth = 0;
  let squareDepth = 0;
  let curlyDepth = 0;

  for (let index = 0; index < source.length; index++) {
    const character = source[index];
    if (quote) {
      if (escaped) {
        escaped = false;
      } else if (character === '\\' && quote !== '`') {
        escaped = true;
      } else if (character === quote) {
        quote = null;
      }
      continue;
    }

    if (character === "'" || character === '"' || character === '`') {
      quote = character;
      continue;
    }
    if (character === '(') roundDepth++;
    if (character === ')') roundDepth--;
    if (character === '[') squareDepth++;
    if (character === ']') squareDepth--;
    if (character === '{') curlyDepth++;
    if (character === '}') curlyDepth--;

    if (roundDepth < 0 || squareDepth < 0 || curlyDepth < 0) {
      return null;
    }
    if (character === '&' && roundDepth === 0 && squareDepth === 0 && curlyDepth === 0) {
      terms.push(source.slice(start, index));
      start = index + 1;
    }
  }

  if (quote || roundDepth !== 0 || squareDepth !== 0 || curlyDepth !== 0) {
    return null;
  }
  terms.push(source.slice(start));
  return terms.every((term) => term.trim().length > 0) ? terms : null;
}
