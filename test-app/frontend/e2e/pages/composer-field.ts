// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import type { Locator } from '@playwright/test';

/**
 * A field the letter composer asks for, by its template field key.
 *
 * A plain field is an input named `data[key]`. A rich-text field (`motivation` on
 * `besluit-bezwaar`) is Form.io's WYSIWYG text area instead, and CKEditor replaces the textarea
 * with an editable region, so nothing carries the name. Playwright's `fill` types into either.
 */
export function composerField(inputs: Locator, key: string): Locator {
  return inputs.locator(`[name="data[${key}]"], .formio-component-${key} .ck-editor__editable`);
}
