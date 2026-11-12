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
import { barePathProblem, isBareVersionRange, textExpressionProblem } from './jsonata-bare-path';

describe('textExpressionProblem', () => {
  it.each(['1', '1.4', 'true'])(
    'flags %p: not text, which an environment or range must be',
    (expression) => {
      expect(textExpressionProblem(expression)).toContain(`Did you mean "${expression}"`);
    },
  );

  it('flags a bare path as barePathProblem does', () => {
    expect(textExpressionProblem('productie')).toBe(barePathProblem('productie'));
  });

  it.each(['"1"', '$pv.versie', '$pv.versie ?: "^1.0.0"', ''])('leaves %p alone', (expression) => {
    expect(textExpressionProblem(expression)).toBeNull();
  });
});

describe('isBareVersionRange', () => {
  it.each(['^1.4.0', '>=1.3 <2', '1', '1.4', '*', 'x', '1.x'])('%p is a bare range', (value) => {
    expect(isBareVersionRange(value)).toBe(true);
  });

  it.each(['"^1.4.0"', '$pv.briefversie', '$exists($pv.v) ? $pv.v : "^1"', '', '  '])(
    '%p is not',
    (value) => {
      expect(isBareVersionRange(value)).toBe(false);
    },
  );
});

describe('barePathProblem', () => {
  it('flags a wildcard, which in an empty input finds nothing either', () => {
    expect(barePathProblem('*')).not.toBeNull();
  });

  it.each(['someEnv', 'omgeving.key', ' productie '])(
    'flags %p: a path in an empty input never finds anything',
    (expression) => {
      expect(barePathProblem(expression)).toContain(`Did you mean the text "${expression.trim()}"`);
    },
  );

  it.each([
    '"someEnv"',
    '$pv.omgeving',
    '$pv.omgeving ?: $plugin.defaultEnvironmentId',
    '$doc.name & " " & $doc.surname',
    '42',
    '',
    '$uppercase($doc.name)',
    'not valid ((',
  ])('leaves %p alone', (expression) => {
    expect(barePathProblem(expression)).toBeNull();
  });
});
