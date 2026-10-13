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

/** Marks the style element, so a second call finds it rather than adding another. */
export const CKEDITOR_LIST_STYLES_ID = 'epistola-ckeditor-list-styles';

/**
 * List markers inside CKEditor's content, which Valtimo's styling removes.
 *
 * Valtimo loads Carbon's reset globally (`@valtimo/components/assets/css/carbon.scss`), and it
 * sets `ol, ul { list-style: none }` and zeroes their padding. CKEditor draws lists with the
 * browser's default markers and Form.io only adds `margin-left: 10px`, so in a Valtimo form a
 * list is indented text without bullets or numbers. Valtimo restores markers for its own text
 * widget only (`.valtimo-widget-text__content`, in `@valtimo/layout`); nothing does for CKEditor
 * as of 13.48. The letter is unaffected: this is the editor's display alone.
 *
 * The same restore, scoped to `.ck-content` (CKEditor's editable and read-only content), so it
 * touches nothing else on the page. It applies to every CKEditor in the host, Valtimo's own WYSIWYG
 * text areas included, which have the same defect. Remove it once Valtimo ships its own.
 */
export const CKEDITOR_LIST_STYLES = `
.ck-content ul { list-style: disc; padding-left: 1.5em; }
.ck-content ul ul { list-style: circle; }
.ck-content ul ul ul { list-style: square; }
.ck-content ol { list-style: decimal; padding-left: 1.5em; }
`;

/** The part of `Document` this needs, so it can be tested without a DOM. */
export interface StyleHost {
  getElementById(id: string): unknown;
  createElement(tagName: 'style'): { id: string; textContent: string | null };
  head: { appendChild(node: unknown): unknown };
}

/** Add the list styles to the page once. */
export function installCkEditorListStyles(host: StyleHost | undefined = globalThis.document): void {
  if (!host?.head || host.getElementById(CKEDITOR_LIST_STYLES_ID)) {
    return;
  }
  const style = host.createElement('style');
  style.id = CKEDITOR_LIST_STYLES_ID;
  style.textContent = CKEDITOR_LIST_STYLES;
  host.head.appendChild(style);
}
