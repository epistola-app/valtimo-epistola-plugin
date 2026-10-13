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
 * The key a tagged rich-text value is wrapped in. Mirrors `RichTextValues.TAG` in the backend.
 */
export const RICH_TEXT_TAG = '$richText';

/**
 * The property a generated rich-text component carries: the format its editor writes and the
 * field's kind. Mirrors `FormioFormGenerator.RICH_TEXT_PROPERTY` in the backend.
 */
export const RICH_TEXT_PROPERTY = 'epistolaRichText';

export interface RichTextTag {
  format: string;
  kind?: 'INLINE' | 'BLOCK';
  value: string;
}

/**
 * The data with what was typed into each rich-text editor tagged with that editor's format (ADR
 * 0008): `"<p>…</p>"` becomes `{"$richText": {"format": "html", "kind": "BLOCK", "value": "<p>…</p>"}}`.
 *
 * The editor is the only thing that knows which format it wrote, so it says so, here, and the value
 * carries that wherever it goes — through the composer's merge with mapped data, onto a process
 * variable, into a list. The backend turns tags into documents as the data is sent to Epistola, and
 * sends everything else as it is.
 *
 * Which components are editors comes from the generated form: the backend marks each rich-text
 * component with {@link RICH_TEXT_PROPERTY}. Keys follow the generator's layout — dotted paths
 * against the submission, relative to the row inside a data grid. `data` is not modified.
 */
export function tagRichText<T>(form: { components?: any[] } | null | undefined, data: T): T {
  if (!form?.components || data === null || typeof data !== 'object') {
    return data;
  }
  const copy = structuredClone(data) as any;
  tagIn(form.components, copy);
  return copy;
}

function tagIn(components: any[] | undefined, scope: any): void {
  for (const component of components ?? []) {
    if (!component || typeof component !== 'object') {
      continue;
    }
    const marker = component[RICH_TEXT_PROPERTY];
    if (marker?.format && component.key) {
      update(scope, component.key, (value) => tagged(value, marker));
      continue;
    }
    if (['datagrid', 'editgrid'].includes(component.type) && component.key) {
      // A grid's children are keyed within its row, so each row is their scope.
      const rows = read(scope, component.key);
      if (Array.isArray(rows)) {
        rows.forEach((row) => tagIn(component.components, row));
      }
      continue;
    }
    if (component.type === 'container' && component.key) {
      tagIn(component.components, read(scope, component.key));
      continue;
    }
    // Layout — a fieldset, a panel, a wizard step, columns — adds no data level of its own.
    tagIn(component.components, scope);
    for (const column of component.columns ?? []) {
      tagIn(column?.components, scope);
    }
  }
}

/** What was typed, tagged; a list of editors is tagged item by item; anything else is left. */
function tagged(value: unknown, marker: { format: string; kind?: RichTextTag['kind'] }): unknown {
  if (typeof value === 'string') {
    const tag: RichTextTag = { format: marker.format, value };
    if (marker.kind) {
      tag.kind = marker.kind;
    }
    return { [RICH_TEXT_TAG]: tag };
  }
  if (Array.isArray(value)) {
    return value.map((item) => tagged(item, marker));
  }
  return value;
}

function read(scope: any, key: string): any {
  return key
    .split('.')
    .reduce<any>((node, segment) => (node == null ? undefined : node[segment]), scope);
}

function update(scope: any, key: string, change: (value: unknown) => unknown): void {
  if (scope == null || typeof scope !== 'object') {
    return;
  }
  const segments = key.split('.');
  const last = segments.pop()!;
  const parent = segments.length ? read(scope, segments.join('.')) : scope;
  if (parent && typeof parent === 'object' && last in parent) {
    parent[last] = change(parent[last]);
  }
}
