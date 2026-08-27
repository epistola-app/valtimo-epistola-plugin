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
 * Recursively collect input field keys and labels from a Formio component tree.
 * Skips Epistola custom components and their subtrees: they are builder UI and
 * carrier plumbing, not form fields.
 *
 * Only the form currently open in the builder is walked, so fields belonging to
 * other Form Flow steps never appear here — see the note under the Form Field
 * dropdown and docs/form-flows.md.
 */
export function collectFormFields(components: any[]): { key: string; label: string }[] {
  const fields: { key: string; label: string }[] = [];
  for (const comp of components) {
    const isEpistolaComponent = !!comp.type?.startsWith('epistola-');
    if (comp.input && comp.key && comp.type !== 'button' && !isEpistolaComponent) {
      fields.push({ key: comp.key, label: comp.label || comp.key });
    }
    // Don't descend into an Epistola component: its nested `components` array is
    // reserved for the hidden task-id carrier (see docs/formio-components.md),
    // which is plumbing rather than a form field an author should be offered.
    if (comp.components && !isEpistolaComponent) {
      fields.push(...collectFormFields(comp.components));
    }
    if (comp.columns) {
      for (const col of comp.columns) {
        if (col.components) {
          fields.push(...collectFormFields(col.components));
        }
      }
    }
  }
  return fields;
}
