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

import { withValidationMessages, type ValidationMessages } from './composer-messages';

/** Recognisable stand-ins, so a test failure says which message was used. */
const MESSAGES: ValidationMessages = {
  required: 'REQUIRED {{field}}',
  minLength: 'MINLENGTH {{field}} {{length}}',
  maxLength: 'MAXLENGTH {{field}} {{length}}',
  min: 'MIN {{field}} {{min}}',
  max: 'MAX {{field}} {{max}}',
  pattern: 'PATTERN {{field}}',
  patternDescribed: 'PATTERNDESC {{field}} {{description}}',
};

/** The shape `FormioFormGenerator` emits, trimmed to what this transform reads. */
function form(...components: unknown[]): any {
  return { display: 'form', components };
}

function errorsOf(result: unknown, index = 0): Record<string, string> | undefined {
  return (result as any).components[index].errors;
}

describe('withValidationMessages', () => {
  it('gives every rule a component carries a message of its own', () => {
    const result = withValidationMessages(
      form({
        key: 'motivation',
        label: 'Motivation',
        validate: { required: true, minLength: 10, maxLength: 200 },
      }),
      MESSAGES,
    );

    expect(errorsOf(result)).toEqual({
      required: 'REQUIRED {{field}}',
      minLength: 'MINLENGTH {{field}} {{length}}',
      maxLength: 'MAXLENGTH {{field}} {{length}}',
    });
  });

  it('leaves a rule the component does not carry to Form.io', () => {
    // An entry here replaces Form.io's English default; an absent one keeps it. Writing a message
    // for a rule that cannot fire would be dead text to maintain.
    const result = withValidationMessages(
      form({ key: 'subject', validate: { required: true } }),
      MESSAGES,
    );

    expect(Object.keys(errorsOf(result) ?? {})).toEqual(['required']);
  });

  it('treats a zero bound as a bound', () => {
    // `minimum: 0` is a real rule and a falsy value — the trap in writing this check.
    const result = withValidationMessages(
      form({ key: 'quantity', validate: { min: 0, max: 0 } }),
      MESSAGES,
    );

    expect(errorsOf(result)).toEqual({
      min: 'MIN {{field}} {{min}}',
      max: 'MAX {{field}} {{max}}',
    });
  });

  it('says nothing about a field with no rules at all', () => {
    const result = withValidationMessages(form({ key: 'notes' }), MESSAGES);

    expect(errorsOf(result)).toBeUndefined();
  });

  describe('pattern', () => {
    const PATTERN = '[\\s\\S]*(?:^[A-Z]{2}\\d{4}$)[\\s\\S]*';

    it('never shows the expression, by either route Form.io reads', () => {
      // The regression this exists for. The pattern on a generated field is not even the
      // contract's own — the generator wraps it so Form.io's match behaves like JSON Schema's
      // search — so showing it tells the employee about a rule this plugin assembled.
      const result = withValidationMessages(
        form({ key: 'reference', label: 'Reference', validate: { pattern: PATTERN } }),
        MESSAGES,
      );

      const component = (result as any).components[0];
      expect(component.errors.pattern).toBe('PATTERN {{field}}');
      expect(component.validate.patternMessage).toBe('PATTERN {{field}}');
      expect(JSON.stringify(component.errors)).not.toContain('[\\s\\S]');
      expect(JSON.stringify(component.validate.patternMessage)).not.toContain('[\\s\\S]');
    });

    it('prefers what the contract says about the field over anything generic', () => {
      // The description is the only human text available, and an author wrote it for this field.
      const result = withValidationMessages(
        form({
          key: 'reference',
          tooltip: 'two capitals followed by four digits, e.g. NL1234',
          validate: { pattern: PATTERN },
        }),
        MESSAGES,
      );

      expect(errorsOf(result)?.['pattern']).toBe(
        'PATTERNDESC {{field}} two capitals followed by four digits, e.g. NL1234',
      );
    });

    it('ignores a description that is only whitespace', () => {
      const result = withValidationMessages(
        form({ key: 'reference', tooltip: '   ', validate: { pattern: PATTERN } }),
        MESSAGES,
      );

      expect(errorsOf(result)?.['pattern']).toBe('PATTERN {{field}}');
    });

    it('keeps the pattern itself, so the check still runs', () => {
      // Only the message changes. Dropping the rule would make the form accept what Epistola will
      // refuse, which is worse than an unreadable message.
      const result = withValidationMessages(
        form({ key: 'reference', validate: { pattern: PATTERN } }),
        MESSAGES,
      );

      expect((result as any).components[0].validate.pattern).toBe(PATTERN);
    });
  });

  describe('nesting', () => {
    it('reaches a field inside a panel, a wizard step and a column', () => {
      const result = withValidationMessages(
        form(
          {
            type: 'panel',
            components: [{ key: 'applicant.bsn', validate: { required: true } }],
          },
          {
            type: 'columns',
            columns: [{ components: [{ key: 'street', validate: { maxLength: 60 } }] }],
          },
        ),
        MESSAGES,
      );

      const panelled = (result as any).components[0].components[0];
      const columned = (result as any).components[1].columns[0].components[0];
      expect(panelled.errors).toEqual({ required: 'REQUIRED {{field}}' });
      expect(columned.errors).toEqual({ maxLength: 'MAXLENGTH {{field}} {{length}}' });
    });

    it('reaches a grid, whose own rule is separate from its rows', () => {
      const result = withValidationMessages(
        form({
          type: 'datagrid',
          key: 'lineItems',
          validate: { required: true },
          components: [{ key: 'quantity', validate: { min: 1 } }],
        }),
        MESSAGES,
      );

      const grid = (result as any).components[0];
      expect(grid.errors).toEqual({ required: 'REQUIRED {{field}}' });
      expect(grid.components[0].errors).toEqual({ min: 'MIN {{field}} {{min}}' });
    });
  });

  describe('safety', () => {
    it('does not modify the definition it was given', () => {
      // It comes from the server's response and is handed to Form.io; mutating it in place would
      // make this untestable and the input unreadable afterwards.
      const original = form({ key: 'subject', validate: { required: true } });
      const snapshot = JSON.parse(JSON.stringify(original));

      withValidationMessages(original, MESSAGES);

      expect(original).toEqual(snapshot);
    });

    it('keeps a message an author set by hand', () => {
      const result = withValidationMessages(
        form({ key: 'subject', errors: { custom: 'MINE' }, validate: { required: true } }),
        MESSAGES,
      );

      expect(errorsOf(result)).toEqual({ custom: 'MINE', required: 'REQUIRED {{field}}' });
    });

    it('passes anything that is not a form straight through', () => {
      expect(withValidationMessages(null, MESSAGES)).toBeNull();
      expect(withValidationMessages(undefined, MESSAGES)).toBeUndefined();
      expect(withValidationMessages('not a form', MESSAGES)).toBe('not a form');
    });

    it('survives a form with no components', () => {
      expect(withValidationMessages({ display: 'form' }, MESSAGES)).toEqual({
        display: 'form',
        components: [],
      });
    });
  });
});
