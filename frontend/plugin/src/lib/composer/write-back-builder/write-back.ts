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

import { isJsonataExpressionValid } from '../../utils/jsonata-converter';

/**
 * The write-back rules, as the author edits them and as the form stores them.
 *
 * <p>Stored as a map from destination to expression, which is the shape the backend reads and the
 * direction that matters: one writer per case path. Edited as a list, because a map has no order to
 * add a row to and no way to hold a half-typed key.
 *
 * <p>Nothing here evaluates anything. The rules are evaluated when the letter is generated, against
 * that letter — see [ADR 0006](../../../../../docs/adr/0006-letter-composer-configuration.md).
 */

/** One rule, while it is being written. */
export interface WriteBackRule {
  /** Where the value goes: a value-resolver key, such as `doc:/aanvrager/telefoon`. */
  destination: string;
  /** A JSONata expression over the composed letter: `$data…`, `$inputs…`. */
  expression: string;
}

/** What is wrong with a rule, or null when nothing is. */
export interface RuleProblem {
  readonly destination: string | null;
  readonly expression: string | null;
}

/**
 * A resolver key the backend can write to.
 *
 * <p>`doc:` takes a JSON Pointer into the case document; `pv:` a process variable name. Both are
 * Valtimo's own, and both are checked here only for shape — whether the path exists in this case
 * type is a question for the case definition, not for a regular expression.
 */
const DESTINATION = /^(doc:\/[^\s]*|pv:[A-Za-z_][A-Za-z0-9_]*)$/;

/** The prefixes a destination may use, for the message when it uses another. */
export const SUPPORTED_PREFIXES = ['doc:', 'pv:'] as const;

/** The stored map as editable rows, in a stable order. */
export function rulesFrom(stored: unknown): WriteBackRule[] {
  if (!stored || typeof stored !== 'object' || Array.isArray(stored)) {
    return [];
  }
  return Object.entries(stored as Record<string, unknown>).map(([destination, expression]) => ({
    destination,
    expression: typeof expression === 'string' ? expression : '',
  }));
}

/**
 * The rows as the stored map, keeping only rules that are complete.
 *
 * <p>A half-written rule is dropped rather than stored: the backend drops it too, with a warning,
 * and storing one would mean a form that looks configured and writes nothing. A row the author is
 * still typing therefore simply does not count yet, which is also why this is recomputed on every
 * keystroke rather than on blur.
 *
 * <p>Null rather than an empty object when nothing is configured, so a composer with no write-back
 * stores no `writeBack` key at all.
 */
export function storedFrom(rules: readonly WriteBackRule[]): Record<string, string> | null {
  const stored: Record<string, string> = {};
  for (const rule of rules) {
    const destination = rule.destination?.trim();
    const expression = rule.expression?.trim();
    if (destination && expression) {
      stored[destination] = expression;
    }
  }
  return Object.keys(stored).length ? stored : null;
}

/**
 * What is wrong with this rule, given the others.
 *
 * <p>Reported per row rather than refused: an author mid-sentence has an invalid expression almost
 * continuously, and a builder that erases work to keep itself valid is worse than one that says
 * what it does not yet understand.
 */
export function problemWith(
  rule: WriteBackRule,
  index: number,
  rules: readonly WriteBackRule[],
): RuleProblem {
  const destination = rule.destination?.trim() ?? '';
  const expression = rule.expression?.trim() ?? '';

  let destinationProblem: string | null = null;
  if (destination && !DESTINATION.test(destination)) {
    destinationProblem = 'writeBackDestinationInvalid';
  } else if (
    destination &&
    rules.some(
      (other, otherIndex) => otherIndex < index && other.destination?.trim() === destination,
    )
  ) {
    // One writer per destination. Two rules for one path have no defined order, and the backend
    // keeps the first with a warning — better to say so here, where it can still be fixed.
    destinationProblem = 'writeBackDestinationDuplicate';
  } else if (!destination && expression) {
    destinationProblem = 'writeBackDestinationMissing';
  }

  let expressionProblem: string | null = null;
  if (expression && !isJsonataExpressionValid(expression)) {
    expressionProblem = 'writeBackExpressionInvalid';
  } else if (!expression && destination) {
    expressionProblem = 'writeBackExpressionMissing';
  }

  return { destination: destinationProblem, expression: expressionProblem };
}
