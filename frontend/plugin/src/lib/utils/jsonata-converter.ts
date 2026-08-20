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
import { parseSimpleJsonataExpression } from '../components/smart-expression-editor/simple-jsonata-expression';

const jsonata = (_jsonata as any).default || _jsonata;

/**
 * A statically named field in the Simple data-mapping object.
 *
 * Complex leaf values remain raw JSONata and are handled by the field-level
 * smart editor. `present: false` identifies a template-schema placeholder that
 * must stay omitted until an author gives it a value.
 */
export interface BuilderField {
  name: string;
  mode: 'ref' | 'raw';
  value: string;
  children?: BuilderField[];
  present?: boolean;
  required?: boolean;
}

interface ObjectSourceEntry {
  name: string;
  value: string;
}

/**
 * Parse a top-level JSONata object constructor without reconstructing its
 * values from the AST. The source text of every leaf is retained exactly, so
 * opening Simple mode cannot silently change quote style or complex syntax.
 */
export function parseJsonataToBuilder(expression: string): BuilderField[] {
  if (!expression || !expression.trim()) {
    return [];
  }

  try {
    jsonata(expression);
  } catch {
    return [{ name: '_root', mode: 'raw', value: expression }];
  }

  const entries = parseStaticObject(expression);
  if (!entries) {
    return [{ name: '_root', mode: 'raw', value: expression }];
  }
  return entries.map(toBuilderField);
}

export function builderToJsonata(fields: BuilderField[]): string {
  if (fields.length === 0) {
    return '';
  }
  if (fields.length === 1 && fields[0].name === '_root' && fields[0].mode === 'raw') {
    return fields[0].value;
  }

  const entries = fields
    .map((field) => formatFieldEntry(field))
    .filter((entry): entry is string => !!entry);
  return entries.length > 0 ? `{\n${entries.join(',\n')}\n}` : '{}';
}

/**
 * Whole-mapping Simple mode supports static object structure. Individual leaf
 * expressions may be arbitrarily complex because their own editor falls back
 * to its validated raw mode.
 */
export function isBuilderCompatible(expression: string): boolean {
  if (!expression || !expression.trim()) {
    return true;
  }
  const fields = parseJsonataToBuilder(expression);
  return !(fields.length === 1 && fields[0].name === '_root' && fields[0].mode === 'raw');
}

export function isJsonataExpressionValid(expression: string): boolean {
  if (!expression.trim()) {
    return false;
  }
  try {
    jsonata(expression);
    return true;
  } catch {
    return false;
  }
}

function toBuilderField(entry: ObjectSourceEntry): BuilderField {
  const children = parseStaticObject(entry.value);
  if (children) {
    return {
      name: entry.name,
      mode: 'ref',
      value: '',
      children: children.map(toBuilderField),
    };
  }

  return {
    name: entry.name,
    mode: parseSimpleJsonataExpression(entry.value).representable ? 'ref' : 'raw',
    value: entry.value,
  };
}

function formatFieldEntry(field: BuilderField, indent = '  '): string | null {
  if (field.children) {
    const childEntries = field.children
      .map((child) => formatFieldEntry(child, `${indent}  `))
      .filter((entry): entry is string => !!entry);
    if (field.present === false && childEntries.length === 0) {
      return null;
    }
    return `${indent}${JSON.stringify(field.name)}: {${
      childEntries.length > 0 ? `\n${childEntries.join(',\n')}\n${indent}` : ''
    }}`;
  }

  if (!field.value?.trim()) {
    return null;
  }
  return `${indent}${JSON.stringify(field.name)}: ${field.value}`;
}

/**
 * A small concrete-syntax scanner for static JSONata object constructors.
 * JSONata still performs syntax validation; this scanner only identifies key
 * and value source ranges while respecting nested syntax and quoted text.
 */
