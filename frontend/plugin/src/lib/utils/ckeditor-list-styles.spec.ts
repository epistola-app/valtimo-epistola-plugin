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
  CKEDITOR_LIST_STYLES,
  CKEDITOR_LIST_STYLES_ID,
  installCkEditorListStyles,
  type StyleHost,
} from './ckeditor-list-styles';

function fakeDocument() {
  const appended: { id: string; textContent: string | null }[] = [];
  const host: StyleHost = {
    getElementById: (id) => appended.find((el) => el.id === id) ?? null,
    createElement: () => ({ id: '', textContent: null }),
    head: { appendChild: (node) => appended.push(node as never) },
  };
  return { host, appended };
}

describe('installCkEditorListStyles', () => {
  it('adds the list styles to the page', () => {
    const { host, appended } = fakeDocument();

    installCkEditorListStyles(host);

    expect(appended).toEqual([{ id: CKEDITOR_LIST_STYLES_ID, textContent: CKEDITOR_LIST_STYLES }]);
  });

  it('adds them once, however often it is called', () => {
    const { host, appended } = fakeDocument();

    installCkEditorListStyles(host);
    installCkEditorListStyles(host);

    expect(appended).toHaveLength(1);
  });

  it('does nothing without a document', () => {
    expect(() => installCkEditorListStyles(undefined)).not.toThrow();
  });

  it('touches only CKEditor content', () => {
    const selectors = CKEDITOR_LIST_STYLES.split('}')
      .map((rule) => rule.split('{')[0].trim())
      .filter(Boolean);

    expect(selectors.length).toBeGreaterThan(0);
    selectors.forEach((selector) => expect(selector).toMatch(/^\.ck-content /));
  });
});
