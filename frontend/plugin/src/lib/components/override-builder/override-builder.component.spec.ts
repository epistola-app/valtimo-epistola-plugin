/*
 * Copyright 2026 Epistola.
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

/**
 * The authoring-time guard against a `$form` reference this form cannot satisfy.
 *
 * Without it the failure is invisible: the mapping saves, the preview renders a
 * plausible document from the saved case data, and nothing tells the author it
 * ignored what they wrote. That is the exact shape of the Form Flow cross-step
 * trap — `$form` only ever holds the form being edited.
 */
jest.mock('@angular/core', () => ({
  ChangeDetectionStrategy: { OnPush: 0 },
  ChangeDetectorRef: class {},
  Component: () => (target: unknown) => target,
  EventEmitter: class {
    emit(): void {}
  },
  Input: () => () => undefined,
  Output: () => () => undefined,
}));
jest.mock('@angular/common', () => ({ CommonModule: class {} }));
jest.mock('@angular/forms', () => ({ FormsModule: class {} }));
jest.mock('@valtimo/components', () => ({ FormioCustomComponent: class {} }));
jest.mock('../jsonata-editor/jsonata-editor.component', () => ({
  JsonataEditorComponent: class {},
}));
jest.mock('../../services', () => ({ EpistolaPluginService: class {} }));

import { EpistolaOverrideBuilderComponent } from './override-builder.component';
import { collectFormFields } from './collect-form-fields';

function builder(expression: string, fields: string[]): EpistolaOverrideBuilderComponent {
  const component = new EpistolaOverrideBuilderComponent({} as never, {} as never);
  component.expression = expression;
  component.availableFields = fields.map((key) => ({ key, label: key }));
  return component;
}

describe('OverrideBuilderComponent — unknown $form references', () => {
  it('stays quiet when every referenced field exists on the form', () => {
    const c = builder('{ "doc": { "title": $form.subject } }', ['subject', 'other']);
    expect(c.unknownFieldReferences).toEqual([]);
    expect(c.hasUnknownFieldReferences).toBe(false);
  });

  it('flags a field that only exists on another form flow step', () => {
    const c = builder('{ "doc": { "title": $form.subject } }', ['confirmation']);
    expect(c.unknownFieldReferences).toEqual(['subject']);
    expect(c.hasUnknownFieldReferences).toBe(true);
  });

  it('flags unknown fields in an advanced expression the simple table cannot hold', () => {
    const c = builder('{ "doc": { "title": $form.subject & " " & $form.kind } }', ['subject']);
    expect(c.unknownFieldReferences).toEqual(['kind']);
  });

  it('stays quiet when the field list is unknown', () => {
    // With no availableFields the builder falls back to free-text field entry and
    // cannot distinguish an unknown key from an unlisted one; warning on every
    // key would be worse than silence.
    const c = builder('{ "doc": { "title": $form.subject } }', []);
    expect(c.unknownFieldReferences).toEqual([]);
  });

  it('stays quiet for an empty mapping', () => {
    expect(builder('', ['subject']).unknownFieldReferences).toEqual([]);
  });

  it('does not flag other context variables', () => {
    const c = builder('{ "doc": { "title": $doc.title } }', ['subject']);
    expect(c.unknownFieldReferences).toEqual([]);
  });
});

describe('collectFormFields', () => {
  it('lists input fields, including hidden ones used as cross-step carriers', () => {
    const fields = collectFormFields([
      { type: 'textfield', input: true, key: 'subject', label: 'Onderwerp' },
      { type: 'hidden', input: true, key: 'carried', label: 'From step 1' },
      { type: 'button', input: true, key: 'submit', label: 'Submit' },
      { type: 'htmlelement', input: false, key: 'info' },
    ]);
    expect(fields).toEqual([
      { key: 'subject', label: 'Onderwerp' },
      { key: 'carried', label: 'From step 1' },
    ]);
  });

  it('does not offer the task-id carrier nested inside an Epistola component', () => {
    // The carrier is plumbing the plugin injects, not a field an author picks.
    const fields = collectFormFields([
      {
        type: 'epistola-document-preview',
        key: 'preview',
        components: [
          { type: 'hidden', input: true, key: 'epistolaTaskId', label: 'Epistola Task Id' },
        ],
      },
    ]);
    expect(fields).toEqual([]);
  });

  it('still descends into ordinary containers and columns', () => {
    const fields = collectFormFields([
      {
        type: 'panel',
        key: 'panel',
        components: [{ type: 'textfield', input: true, key: 'inner', label: 'Inner' }],
      },
      {
        type: 'columns',
        key: 'cols',
        columns: [{ components: [{ type: 'textfield', input: true, key: 'col', label: 'Col' }] }],
      },
    ]);
    expect(fields.map((f) => f.key).sort()).toEqual(['col', 'inner']);
  });
});