function parseStaticObject(source: string): ObjectSourceEntry[] | null {
  const start = firstNonWhitespace(source, 0);
  if (source[start] !== '{') {
    return null;
  }

  const entries: ObjectSourceEntry[] = [];
  const names = new Set<string>();
  let index = firstNonWhitespace(source, start + 1);
  if (source[index] === '}' && firstNonWhitespace(source, index + 1) === source.length) {
    return entries;
  }

  while (index < source.length) {
    const keyToken = readQuotedToken(source, index);
    if (!keyToken || keyToken.quote === '`') {
      return null;
    }

    let name: string;
    try {
      const keyAst = (jsonata(keyToken.source) as any).ast();
      if (keyAst?.type !== 'string' || typeof keyAst.value !== 'string') {
        return null;
      }
      name = keyAst.value;
    } catch {
      return null;
    }
    if (names.has(name)) {
      return null;
    }
    names.add(name);

    index = firstNonWhitespace(source, keyToken.end);
    if (source[index] !== ':') {
      return null;
    }
    index = firstNonWhitespace(source, index + 1);

    const valueEnd = findObjectValueEnd(source, index);
    if (!valueEnd) {
      return null;
    }
    const value = source.slice(index, valueEnd.index).trim();
    if (!value) {
      return null;
    }
    entries.push({ name, value });

    if (valueEnd.delimiter === '}') {
      return firstNonWhitespace(source, valueEnd.index + 1) === source.length ? entries : null;
    }
    index = firstNonWhitespace(source, valueEnd.index + 1);
  }

  return null;
}

function findObjectValueEnd(
  source: string,
  start: number,
): { index: number; delimiter: ',' | '}' } | null {
  let quote: "'" | '"' | '`' | null = null;
  let escaped = false;
  let regex = false;
  let regexCharacterClass = false;
  let roundDepth = 0;
  let squareDepth = 0;
  let curlyDepth = 0;

  for (let index = start; index < source.length; index++) {
    const character = source[index];
    if (regex) {
      if (escaped) {
        escaped = false;
      } else if (character === '\\') {
        escaped = true;
      } else if (character === '[') {
        regexCharacterClass = true;
      } else if (character === ']') {
        regexCharacterClass = false;
      } else if (character === '/' && !regexCharacterClass) {
        regex = false;
      }
      continue;
    }
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
    if (character === '/' && startsRegexLiteral(source, index, start)) {
      regex = true;
      regexCharacterClass = false;
      continue;
    }
    if (character === '(') roundDepth++;
    if (character === ')') roundDepth--;
    if (character === '[') squareDepth++;
    if (character === ']') squareDepth--;
    if (character === '{') curlyDepth++;
    if (character === '}') {
      if (curlyDepth === 0 && roundDepth === 0 && squareDepth === 0) {
        return { index, delimiter: '}' };
      }
      curlyDepth--;
    }
    if (character === ',' && roundDepth === 0 && squareDepth === 0 && curlyDepth === 0) {
      return { index, delimiter: ',' };
    }
    if (roundDepth < 0 || squareDepth < 0 || curlyDepth < 0) {
      return null;
    }
  }
  return null;
}

function startsRegexLiteral(source: string, index: number, valueStart: number): boolean {
  let previous = index - 1;
  while (previous >= valueStart && /\s/.test(source[previous])) {
    previous--;
  }
  return previous < valueStart || '(:,[=!?&|+-*%;<>'.includes(source[previous]);
}

function readQuotedToken(
  source: string,
  start: number,
): { source: string; end: number; quote: "'" | '"' | '`' } | null {
  const quote = source[start];
  if (quote !== "'" && quote !== '"' && quote !== '`') {
    return null;
  }
  let escaped = false;
  for (let index = start + 1; index < source.length; index++) {
    const character = source[index];
    if (escaped) {
      escaped = false;
    } else if (character === '\\' && quote !== '`') {
      escaped = true;
    } else if (character === quote) {
      return { source: source.slice(start, index + 1), end: index + 1, quote };
    }
  }
  return null;
}

function firstNonWhitespace(source: string, start: number): number {
  let index = start;
  while (index < source.length && /\s/.test(source[index])) {
    index++;
  }
  return index;
}
