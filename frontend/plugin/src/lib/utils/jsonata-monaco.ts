import { ExpressionFunctionInfo, VariableSuggestions } from '../models';

/**
 * Shared state for the JSONata completion provider.
 * Updated by the editor component when suggestions/functions change.
 */
export const jsonataCompletionData = {
  suggestions: null as VariableSuggestions | null,
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
        suggestions.push(
          ...['doc', 'pv', 'case'].map((v) => ({
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

      // After "$doc." — suggest document paths
      if (/\$doc\.\s*$/.test(textUntilPosition) || /\$doc\.[a-zA-Z_]*$/.test(textUntilPosition)) {
        const docPaths = jsonataCompletionData.suggestions?.doc || [];
        for (const path of docPaths) {
          suggestions.push({
            label: path,
            kind: CompletionItemKind.Field,
            insertText: path,
            detail: 'Document field',
          });
        }
      }

      // After "$pv." — suggest process variables
      if (/\$pv\.\s*$/.test(textUntilPosition) || /\$pv\.[a-zA-Z_]*$/.test(textUntilPosition)) {
        const pvNames = jsonataCompletionData.suggestions?.pv || [];
        for (const name of pvNames) {
          suggestions.push({
            label: name,
            kind: CompletionItemKind.Variable,
            insertText: name,
            detail: 'Process variable',
          });
        }
      }

      return { suggestions };
    },
  });
}
