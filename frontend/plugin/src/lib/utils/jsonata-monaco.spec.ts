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

import { jsonataCompletionData, registerJsonataLanguage } from './jsonata-monaco';

describe('JSONata Monaco completions', () => {
  it('suggests described top-level fields for schema-backed function calls', () => {
    let provider: any;
    const monaco = {
      languages: {
        getLanguages: () => [],
        register: jest.fn(),
        setMonarchTokensProvider: jest.fn(),
        registerCompletionItemProvider: jest.fn((_language, value) => {
          provider = value;
        }),
        CompletionItemKind: { Variable: 1, Function: 2, Field: 3 },
        CompletionItemInsertTextRule: { InsertAsSnippet: 4 },
      },
    };
    jsonataCompletionData.variables = {};
    jsonataCompletionData.functions = [
      {
        name: 'inwonerplan',
        description: 'Resident plan',
        overloads: [
          {
            arguments: [],
            returnType: 'Map',
            resultSchema: {
              type: 'object',
              required: ['activiteiten'],
              properties: {
                activiteiten: {
                  type: 'array',
                  description: 'Planned activities',
                  items: { type: 'object' },
                },
              },
            },
          },
        ],
      },
    ];
    registerJsonataLanguage(monaco);

    const result = provider.provideCompletionItems(
      { getValueInRange: () => '$inwonerplan().' },
      { lineNumber: 1, column: 18 },
    );

    expect(result.suggestions).toEqual([
      expect.objectContaining({
        label: 'activiteiten',
        insertText: 'activiteiten',
        detail: 'array<object> · required',
        documentation: 'Planned activities',
      }),
    ]);
  });
});
