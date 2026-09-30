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
 * Break a generated input form into steps once there is a lot to fill in.
 *
 * <p>A letter's contract decides how much the employee is asked for, and some letters ask for a
 * great deal. One long column of inputs next to a preview is hard to work through and hard to come
 * back to, so above a threshold the form becomes a Form.io wizard: one step per section, freely
 * navigable, with the preview alongside throughout.
 *
 * <p>This runs in the browser rather than in the generator on purpose. It is presentation only —
 * the same fields, the same keys, the same submission — so the form the backend generates stays
 * the canonical one, and the step titles can be translated, which the backend has no locale for.
 */

/** Above this many inputs, the form is worth stepping through. */
export const SECTION_THRESHOLD = 6;

/** The Formio layout components the generator emits as a group of fields. */
const GROUP_TYPES = ['fieldset', 'panel', 'container', 'well'];

/**
 * A wizard version of `form`, or `form` itself when there is little enough to fill in.
 *
 * @param form     the generated form definition, untouched on return
 * @param titleFor names a step that the contract did not name, given its 1-based number
 */
export function sectionForm(
  form: any,
  titleFor: (step: number) => string,
  threshold: number = SECTION_THRESHOLD,
): any {
  const components: any[] = Array.isArray(form?.components) ? form.components : [];
  if (!components.length || countInputs(components) <= threshold) {
    return form;
  }

  const pages: any[] = [];
  let loose: any[] = [];

  /** Turn whatever ungrouped fields have piled up into their own step(s). */
  const flushLoose = () => {
    for (const chunk of chunked(loose, threshold)) {
      // A step that is one field is that field, and it already has a name — better than a number.
      const own = chunk.length === 1 ? nameOf(chunk[0]) : null;
      pages.push(page(own || titleFor(pages.length + 1), chunk));
    }
    loose = [];
  };

  for (const component of components) {
    const children = groupChildrenOf(component);
    if (!children) {
      loose.push(component);
      continue;
    }
    // A named group is a section the template already describes — keep its name, and keep it
    // whole unless it is a lot on its own, in which case it is split into numbered parts. Two
    // steps carrying the same name would be indistinguishable in the breadcrumbs.
    flushLoose();
    const title = nameOf(component);
    const chunks = chunked(children, threshold);
    chunks.forEach((chunk, index) => {
      const name = title || titleFor(pages.length + 1);
      pages.push(page(chunks.length > 1 ? `${name} ${index + 1}/${chunks.length}` : name, chunk));
    });
  }
  flushLoose();

  // One step is not a wizard, it is the same form with a header on it.
  if (pages.length < 2) {
    return form;
  }
  return { ...form, display: 'wizard', components: pages };
}

/** Whether `sectionForm` turned this definition into steps. */
export function isSectioned(form: any): boolean {
  return form?.display === 'wizard';
}

/**
 * The children of a layout group, or null when this is a field rather than a group.
 *
 * <p>The children are lifted out of the group and onto the step: their keys are full paths
 * already, and a legend inside a step titled the same thing is noise. A `datagrid` is deliberately
 * not a group — it is one input that happens to contain components.
 */
function groupChildrenOf(component: any): any[] | null {
  if (!GROUP_TYPES.includes(component?.type)) {
    return null;
  }
  const children = component?.components;
  return Array.isArray(children) && children.length ? children : null;
}

/** How many things the employee actually has to fill in, groups counted by their contents. */
function countInputs(components: any[]): number {
  return components.reduce((total, component) => {
    const children = groupChildrenOf(component);
    return total + (children ? countInputs(children) : 1);
  }, 0);
}

/** Whatever this component calls itself, in the order Formio's own layouts use. */
function nameOf(component: any): string | null {
  const name = component?.legend || component?.label || component?.title;
  return typeof name === 'string' && name.trim() ? name : null;
}

function chunked<T>(items: T[], size: number): T[][] {
  const chunks: T[][] = [];
  for (let index = 0; index < items.length; index += size) {
    chunks.push(items.slice(index, index + size));
  }
  return chunks;
}

function page(title: string, components: any[]): any {
  return {
    type: 'panel',
    key: `epistolaSection${title.replace(/\W+/g, '')}${components[0]?.key ?? ''}`,
    title,
    label: title,
    input: false,
    // Per-page settings, because Formio's wizard takes these over the ones passed in options
    // (Wizard.hasButton) — and options alone left a Cancel and a Submit Form button on the last
    // step. Neither belongs here: the composer is embedded in a Valtimo form that has its own
    // submit, and a second one inside it would submit the wrong thing. Previous and Next stay, and
    // Formio only renders them when there is a step to go to.
    buttonSettings: { previous: true, next: true, cancel: false, submit: false },
    components,
  };
}
