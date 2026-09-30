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
import { isSectioned, sectionForm, SECTION_THRESHOLD } from './composer-sections';

const titleFor = (step: number) => `Stap ${step}`;

function fields(count: number, prefix = 'veld'): any[] {
  return Array.from({ length: count }, (_, index) => ({
    type: 'textfield',
    key: `${prefix}${index + 1}`,
    input: true,
  }));
}

function form(components: any[]): any {
  return { display: 'form', components };
}

describe('sectionForm', () => {
  describe('leaves a short form alone', () => {
    it('for a form at the threshold', () => {
      const original = form(fields(SECTION_THRESHOLD));

      expect(sectionForm(original, titleFor)).toBe(original);
    });

    it('for an empty form', () => {
      const original = form([]);

      expect(sectionForm(original, titleFor)).toBe(original);
    });

    it('for the demo letter that asks for three fields', () => {
      // The composer's promise is that a small letter stays a small form; a three-field letter
      // behind Back/Next would be worse than what it replaces.
      const original = form(fields(3));

      expect(isSectioned(sectionForm(original, titleFor))).toBe(false);
    });
  });

  describe('steps through a long form', () => {
    it('splits ungrouped fields into numbered steps of at most the threshold', () => {
      const sectioned = sectionForm(form(fields(14)), titleFor);

      expect(sectioned.display).toBe('wizard');
      expect(sectioned.components).toHaveLength(3);
      expect(sectioned.components.map((page: any) => page.title)).toEqual([
        'Stap 1',
        'Stap 2',
        'Stap 3',
      ]);
      expect(sectioned.components.map((page: any) => page.components.length)).toEqual([6, 6, 2]);
    });

    it('keeps every field, in order, and does not touch their keys', () => {
      const original = form(fields(14));

      const keys = sectionForm(original, titleFor).components.flatMap((page: any) =>
        page.components.map((child: any) => child.key),
      );

      expect(keys).toEqual(original.components.map((child: any) => child.key));
    });

    it('leaves the original definition untouched', () => {
      const original = form(fields(14));

      sectionForm(original, titleFor);

      expect(original.display).toBe('form');
      expect(original.components).toHaveLength(14);
    });
  });

  describe('uses the sections the contract already describes', () => {
    it('leaves an unsplit group\u2019s name alone', () => {
      const sectioned = sectionForm(
        form([
          { type: 'fieldset', legend: 'Aanhef', key: 'aanhef', components: fields(4, 'a') },
          { type: 'fieldset', legend: 'Besluit', key: 'besluit', components: fields(4, 'b') },
        ]),
        titleFor,
      );

      expect(sectioned.components.map((page: any) => page.title)).toEqual(['Aanhef', 'Besluit']);
    });

    it('gives each group its own step, named by its legend', () => {
      const sectioned = sectionForm(
        form([
          { type: 'fieldset', legend: 'Aanhef', key: 'aanhef', components: fields(2, 'a') },
          { type: 'fieldset', legend: 'Besluit', key: 'besluit', components: fields(5, 'b') },
        ]),
        titleFor,
      );

      expect(sectioned.components.map((page: any) => page.title)).toEqual(['Aanhef', 'Besluit']);
    });

    /** A group is a layout wrapper, so its children carry full paths and can be lifted onto the
     * step — a legend inside a step named the same thing is noise. */
    it('lifts a group’s fields onto the step rather than nesting them', () => {
      const sectioned = sectionForm(
        form([
          { type: 'fieldset', legend: 'Aanhef', key: 'aanhef', components: fields(4, 'a') },
          { type: 'fieldset', legend: 'Besluit', key: 'besluit', components: fields(4, 'b') },
        ]),
        titleFor,
      );

      expect(sectioned.components[0].components.map((child: any) => child.type)).toEqual(
        Array(4).fill('textfield'),
      );
    });

    it('splits a group that is a lot on its own, keeping its name', () => {
      const sectioned = sectionForm(
        form([
          { type: 'fieldset', legend: 'Besluit', key: 'besluit', components: fields(13, 'b') },
        ]),
        titleFor,
      );

      // Numbered, because three breadcrumbs all reading "Besluit" name nothing.
      expect(sectioned.components.map((page: any) => page.title)).toEqual([
        'Besluit 1/3',
        'Besluit 2/3',
        'Besluit 3/3',
      ]);
    });

    it('keeps ungrouped fields out of the group that follows them', () => {
      const sectioned = sectionForm(
        form([
          ...fields(2, 'los'),
          { type: 'fieldset', legend: 'Besluit', key: 'besluit', components: fields(5, 'b') },
        ]),
        titleFor,
      );

      expect(sectioned.components.map((page: any) => page.title)).toEqual(['Stap 1', 'Besluit']);
      expect(sectioned.components[0].components.map((child: any) => child.key)).toEqual([
        'los1',
        'los2',
      ]);
    });

    /**
     * A datagrid contains components but is a single input. Treating it as a group would strip its
     * grid and scatter item columns across steps as if they were top-level fields.
     */
    it('treats a datagrid as one input, not as a group', () => {
      const grid = { type: 'datagrid', key: 'bijlagen', input: true, components: fields(9, 'kol') };
      const sectioned = sectionForm(form([grid, ...fields(6, 'x')]), titleFor);

      expect(sectioned.components[0].components[0]).toBe(grid);
      expect(sectioned.components).toHaveLength(2);
    });
  });

  it('names a one-field step after that field', () => {
    // The permit letter's last step is the `activities` grid on its own; "Step 3" says less than
    // the name the field already carries.
    const sectioned = sectionForm(
      form([
        { type: 'fieldset', legend: 'Aanvrager', key: 'a', components: fields(7, 'a') },
        { type: 'datagrid', key: 'activities', label: 'Activities', input: true, components: [] },
      ]),
      titleFor,
    );

    expect(sectioned.components.map((page: any) => page.title)).toEqual([
      'Aanvrager 1/2',
      'Aanvrager 2/2',
      'Activities',
    ]);
  });

  it('still numbers a step that holds several unnamed fields', () => {
    const sectioned = sectionForm(form(fields(14)), titleFor);

    expect(sectioned.components.map((page: any) => page.title)).toEqual([
      'Stap 1',
      'Stap 2',
      'Stap 3',
    ]);
  });

  it('gives every step the button settings that suppress Cancel and Submit', () => {
    // Formio's wizard takes a page's own buttonSettings over the ones passed in options, and with
    // options alone it rendered a Cancel and a Submit Form button inside a Valtimo form that has
    // its own submit.
    const sectioned = sectionForm(form(fields(14)), titleFor);

    for (const page of sectioned.components) {
      expect(page.buttonSettings).toEqual({
        previous: true,
        next: true,
        cancel: false,
        submit: false,
      });
    }
  });

  it('does not make a wizard out of a single step', () => {
    // One group holding everything is still one page; Back/Next with nowhere to go is chrome.
    const original = form([
      { type: 'fieldset', legend: 'Besluit', key: 'besluit', components: fields(6, 'b') },
      ...fields(1, 'x'),
    ]);

    const sectioned = sectionForm(original, titleFor, 7);

    expect(sectioned).toBe(original);
  });
});
