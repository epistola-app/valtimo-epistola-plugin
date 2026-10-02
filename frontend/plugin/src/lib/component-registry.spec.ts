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
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Guards the three lists that adding a Form.io component means updating.
 *
 * Each of them is written by hand somewhere other than the component itself, and forgetting one is
 * silent: a component that is never registered simply does not exist in the builder, a setting
 * left out of `fieldOptions` arrives as `undefined`, and a missing row in the docs is a component
 * nobody knows about. None of that is a compile error, and no behavioural test notices — a test
 * checks that code works, not that someone remembered to write it down elsewhere.
 *
 * That pattern has cost real bugs: a wrapper written but never wired, translations registered in
 * the wrong file, and a generate action missing from the list the catch event uses, which shipped
 * a letter that generated perfectly and left its process waiting forever.
 *
 * So these read the filesystem rather than restating what is there. Add a component and no list
 * here needs touching; miss a registration and this fails.
 */
const LIB = __dirname;
const DOCS = join(LIB, '..', '..', '..', '..', 'docs', 'formio-components.md');

/**
 * Inputs every custom component has, or that its Formio wrapper fills in at runtime — none of them
 * is a setting an author types in the builder, so none belongs in `fieldOptions`.
 *
 * Kept explicit rather than pattern-matched: a new input that is genuinely wrapper-set gets added
 * here deliberately, which is the moment to ask whether it really is.
 */
const NOT_AUTHORED: Record<string, string> = {
  value: "Valtimo's custom-component contract",
  disabled: "Valtimo's custom-component contract",
  taskInstanceId: 'filled from the server-prefilled epistola:taskId carrier',
  startDocumentId: 'filled from the server-prefilled epistola:documentId carrier',
  componentKey: "the wrapper passes the component's own Formio key",
  designMode: 'the wrapper detects the builder',
  liveOverrides: 'runtime state, pushed by the wrapper as the form is filled in',
  inputOverrides: 'runtime state, pushed by the wrapper',
  requestOverrides: 'runtime state, pushed by the wrapper',
  autoRefresh:
    "seeded once from the builder option, then toggled at runtime — see the wrapper's note",
  setAutoRefresh: 'a callback the wrapper provides',
  tenantIdVariable: 'intentionally not author-configurable; see epistola-document.formio.ts',
};

function filesUnder(dir: string, suffix: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      return filesUnder(path, suffix);
    }
    return path.endsWith(suffix) ? [path] : [];
  });
}

/** Every Form.io component this library defines, found on disk rather than listed. */
const components = filesUnder(LIB, '.formio.ts').map((path) => {
  const source = readFileSync(path, 'utf8');
  const componentPath = path.replace('.formio.ts', '.component.ts');
  let inputs: string[] = [];
  try {
    // `set` is skipped: an @Input() may be a setter, and the setting is named after it, not
    // "set". A component that reads its value through one otherwise looks like it takes a
    // setting called `set` that no author can type.
    inputs = [
      ...readFileSync(componentPath, 'utf8').matchAll(/@Input\(\)\s+(?:set\s+)?(\w+)/g),
    ].map((match) => match[1]);
  } catch {
    // A few registrations reuse a component from elsewhere; nothing to compare then.
  }
  return {
    file: path.slice(LIB.length + 1),
    type: source.match(/type: '([a-z-]+)'/)?.[1],
    registrar: source.match(/export function (registerEpistola\w*Component)/)?.[1],
    fieldOptions: [
      ...(source.match(/fieldOptions:\s*\[([\s\S]*?)\]/)?.[1] ?? '').matchAll(/'(\w+)'/g),
    ].map((match) => match[1]),
    inputs,
  };
});

describe('every Form.io component this library defines', () => {
  it('was found — otherwise the rest of this file proves nothing', () => {
    expect(components.length).toBeGreaterThanOrEqual(8);
    expect(components.every((component) => component.type)).toBe(true);
  });

  it('is actually registered, or it does not exist in the builder', () => {
    // Import lines are stripped first: a registration that is imported and then never called is
    // exactly the mistake this is here to catch, and the name alone cannot tell the difference.
    const calls = ['services/epistola-registration.service.ts', 'composer/composer.registration.ts']
      .flatMap((path) => readFileSync(join(LIB, path), 'utf8').split('\n'))
      .filter((line) => !line.trimStart().startsWith('import'))
      .join('\n');

    for (const component of components) {
      if (!component.registrar) {
        continue;
      }
      expect(calls).toMatch(new RegExp(`\\b${component.registrar}\\s*\\(`));
    }
  });

  it.each(components.filter((component) => component.inputs.length))(
    'hands $type every setting an author can type',
    ({ fieldOptions, inputs }) => {
      const missing = inputs.filter(
        (input) => !fieldOptions.includes(input) && !(input in NOT_AUTHORED),
      );

      expect(missing).toEqual([]);
    },
  );

  it.each(components)('lists $type in docs/formio-components.md', ({ type }) => {
    expect(readFileSync(DOCS, 'utf8')).toContain(`\`${type}\``);
  });

  it('forwards no setting twice', () => {
    for (const { type, fieldOptions } of components) {
      expect([...new Set(fieldOptions)]).toEqual(fieldOptions);
      expect(type).toBeTruthy();
    }
  });
});
