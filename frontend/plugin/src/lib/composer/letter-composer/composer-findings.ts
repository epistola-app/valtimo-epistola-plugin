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

import type { ValidationMessages } from './composer-messages';

/**
 * Naming the fields Epistola refused, instead of showing one sentence about the letter.
 *
 * <p>The browser checks what the contract says about the fields it offered, as the employee types.
 * It cannot check the rest: most of a letter's data comes from the baseline mapping, which the
 * browser neither computed nor holds the contract for. So a letter can be refused over a field
 * nobody was asked about, and that answer only exists on the server.
 *
 * <p>Contract 1.4.0 makes it addressable. A refused render names each bad or absent field by
 * **JSON Pointer into the data**, and the composer's inputs are generated from that same data, so
 * a pointer can be matched back to the field the employee sees — see {@link describeFindings}.
 */

/** One field Epistola refused, as the plugin's API reports it. */
export interface FieldFinding {
  /** JSON Pointer (RFC 6901) into the letter's data, e.g. `/customer/email`. */
  readonly path: string;
  /** The JSON Schema keyword that failed, or `required` for an absent field. */
  readonly keyword?: string;
  /** What Epistola said, when it said anything. Absent for a field it reported as merely missing. */
  readonly message?: string;
}

/** The part of a Form.io component this module reads. */
interface LabelledComponent {
  readonly component?: { key?: string; label?: string; legend?: string };
}

/** The part of a Form.io form this module reads. */
export interface AddressableForm {
  /** Visits every component on the form, nested ones included. */
  everyComponent(visit: (component: LabelledComponent) => void): void;
}

/** One refused field, ready to show: what to call it, and what is wrong with it. */
export interface ShownFinding {
  /** The field's own label where the form has one, else its pointer. */
  readonly label: string;
  /** What the employee reads about it. */
  readonly message: string;
}

/**
 * The component key that holds the value at `pointer`, or null when no single field does.
 *
 * <p>The generator keys a scalar input by its **full dotted path** — `/applicant/address/postalCode`
 * is one component keyed `applicant.address.postalCode`, sitting inside a fieldset keyed
 * `applicant` — so the conversion is mechanical, with RFC 6901's escapes undone (`~1` is a literal
 * `/`, `~0` a literal `~`).
 *
 * <p>Null for two cases that are not a single input:
 *
 * <ul>
 *   <li>the document root (`""`), which is the letter rather than a field;</li>
 *   <li>any pointer through an array index (`/lineItems/0/quantity`), because a grid's children are
 *       keyed within a row rather than against the submission, so the index belongs to the data's
 *       shape and not to any component's key.</li>
 * </ul>
 */
export function pointerToFieldKey(pointer: string): string | null {
  if (typeof pointer !== 'string' || pointer === '' || pointer === '/') {
    return null;
  }
  if (!pointer.startsWith('/')) {
    return null;
  }
  const segments = pointer
    .slice(1)
    .split('/')
    .map((segment) => segment.replace(/~1/g, '/').replace(/~0/g, '~'));
  if (segments.some((segment) => segment === '' || /^\d+$/.test(segment))) {
    return null;
  }
  return segments.join('.');
}

/**
 * The findings, each named the way the employee sees the field named.
 *
 * <p>Shown in the composer's own markup rather than injected into Form.io's per-component error
 * slots, and that is a deliberate retreat from the obvious approach. `setCustomValidity` paints
 * only when the component has a `messageContainer`, which exists for a *rendered* component — and
 * a sectioned form renders one page at a time, so a message for a field two steps away is stored
 * and never shown. Discovered in a browser, after it looked right in every unit test.
 *
 * <p>It is also the better fit for what these findings usually are. The browser already checks the
 * fields it offered; what reaches here is mostly the half it could not — fields the baseline
 * mapping supplies, which have no input to sit under at all. A list that names them is the only
 * honest place to put those.
 *
 * <p>A label is preferred over a pointer wherever the form has one, because `/applicant/bsn` is not
 * what the employee sees above the box. A field the form does not have keeps its pointer: it is
 * less friendly and more truthful than inventing a name for something that is not on screen.
 */
export function describeFindings(
  form: AddressableForm | null | undefined,
  findings: readonly FieldFinding[],
  messages: ValidationMessages,
): ShownFinding[] {
  const labels = labelsByKey(form);
  return findings.map((finding) => {
    const key = pointerToFieldKey(finding.path);
    const label = (key !== null ? labels.get(key) : undefined) ?? finding.path;
    return { label, message: messageFor(finding, messages) };
  });
}

/** Every component's label, by the key the generator gave it. */
function labelsByKey(form: AddressableForm | null | undefined): Map<string, string> {
  const labels = new Map<string, string>();
  if (!form?.everyComponent) {
    return labels;
  }
  form.everyComponent((component) => {
    const key = component?.component?.key;
    const label = component?.component?.label ?? component?.component?.legend;
    if (typeof key === 'string' && typeof label === 'string' && label !== '' && !labels.has(key)) {
      labels.set(key, label);
    }
  });
  return labels;
}

/**
 * What the employee reads for one finding.
 *
 * <p>Epistola's own sentence is preferred when it has one: it is specific, and it is about the rule
 * that actually failed. An absent field has none — the server reports a location rather than a
 * sentence — so the composer's own wording is used, the same text the browser shows for a required
 * input left empty, with the `{{field}}` placeholder removed because the label is already beside it.
 */
function messageFor(finding: FieldFinding, messages: ValidationMessages): string {
  if (typeof finding.message === 'string' && finding.message.trim() !== '') {
    return finding.message.trim();
  }
  const template = finding.keyword === 'required' ? messages.required : messages.pattern;
  return template.replace('{{field}}', '').replace(/\s+/g, ' ').trim();
}
