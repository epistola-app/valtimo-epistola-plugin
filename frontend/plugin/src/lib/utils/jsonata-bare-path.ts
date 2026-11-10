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

// The package's default export, under both module systems (as elsewhere in the plugin).
const jsonata = (_jsonata as any).default || _jsonata;

/**
 * Whether an expression is a bare path — `someEnv`, `omgeving.key` — and what to say about it.
 *
 * The plugin evaluates every expression against an empty input: only `$doc`, `$pv` and `$case` (and
 * `$plugin`) carry data. A path that starts with a plain name therefore never finds anything and
 * always produces no value. It is never a choice; it is a forgotten quote (`"someEnv"`) or a
 * forgotten `$pv.` — and since an environment or release producing no value fails generation
 * (ADR 0009, second addendum), it is caught here, while configuring, rather than on the first case.
 *
 * @returns a message with the likely fix, or null when the expression is not a bare path (or does
 *          not parse, which the editor reports on its own)
 */
export function barePathProblem(expression: string): string | null {
  const source = expression.trim();
  if (!source) return null;
  let ast: { type?: string; value?: unknown; steps?: { type?: string; value?: unknown }[] };
  try {
    ast = jsonata(source).ast() as typeof ast;
  } catch {
    return null;
  }
  const first = ast.type === 'path' ? ast.steps?.[0] : ast;
  if (!first || first.type !== 'name') return null;
  return (
    `${source} refers to nothing and always produces no value: an expression reads data through ` +
    `$doc, $pv or $case. Did you mean the text "${source}", or $pv.${source}?`
  );
}
