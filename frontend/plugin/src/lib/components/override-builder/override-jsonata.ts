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

import * as _jsonata from 'jsonata';
const jsonata = (_jsonata as any).default || _jsonata;

/**
 * A single row in the override builder's simple table: a form field feeding a
 * `doc`/`pv` input path during preview.
 */
export interface OverrideRow {
  scope: 'doc' | 'pv';
  inputPath: string;
  formFieldKey: string;
}

const BARE_IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_]*$/;

/**
 * Render a `$form` reference for a form-field key. Keys that aren't bare
 * identifiers (e.g. `pv:motivation`) are backtick-quoted so JSONata treats the
 * whole key as a single property name — matching the old flat `formData[key]`
 * lookup rather than a nested path traversal.
 */
function formRef(key: string): string {
  return BARE_IDENTIFIER.test(key) ? `$form.${key}` : '$form.`' + key + '`';
}

/** Branch node = nested object; leaf = a JSONata expression string. */
type Tree = { [segment: string]: string | Tree };

function insert(tree: Tree, segments: string[], leaf: string): void {
  let node = tree;
  for (let i = 0; i < segments.length - 1; i++) {
    const seg = segments[i];
    if (typeof node[seg] !== 'object') {
      node[seg] = {};
    }
    node = node[seg] as Tree;
  }
  node[segments[segments.length - 1]] = leaf;
}

function emit(node: Tree | string, indent: string): string {
  if (typeof node === 'string') {
    return node;
  }
  const inner = indent + '  ';
  const entries = Object.entries(node).map(
    ([key, value]) => `${inner}"${key}": ${emit(value, inner)}`,
  );
  return `{\n${entries.join(',\n')}\n${indent}}`;
}

/**
 * Serialize simple-table rows into a JSONata expression that maps `$form` onto
 * a `{ doc, pv }` overlay. Dot-notation input paths expand into nested object
 * literals (so `beslissing.tekst` becomes `{ "beslissing": { "tekst": ... } }`),
 * preserving the legacy override semantics.
 */
export function serializeOverrideRows(rows: OverrideRow[]): string {
  const scopes: Tree = {};
  for (const row of rows) {
    if (!row.inputPath || !row.formFieldKey) continue;
    if (row.scope !== 'doc' && row.scope !== 'pv') continue;
    if (typeof scopes[row.scope] !== 'object') {
      scopes[row.scope] = {};
    }
    insert(scopes[row.scope] as Tree, row.inputPath.split('.'), formRef(row.formFieldKey));
  }
  if (Object.keys(scopes).length === 0) {
    return '';
  }
  return emit(scopes, '');
}

/**
 * Parse a JSONata override expression back into simple-table rows, or `null`
 * when the expression is richer than the simple table can represent (anything
 * beyond `doc`/`pv` objects whose leaves are plain `$form.<key>` references).
 * A `null` result is the builder's signal to fall back to advanced mode.
 */
export function parseOverrideJsonata(expression: string): OverrideRow[] | null {
  if (!expression || !expression.trim()) {
    return [];
  }
  let ast: any;
  try {
    ast = (jsonata(expression) as any).ast();
  } catch {
    return null;
  }
  if (!(ast?.type === 'unary' && ast.value === '{')) {
    return null;
  }
  const rows: OverrideRow[] = [];
  for (const entry of ast.lhs || []) {
    const scope = entry?.[0]?.value;
    const valueNode = entry?.[1];
    if (scope !== 'doc' && scope !== 'pv') {
      return null;
    }
    if (!(valueNode?.type === 'unary' && valueNode.value === '{')) {
      return null;
    }
    if (!collectLeaves(valueNode.lhs || [], [], scope, rows)) {
      return null;
    }
  }
  return rows;
}

function collectLeaves(
  entries: any[][],
  prefix: string[],
  scope: 'doc' | 'pv',
  rows: OverrideRow[],
): boolean {
  for (const [keyNode, valueNode] of entries) {
    const segment = keyNode?.value;
    if (typeof segment !== 'string') {
      return false;
    }
    const path = [...prefix, segment];
    if (valueNode?.type === 'unary' && valueNode.value === '{') {
      if (!collectLeaves(valueNode.lhs || [], path, scope, rows)) {
        return false;
      }
    } else {
      const formFieldKey = formKeyOf(valueNode);
      if (formFieldKey === null) {
        return false;
      }
      rows.push({ scope, inputPath: path.join('.'), formFieldKey });
    }
  }
  return true;
}

/** Extract the form-field key from a `$form.<key>` path node, or null. */
function formKeyOf(node: any): string | null {
  if (
    node?.type === 'path' &&
    node.steps?.length === 2 &&
    node.steps[0].type === 'variable' &&
    node.steps[0].value === 'form' &&
    typeof node.steps[1]?.value === 'string'
  ) {
    return node.steps[1].value;
  }
  return null;
}

/** Whether the expression can be edited in the simple table (round-trippable). */
export function isRoundTrippable(expression: string): boolean {
  return parseOverrideJsonata(expression) !== null;
}
