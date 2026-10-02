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

import { describeFindings, pointerToFieldKey, type AddressableForm } from './composer-findings';
import type { ValidationMessages } from './composer-messages';

const MESSAGES: ValidationMessages = {
  required: 'REQUIRED {{field}}',
  minLength: 'MINLENGTH',
  maxLength: 'MAXLENGTH',
  min: 'MIN',
  max: 'MAX',
  pattern: 'PATTERN {{field}}',
  patternDescribed: 'PATTERNDESC',
  exampleSuffix: ' EG {{example}}',
};

/** A form whose components carry the keys and labels the generator gives them. */
function formWith(labels: Record<string, string>): AddressableForm {
  return {
    everyComponent(visit: (component: any) => void) {
      for (const [key, label] of Object.entries(labels)) {
        visit({ component: { key, label } });
      }
    },
  };
}

describe('pointerToFieldKey', () => {
  it('turns a pointer into the dotted key the generator used', () => {
    // A scalar input's key is its full dotted path, not its leaf name.
    expect(pointerToFieldKey('/applicant/bsn')).toBe('applicant.bsn');
    expect(pointerToFieldKey('/applicant/address/postalCode')).toBe('applicant.address.postalCode');
    expect(pointerToFieldKey('/subject')).toBe('subject');
  });

  it('undoes RFC 6901 escapes', () => {
    // `~1` is a literal slash in a property name, `~0` a literal tilde. Left encoded they would
    // address a field that does not exist.
    expect(pointerToFieldKey('/a~1b')).toBe('a/b');
    expect(pointerToFieldKey('/a~0b')).toBe('a~b');
  });

  it('refuses the document root, which is the letter rather than a field', () => {
    expect(pointerToFieldKey('')).toBeNull();
    expect(pointerToFieldKey('/')).toBeNull();
  });

  it('refuses a pointer through an array index', () => {
    // A grid's children are keyed within a row, not against the submission, so the index belongs
    // to the data's shape and not to any component's key.
    expect(pointerToFieldKey('/lineItems/0/quantity')).toBeNull();
    expect(pointerToFieldKey('/lineItems/12')).toBeNull();
  });

  it('refuses anything that is not a pointer', () => {
    expect(pointerToFieldKey('applicant.bsn')).toBeNull();
    expect(pointerToFieldKey('$.applicant.bsn')).toBeNull();
    expect(pointerToFieldKey(undefined as unknown as string)).toBeNull();
  });
});

describe('describeFindings', () => {
  it('names a field the way the employee sees it named', () => {
    // `/applicant/bsn` is not what is printed above the box.
    const form = formWith({ 'applicant.bsn': 'Burgerservicenummer' });

    const shown = describeFindings(
      form,
      [{ path: '/applicant/bsn', keyword: 'pattern', message: 'is geen geldig bsn' }],
      MESSAGES,
    );

    expect(shown).toEqual([{ label: 'Burgerservicenummer', message: 'is geen geldig bsn' }]);
  });

  it("prefers Epistola's own sentence, which is about the rule that failed", () => {
    const form = formWith({ 'customer.email': 'E-mail' });

    const shown = describeFindings(
      form,
      [{ path: '/customer/email', keyword: 'format', message: 'must be a valid email address' }],
      MESSAGES,
    );

    expect(shown[0]?.message).toBe('must be a valid email address');
  });

  it('falls back to the composer’s own wording for a field reported only as absent', () => {
    // The server reports a missing field as a location, not a sentence, so the text comes from
    // here — with `{{field}}` dropped, because the label is already beside it.
    const form = formWith({ invoiceNumber: 'Factuurnummer' });

    const shown = describeFindings(
      form,
      [{ path: '/invoiceNumber', keyword: 'required' }],
      MESSAGES,
    );

    expect(shown).toEqual([{ label: 'Factuurnummer', message: 'REQUIRED' }]);
  });

  it('keeps the pointer for a field that is not on the form at all', () => {
    // The common case this exists for: the baseline mapping supplies the value, so the employee
    // was never asked and there is no label. Less friendly than a name, and more truthful than
    // inventing one for something that is not on screen.
    const shown = describeFindings(
      formWith({ subject: 'Onderwerp' }),
      [{ path: '/recipient/city', keyword: 'required' }],
      MESSAGES,
    );

    expect(shown).toEqual([{ label: '/recipient/city', message: 'REQUIRED' }]);
  });

  it('keeps the pointer for a row inside a grid', () => {
    const shown = describeFindings(
      formWith({ lineItems: 'Regels' }),
      [{ path: '/lineItems/0/quantity', keyword: 'minimum', message: 'must be at least 1' }],
      MESSAGES,
    );

    expect(shown[0]?.label).toBe('/lineItems/0/quantity');
  });

  it('describes every finding, named or not, in the order given', () => {
    // Dropping one would leave a field that stops the letter rendering unmentioned.
    const shown = describeFindings(
      formWith({ 'applicant.bsn': 'Burgerservicenummer' }),
      [
        { path: '/applicant/bsn', keyword: 'pattern', message: 'bad bsn' },
        { path: '/recipient/city', keyword: 'required' },
      ],
      MESSAGES,
    );

    expect(shown.map((finding) => finding.label)).toEqual([
      'Burgerservicenummer',
      '/recipient/city',
    ]);
  });

  it('uses a fieldset’s legend when that is the only name a component has', () => {
    const form: AddressableForm = {
      everyComponent: (visit: (component: any) => void) =>
        visit({ component: { key: 'applicant', legend: 'Aanvrager' } }),
    };

    expect(
      describeFindings(form, [{ path: '/applicant', keyword: 'required' }], MESSAGES)[0]?.label,
    ).toBe('Aanvrager');
  });

  it('survives a form that has not mounted yet', () => {
    const shown = describeFindings(
      null,
      [{ path: '/applicant/bsn', keyword: 'required' }],
      MESSAGES,
    );

    expect(shown).toEqual([{ label: '/applicant/bsn', message: 'REQUIRED' }]);
  });

  it('says nothing when there is nothing to say', () => {
    expect(describeFindings(formWith({}), [], MESSAGES)).toEqual([]);
  });
});
