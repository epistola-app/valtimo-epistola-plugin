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
 * Fit a generated form to the reader's locale.
 *
 * <p>The backend generates the form without knowing who will read it, so what it emits is
 * locale-neutral where it matters: a date is <i>stored</i> as `yyyy-MM-dd` whatever the reader
 * types. How a value is <i>shown and typed</i> is decided here, from Valtimo's current language:
 *
 * - Form.io's `language` option, which is where its number field takes its separators from. It
 *   falls back to English rather than to the browser, so without it a Dutch reader's `12,50` is
 *   read as twelve hundred and fifty.
 * - The display format of every date and date-time, from the locale's own short date.
 */

/** Valtimo's language as a locale tag, or English when Valtimo reports none. */
export function readerLocale(language: string | null | undefined): string {
  return typeof language === 'string' && language.trim()
    ? language.trim().replace(/_/g, '-')
    : 'en';
}

/**
 * The language alone (`en` for `en-US`), which is what Form.io's `language` option must be.
 *
 * Form.io names the calendar's translation file after it, and flatpickr ships those per language:
 * `en-US` asks for a `flatpickr-en-US.js` that does not exist, and the calendar never starts. The
 * separators are the same either way. The region still counts for dates, through
 * {@link withLocaleDates}, which takes the full tag.
 */
export function formioLanguage(language: string | null | undefined): string {
  return readerLocale(language).split(/[-_]/)[0].toLowerCase();
}

/**
 * The locale's numeric short date as a Form.io format: `dd-MM-yyyy` for `nl`, `MM/dd/yyyy` for
 * `en`, `dd.MM.yyyy` for `de`. Falls back to ISO order for a locale `Intl` cannot describe.
 */
export function localeDateFormat(language: string): string {
  try {
    const parts = new Intl.DateTimeFormat(language, {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(new Date(2026, 10, 23));
    const tokens: Record<string, string> = { day: 'dd', month: 'MM', year: 'yyyy' };
    const format = parts
      .map((part) => tokens[part.type] ?? (part.type === 'literal' ? part.value : ''))
      .join('');
    return /dd/.test(format) && /MM/.test(format) && /yyyy/.test(format) ? format : 'yyyy-MM-dd';
  } catch {
    return 'yyyy-MM-dd';
  }
}

/** The locale's clock as a Form.io time format: `HH:mm`, or `hh:mm a` where it reads 12-hour. */
export function localeTimeFormat(language: string): string {
  try {
    const hour12 = new Intl.DateTimeFormat(language, { hour: 'numeric' }).resolvedOptions().hour12;
    return hour12 ? 'hh:mm a' : 'HH:mm';
  } catch {
    return 'HH:mm';
  }
}

/**
 * A copy of `form` with every date and date-time shown in the locale's format.
 *
 * Only the display changes. A date's stored value is set by its widget's `dateFormat`, which is
 * left alone, so the submission is the same whichever locale typed it.
 */
export function withLocaleDates(form: any, language: string): any {
  if (!form || !Array.isArray(form.components)) {
    return form;
  }
  const date = localeDateFormat(language);
  const dateTime = `${date} ${localeTimeFormat(language)}`;
  return { ...form, components: form.components.map((c: any) => localize(c, date, dateTime)) };
}

function localize(component: any, date: string, dateTime: string): any {
  if (!component || typeof component !== 'object') {
    return component;
  }
  let result = component;
  if (component.type === 'datetime') {
    // The datetime component derives its own clock from this format and `timePicker`.
    result = {
      ...component,
      format: dateTime,
      timePicker: { ...component.timePicker, showMeridian: /a$/.test(dateTime) },
    };
  } else if (component.widget?.type === 'calendar') {
    result = { ...component, widget: { ...component.widget, format: date } };
  }
  if (Array.isArray(result.components)) {
    result = {
      ...result,
      components: result.components.map((c: any) => localize(c, date, dateTime)),
    };
  }
  return result;
}
