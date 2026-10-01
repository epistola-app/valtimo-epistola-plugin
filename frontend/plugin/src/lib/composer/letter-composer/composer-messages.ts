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

/**
 * What the employee reads when a generated input fails the template's own rules.
 *
 * <p>The contract's constraints reach the generated form — `minLength`, `maxLength`, `pattern`,
 * `minimum`, `maximum`, `required` — so the browser already checks them as the employee types, and
 * that is the right place for it: instant, and no request. What it said about a failure was the
 * problem. Form.io's defaults are English, so a Dutch case worker filling in a Dutch letter read
 * `Motivation must have at least 10 characters`.
 *
 * <p>And for `pattern` it printed the expression. Worse than untranslated: the pattern a generated
 * field carries is not even the contract's own, because a JSON Schema `pattern` *searches* while
 * Form.io's *matches*, so the generator wraps it (`FormioFormGenerator.formioPattern`). The
 * employee was shown `[\s\S]*(?:[A-Z]{2}\d{4})[\s\S]*` — a regular expression this plugin
 * assembled, about a rule it did not invent. No message is better than that one.
 *
 * <p>Both are fixed per component rather than through Form.io's i18n, which is the other way this
 * could go. Two reasons. The messages are already localised — `PluginTranslationService.instant`
 * resolves them against the plugin's own table, so nothing here needs to know the active language
 * or keep a second set of translations keyed by one. And `pattern` cannot be done with i18n at all:
 * a useful message there has to name *this* field's rule, which only the field knows.
 *
 * <p>Nothing is validated here. This module only decides what the browser says when its own check
 * fails; whether the letter can actually be rendered is Epistola's answer, and the preview's.
 */

/** The message templates, already in the reader's language. */
export interface ValidationMessages {
  /** A value the contract requires was left empty. */
  readonly required: string;
  /** Shorter than `minLength`. May use `{{length}}`. */
  readonly minLength: string;
  /** Longer than `maxLength`. May use `{{length}}`. */
  readonly maxLength: string;
  /** Below `minimum`. May use `{{min}}`. */
  readonly min: string;
  /** Above `maximum`. May use `{{max}}`. */
  readonly max: string;
  /**
   * The value does not fit the field's `pattern`, and the contract says nothing readable about it.
   *
   * The fallback only: a field whose contract carries a description gets that instead, because a
   * sentence written for a person beats anything this plugin can say about a regular expression.
   */
  readonly pattern: string;
  /**
   * The same, for a field that *does* describe itself. May use `{{description}}`.
   *
   * The description arrives as the component's `tooltip` — that is where
   * `FormioFormGenerator` puts the contract's `description` — so it is text an author wrote for
   * this field, in the letter's own language.
   */
  readonly patternDescribed: string;
  /**
   * Appended when the contract offers a valid value for the field. May use `{{example}}`.
   *
   * A sentence saying what is wrong is worth less than one valid value, so where there is an
   * example it is shown whether or not the field also describes itself. It arrives as
   * `epistolaExample`, which the generator sets only from the schema's `examples`/`example` — not
   * from the placeholder, which may instead be a format shape (`YYYY-MM-DD`) and so an example of
   * nothing.
   */
  readonly exampleSuffix: string;
}

/** A Form.io component, as far as this module needs to care. */
interface Component {
  type?: string;
  tooltip?: string;
  epistolaExample?: string;
  validate?: Record<string, unknown>;
  errors?: Record<string, string>;
  components?: Component[];
  columns?: { components?: Component[] }[];
  rows?: { components?: Component[] }[][];
}

/**
 * The same form, with a message on every rule a generated input actually carries.
 *
 * <p>A copy: the definition comes from the server's response and is handed to Form.io, and mutating
 * it in place would make the transform impossible to test and the input impossible to re-read.
 *
 * <p>Only rules that are present get a message. Form.io resolves one through
 * `Component.errorMessage(rule)`, which prefers `component.errors[rule]` and otherwise uses the
 * rule's name as an i18n key — so an entry here replaces the English default, and a rule left out
 * keeps it. `pattern` additionally honours `validate.patternMessage`, which takes precedence over
 * `errors.pattern`; both are set, so the message holds whichever Form.io consults.
 */
export function withValidationMessages(form: unknown, messages: ValidationMessages): unknown {
  if (!isRecord(form)) {
    return form;
  }
  return { ...form, components: mapComponents(asComponents(form.components), messages) };
}

function mapComponents(components: Component[], messages: ValidationMessages): Component[] {
  return components.map((component) => withMessages(component, messages));
}

function withMessages(component: Component, messages: ValidationMessages): Component {
  const mapped: Component = { ...component };

  // Layout carries no rules of its own, but its children do: a panel, a fieldset, a wizard step,
  // and the grids whose rows are keyed inside themselves.
  if (Array.isArray(component.components)) {
    mapped.components = mapComponents(component.components, messages);
  }
  if (Array.isArray(component.columns)) {
    mapped.columns = component.columns.map((column) => ({
      ...column,
      components: mapComponents(asComponents(column.components), messages),
    }));
  }
  if (Array.isArray(component.rows)) {
    mapped.rows = component.rows.map((row) =>
      row.map((cell) => ({
        ...cell,
        components: mapComponents(asComponents(cell.components), messages),
      })),
    );
  }

  const validate = component.validate;
  if (!isRecord(validate)) {
    return mapped;
  }

  const errors: Record<string, string> = { ...component.errors };
  if (validate['required']) {
    errors['required'] = messages.required;
  }
  if (isPresent(validate['minLength'])) {
    errors['minLength'] = messages.minLength;
  }
  if (isPresent(validate['maxLength'])) {
    errors['maxLength'] = messages.maxLength;
  }
  if (isPresent(validate['min'])) {
    errors['min'] = messages.min;
  }
  if (isPresent(validate['max'])) {
    errors['max'] = messages.max;
  }

  if (isPresent(validate['pattern'])) {
    const described = typeof component.tooltip === 'string' && component.tooltip.trim() !== '';
    const base = described
      ? messages.patternDescribed.replace('{{description}}', component.tooltip!.trim())
      : messages.pattern;
    const message = base + exampleSuffix(component, messages);
    errors['pattern'] = message;
    // Form.io reads this one first for `pattern`, so it has to agree with `errors.pattern` or the
    // regex comes back through the other path.
    mapped.validate = { ...validate, patternMessage: message };
  }

  if (Object.keys(errors).length > 0) {
    mapped.errors = errors;
  }
  return mapped;
}

/**
 * ` (bijvoorbeeld 3511 LX)`, or nothing when the contract offers no example.
 *
 * One valid value tells an employee more than any description of the rule, so this is appended to
 * whichever message was chosen rather than replacing it.
 */
function exampleSuffix(component: Component, messages: ValidationMessages): string {
  const example =
    typeof component.epistolaExample === 'string' ? component.epistolaExample.trim() : '';
  return example === '' ? '' : messages.exampleSuffix.replace('{{example}}', example);
}

/** A rule Form.io will act on. `0` is a real bound; `''`, `null` and `undefined` are not. */
function isPresent(setting: unknown): boolean {
  return setting !== undefined && setting !== null && setting !== '';
}

function asComponents(value: unknown): Component[] {
  return Array.isArray(value) ? (value as Component[]) : [];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}
