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
  CKEDITOR_CONTENT_STYLES,
  CKEDITOR_CONTENT_STYLES_ID,
  installCkEditorContentStyles,
  type StyleHost,
} from './ckeditor-content-styles';

function fakeDocument() {
  const appended: { id: string; textContent: string | null }[] = [];
  const host: StyleHost = {
    getElementById: (id) => appended.find((el) => el.id === id) ?? null,
    createElement: () => ({ id: '', textContent: null }),
    head: { appendChild: (node) => appended.push(node as never) },
  };
  return { host, appended };
}

describe('installCkEditorContentStyles', () => {
  it('adds the content styles to the page', () => {
    const { host, appended } = fakeDocument();

    installCkEditorContentStyles(host);

    expect(appended).toEqual([
      { id: CKEDITOR_CONTENT_STYLES_ID, textContent: CKEDITOR_CONTENT_STYLES },
    ]);
  });

  it('adds them once, however often it is called', () => {
    const { host, appended } = fakeDocument();

    installCkEditorContentStyles(host);
    installCkEditorContentStyles(host);

    expect(appended).toHaveLength(1);
  });

  it('does nothing without a document', () => {
    expect(() => installCkEditorContentStyles(undefined)).not.toThrow();
  });

  it('restores what Carbon strips: list markers and italics', () => {
    expect(CKEDITOR_CONTENT_STYLES).toContain('.ck-content ul { list-style: disc;');
    expect(CKEDITOR_CONTENT_STYLES).toContain('.ck-content ol { list-style: decimal;');
    expect(CKEDITOR_CONTENT_STYLES).toContain(
      '.ck-content em, .ck-content i { font-style: italic; }',
    );
  });

  it('touches only CKEditor content', () => {
    const selectors = CKEDITOR_CONTENT_STYLES.split('}')
      .map((rule) => rule.split('{')[0].trim())
      .filter(Boolean);

    expect(selectors.length).toBeGreaterThan(0);
    selectors
      .flatMap((selector) => selector.split(','))
      .forEach((selector) => expect(selector.trim()).toMatch(/^\.ck-content /));
  });
});
