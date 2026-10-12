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

import {
  formioLanguage,
  readerLocale,
  localeDateFormat,
  localeTimeFormat,
  withLocaleDates,
} from './formio-locale';

const calendar = (key: string) => ({
  type: 'textfield',
  key,
  widget: { type: 'calendar', saveAs: 'text', dateFormat: 'yyyy-MM-dd', format: 'dd-MM-yyyy' },
});
const dateTime = (key: string) => ({
  type: 'datetime',
  key,
  format: 'dd-MM-yyyy HH:mm',
  timePicker: { showMeridian: false },
});

describe('formioLanguage', () => {
  it('passes Valtimo’s language through', () => {
    expect(formioLanguage('nl')).toBe('nl');
  });

  it('falls back to English when there is none', () => {
    expect(formioLanguage(undefined)).toBe('en');
    expect(formioLanguage('  ')).toBe('en');
  });

  it('drops the region, which would name a calendar translation that does not exist', () => {
    expect(formioLanguage('en-US')).toBe('en');
    expect(formioLanguage('nl_BE')).toBe('nl');
  });
});

describe('readerLocale', () => {
  it('keeps the region, which decides how a date is written', () => {
    expect(readerLocale('en-GB')).toBe('en-GB');
    expect(readerLocale('nl_BE')).toBe('nl-BE');
    expect(readerLocale(null)).toBe('en');
  });
});

describe('localeDateFormat', () => {
  it.each([
    ['nl', 'dd-MM-yyyy'],
    ['en-US', 'MM/dd/yyyy'],
    ['en-GB', 'dd/MM/yyyy'],
    ['de', 'dd.MM.yyyy'],
  ])('writes a %s date as %s', (language, format) => {
    expect(localeDateFormat(language)).toBe(format);
  });

  it('falls back to ISO order for a locale Intl rejects', () => {
    expect(localeDateFormat('not a locale!')).toBe('yyyy-MM-dd');
  });
});

describe('localeTimeFormat', () => {
  it('uses a 24-hour clock where the locale does', () => {
    expect(localeTimeFormat('nl')).toBe('HH:mm');
  });

  it('uses a 12-hour clock where the locale does', () => {
    expect(localeTimeFormat('en-US')).toBe('hh:mm a');
  });
});

describe('withLocaleDates', () => {
  it('changes how a date is shown, never how it is stored', () => {
    const form = withLocaleDates({ components: [calendar('datum')] }, 'en-US');

    expect(form.components[0].widget.format).toBe('MM/dd/yyyy');
    expect(form.components[0].widget.dateFormat).toBe('yyyy-MM-dd');
    expect(form.components[0].widget.saveAs).toBe('text');
  });

  it('shows a date-time on the locale’s clock', () => {
    const nl = withLocaleDates({ components: [dateTime('moment')] }, 'nl').components[0];
    const us = withLocaleDates({ components: [dateTime('moment')] }, 'en-US').components[0];

    expect(nl.format).toBe('dd-MM-yyyy HH:mm');
    expect(nl.timePicker.showMeridian).toBe(false);
    expect(us.format).toBe('MM/dd/yyyy hh:mm a');
    expect(us.timePicker.showMeridian).toBe(true);
  });

  it('reaches dates inside fieldsets and grids', () => {
    const form = withLocaleDates(
      {
        components: [
          { type: 'fieldset', components: [{ type: 'datagrid', components: [calendar('d')] }] },
        ],
      },
      'de',
    );

    expect(form.components[0].components[0].components[0].widget.format).toBe('dd.MM.yyyy');
  });

  it('leaves other components and the original form untouched', () => {
    const original = { components: [{ type: 'number', key: 'n' }, calendar('d')] };
    const copy = JSON.parse(JSON.stringify(original));

    const form = withLocaleDates(original, 'en-US');

    expect(form.components[0]).toEqual({ type: 'number', key: 'n' });
    expect(original).toEqual(copy);
  });

  it('tolerates a form without components', () => {
    expect(withLocaleDates(null, 'nl')).toBeNull();
    expect(withLocaleDates({}, 'nl')).toEqual({});
  });
});
