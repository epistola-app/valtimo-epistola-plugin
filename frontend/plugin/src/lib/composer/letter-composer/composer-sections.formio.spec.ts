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
 *
 * @jest-environment jsdom
 */

/**
 * The stepped form, against the **real** Formio wizard.
 *
 * `composer-sections.spec.ts` covers what the sectioning produces; this covers what Formio then
 * does with it, which is a different question and the one that has actually gone wrong. The
 * behaviour relied on here is undocumented: a panel becomes a page, a page's own `buttonSettings`
 * beat the ones passed in options, and breadcrumbs are clickable unless told otherwise. Options
 * alone left a Cancel and a Submit Form button inside a Valtimo form that has its own submit, and
 * nothing but a browser noticed.
 *
 * Form.io is exact-pinned at 4.19.5 across the whole supported Valtimo range, so this can only
 * change on a deliberate bump — which is exactly when a silent change would be worst. Real
 * formiojs touches `window` at import time, hence the jsdom environment above.
 */
// The barrel is imported first for its side effect: it is what registers the built-in components,
// without which the wizard cannot build its pages. The wizard itself comes from its own path,
// because the barrel does not name it in its typings.
import { Components } from 'formiojs';
import WizardModule from 'formiojs/Wizard';
import { sectionForm } from './composer-sections';

const Wizard: any = (WizardModule as any).default ?? WizardModule;

beforeAll(() => {
  // Fail loudly rather than mysteriously if the barrel ever stops registering them.
  expect(Object.keys((Components as any).components)).toContain('panel');
});

const titleFor = (step: number) => `Stap ${step}`;

function fields(count: number, prefix = 'veld'): any[] {
  return Array.from({ length: count }, (_, index) => ({
    type: 'textfield',
    key: `${prefix}${index + 1}`,
    input: true,
  }));
}

/** A wizard over the sectioned form, set up the way the composer sets one up. */
async function wizardOver(components: any[]): Promise<any> {
  const form = sectionForm({ display: 'form', components }, titleFor);
  const wizard: any = new Wizard(document.createElement('div'), {
    noAlerts: true,
    breadcrumbSettings: { clickable: true },
    buttonSettings: { showCancel: false, showSubmit: false, showPrevious: true, showNext: true },
  });
  await wizard.setForm(form);
  return wizard;
}

describe('the sectioned form, in a real Formio wizard', () => {
  it('turns each step into a page', async () => {
    const wizard = await wizardOver(fields(14));

    expect(wizard.pages).toHaveLength(3);
    expect(wizard.pages.map((page: any) => page.component.title)).toEqual([
      'Stap 1',
      'Stap 2',
      'Stap 3',
    ]);
  });

  it('offers no Cancel and no Submit, on any page', async () => {
    // The composer is embedded in a Valtimo form that has its own submit; a second one inside it
    // would submit the wrong thing. Page-level buttonSettings are what actually decides this —
    // Wizard.hasButton reads `currentPage.component.buttonSettings` before the options.
    const wizard = await wizardOver(fields(14));

    for (let page = 0; page < wizard.pages.length; page++) {
      wizard.setPage(page);
      expect(wizard.hasButton('cancel')).toBe(false);
      expect(wizard.hasButton('submit')).toBe(false);
    }
  });

  it('offers Next until the last step and Previous after the first', async () => {
    const wizard = await wizardOver(fields(14));

    wizard.setPage(0);
    expect(wizard.hasButton('next')).toBe(true);
    expect(wizard.hasButton('previous')).toBe(false);

    wizard.setPage(wizard.pages.length - 1);
    expect(wizard.hasButton('next')).toBe(false);
    expect(wizard.hasButton('previous')).toBe(true);
  });

  it('keeps the breadcrumbs clickable, so a step is one click away', async () => {
    const wizard = await wizardOver(fields(14));

    expect(wizard.options.breadcrumbSettings.clickable).not.toBe(false);
  });

  it('keeps every field reachable, under the key the contract gave it', async () => {
    // Steps are presentation: the submission must look exactly as it would have unsectioned.
    const wizard = await wizardOver(fields(14));

    const keys = wizard.pages.flatMap((page: any) =>
      page.component.components.map((child: any) => child.key),
    );
    expect(keys).toEqual(fields(14).map((field) => field.key));
  });

  it('is not a wizard at all below the threshold', async () => {
    // The short case must stay a plain form, or every three-field letter grows navigation.
    const form = sectionForm({ display: 'form', components: fields(3) }, titleFor);

    expect(form.display).toBe('form');
  });
});
