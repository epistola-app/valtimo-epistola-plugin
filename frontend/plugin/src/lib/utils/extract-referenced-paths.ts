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

/** A `$doc`/`$pv`/`$case` path referenced by a JSONata expression. */
export interface ReferencedPath {
  scope: 'doc' | 'pv' | 'case';
  /** Dotted path under the scope, e.g. `aanvrager.naam`. Empty for a whole-scope reference. */
  path: string;
}

const SCOPES = ['doc', 'pv', 'case'] as const;
type Scope = (typeof SCOPES)[number];

function isScope(value: unknown): value is Scope {
  return typeof value === 'string' && (SCOPES as readonly string[]).includes(value);
}

/**
 * Statically extract every `$doc`/`$pv`/`$case` path referenced anywhere in a JSONata
 * expression. Used to surface — informationally — which inputs a template's data mapping
 * consumes, so the override-builder author sees what is worth overriding during preview.
 *
 * This is a best-effort static read: paths built dynamically (`$lookup`, custom functions,
 * computed keys) can't be resolved and simply won't appear. Treat the result as suggestions,
 * never as validation — an empty result (e.g. on a parse error) means "nothing to suggest".
 *
 * Generalizes the variable-path primitive in `utils/jsonata-converter.ts` (`classifyValue`)
 * to recurse over the whole AST rather than only top-level object values.
 */
export function extractReferencedPaths(expression: string | null | undefined): ReferencedPath[] {
  if (!expression || !expression.trim()) {
    return [];
  }
  let ast: any;
  try {
    ast = (jsonata(expression) as any).ast();
  } catch {
    return [];
  }

  const seen = new Map<string, ReferencedPath>();
  walk(ast, seen);

  return [...seen.values()].sort(
    (a, b) => a.scope.localeCompare(b.scope) || a.path.localeCompare(b.path),
  );
}

function record(node: any, seen: Map<string, ReferencedPath>): void {
  // Two shapes carry a scope reference:
  //  - a bare `variable` node (`$doc`), where the scope is `node.value` and the path is empty;
  //  - a `path` node whose first step is the `$<scope>` variable, followed by the property
  //    names: `$doc.aanvrager.naam` → steps [doc, aanvrager, naam].
  const scope = node?.type === 'variable' ? node.value : node?.steps?.[0]?.value;
  if (!isScope(scope)) {
    return;
  }
  const path = (node.steps ?? [])
    .slice(1)
    .map((step: any) => step?.value)
    .filter((segment: unknown): segment is string => typeof segment === 'string')
    .join('.');
  const key = `${scope}.${path}`;
  if (!seen.has(key)) {
    seen.set(key, { scope, path });
  }
}

/** Recursively walk every child node, recording any `$doc`/`$pv`/`$case` path reference. */
function walk(node: any, seen: Map<string, ReferencedPath>): void {
  if (!node || typeof node !== 'object') {
    return;
  }

  // A bare scope variable with no property access, e.g. `$spread($doc)`.
  if (node.type === 'variable') {
    if (isScope(node.value)) {
      record(node, seen);
    }
    return;
  }

  // A path rooted at a scope variable, e.g. `$doc.aanvrager.naam`. Record the whole path,
  // then walk only the filters/predicates attached to its steps — not the variable/name
  // steps themselves, which would otherwise re-record the leading scope with an empty path.
  if (
    node.type === 'path' &&
    node.steps?.[0]?.type === 'variable' &&
    isScope(node.steps[0].value)
  ) {
    record(node, seen);
    for (const step of node.steps) {
      walkValue(step?.predicate, seen);
      walkValue(step?.stages, seen);
      walkValue(step?.group, seen);
    }
    return;
  }

  // Generic recursion into every structural child the dashjoin AST uses. Object literals
  // carry their entries as `lhs` = array of [keyNode, valueNode] pairs.
  for (const child of [
    node.lhs,
    node.rhs,
    node.condition,
    node.then,
    node.else,
    node.procedure,
    node.group,
    node.pattern,
    node.update,
    node.delete,
  ]) {
    walkValue(child, seen);
  }
  for (const list of [
    node.steps,
    node.arguments,
    node.stages,
    node.expressions,
    node.terms,
    node.predicate,
  ]) {
    walkValue(list, seen);
  }
}

/** Walk a value that may be a node, an array of nodes, or an array of [key, value] pairs. */
function walkValue(value: any, seen: Map<string, ReferencedPath>): void {
  if (Array.isArray(value)) {
    for (const item of value) {
      walkValue(item, seen);
    }
  } else {
    walk(value, seen);
  }
}
