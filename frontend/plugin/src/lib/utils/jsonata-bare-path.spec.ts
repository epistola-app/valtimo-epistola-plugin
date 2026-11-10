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
import { barePathProblem } from './jsonata-bare-path';

describe('barePathProblem', () => {
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
