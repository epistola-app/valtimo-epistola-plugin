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

import { ExpressionFunctionInfo } from '../models';
import { renderJsonataPathTail } from './jsonata-path';

/**
 * Shared state for the JSONata completion provider.
 * Updated by the editor component when suggestions/functions change.
 */
export const jsonataCompletionData = {
  // Context variables in scope, keyed by name (without the `$`), each mapping to
  // its known field/path suggestions. The completion provider derives both the
  // `$`-variable list and the `$<name>.` field list from this — adding a new
  // context variable needs no provider change, just another key here.
  // e.g. { doc: ['name', 'address.street'], pv: ['amount'], form: ['voornaam'] }
  variables: {} as Record<string, string[]>,
  functions: [] as ExpressionFunctionInfo[],
};

/**
 * Register the JSONata language in Monaco editor.
 * Call this once when Monaco is available (e.g., in editor component OnInit).
 */
export function registerJsonataLanguage(monaco: any): void {
  // Only register once
  if (monaco.languages.getLanguages().some((lang: any) => lang.id === 'jsonata')) {
    return;
  }

  monaco.languages.register({ id: 'jsonata' });

  // Syntax highlighting via Monarch tokenizer
  monaco.languages.setMonarchTokensProvider('jsonata', {
    defaultToken: '',
    tokenPostfix: '.jsonata',

    keywords: ['true', 'false', 'null', 'in', 'and', 'or', 'not'],

    operators: ['&', '?', ':', '=', '!=', '>', '<', '>=', '<=', '+', '-', '*', '/', '%', '~>'],

    symbols: /[=><!~?:&|+\-*/^%]+/,

    tokenizer: {
      root: [
        // Variables: $identifier
        [/\$[a-zA-Z_]\w*/, 'variable'],

        // Identifiers and keywords
        [
          /[a-zA-Z_]\w*/,
          {
            cases: {
              '@keywords': 'keyword',
              '@default': 'identifier',
            },
          },
        ],

        // Whitespace
        { include: '@whitespace' },

        // Strings
        [/"([^"\\]|\\.)*$/, 'string.invalid'],
        [/"/, 'string', '@string_double'],
        [/'([^'\\]|\\.)*$/, 'string.invalid'],
        [/'/, 'string', '@string_single'],

        // Numbers
        [/\d+(\.\d+)?([eE][-+]?\d+)?/, 'number'],

        // Delimiters and operators
        [/[{}()\[\]]/, '@brackets'],
        [/[,;.]/, 'delimiter'],
        [
          /@symbols/,
          {
            cases: {
              '@operators': 'operator',
              '@default': '',
            },
          },
        ],
      ],

      string_double: [
        [/[^\\"]+/, 'string'],
        [/\\./, 'string.escape'],
        [/"/, 'string', '@pop'],
      ],

      string_single: [
        [/[^\\']+/, 'string'],
        [/\\./, 'string.escape'],
        [/'/, 'string', '@pop'],
      ],

      whitespace: [[/[ \t\r\n]+/, 'white']],
    },
  });

  // Autocomplete provider
  monaco.languages.registerCompletionItemProvider('jsonata', {
    triggerCharacters: ['$', '.'],

    provideCompletionItems: (model: any, position: any) => {
      const textUntilPosition = model.getValueInRange({
        startLineNumber: position.lineNumber,
        startColumn: 1,
        endLineNumber: position.lineNumber,
        endColumn: position.column,
      });

      const suggestions: any[] = [];
      const CompletionItemKind = monaco.languages.CompletionItemKind;

      // After "$" — suggest variables and functions
      if (textUntilPosition.endsWith('$')) {
        // Variables are whatever the host put in scope (doc/pv/case/form/…).
        suggestions.push(
          ...Object.keys(jsonataCompletionData.variables).map((v) => ({
            label: `$${v}`,
            kind: CompletionItemKind.Variable,
            insertText: v,
            detail: `Context variable`,
          })),
        );

        // Custom functions
        for (const func of jsonataCompletionData.functions) {
          suggestions.push({
            label: `$${func.name}`,
            kind: CompletionItemKind.Function,
            insertText: `${func.name}($0)`,
            insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
            detail: func.description || 'Custom function',
          });
        }

        // Built-in JSONata functions
        const builtins = [
          'string',
          'number',
          'boolean',
          'length',
          'substring',
          'uppercase',
          'lowercase',
          'trim',
          'contains',
          'split',
          'join',
          'sum',
          'count',
          'max',
          'min',
          'average',
          'append',
          'sort',
          'reverse',
          'keys',
          'values',
          'lookup',
          'now',
          'exists',
          'type',
          'not',
        ];
        for (const fn of builtins) {
          suggestions.push({
            label: `$${fn}`,
            kind: CompletionItemKind.Function,
            insertText: `${fn}($0)`,
            insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
            detail: 'Built-in JSONata function',
          });
        }
      }

      // After "$<name>." — suggest that variable's fields. The variable name is
      // captured generically, so doc/pv/case/form/… all work from one branch.
      const fieldMatch = textUntilPosition.match(/\$([a-zA-Z_]\w*)\.[a-zA-Z_]*$/);
      if (fieldMatch) {
        const fields = jsonataCompletionData.variables[fieldMatch[1]] || [];
        for (const field of fields) {
          suggestions.push({
            label: field,
            kind: CompletionItemKind.Field,
            // Formio and JSONata both treat dots as path separators. Quote only
            // individual unsafe segments (e.g. `doc:adres`.straat), never the
            // whole dotted key.
            insertText: renderJsonataPathTail(field),
            detail: `$${fieldMatch[1]} field`,
          });
        }
      }

      return { suggestions };
    },
  });
}
