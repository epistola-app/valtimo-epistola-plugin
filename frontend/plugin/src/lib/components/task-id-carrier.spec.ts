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
 * Guards the *persistence* half of the task-id mechanism.
 *
 * The read half is covered by prefilled-task-id.spec.ts and the per-component wrapper specs
 * assert the id reaches the Angular element. Neither exercised what the Formio builder actually
 * stores — and it stored the component with no carrier at all, because Formio's
 * `Component.getModifiedSchema` drops any array that deep-equals the registered default schema.
 *
 * So unlike the wrapper specs, this suite runs the **real formiojs** implementation: the defect
 * lives in Formio's own serializer, and a mocked base class cannot reproduce it. Real formiojs
 * touches `window` at import time, hence the jsdom environment above.
 *
 * Only `@valtimo/components` is mocked, because its Angular ESM bundle is not loadable under
 * ts-jest. `createCustomFormioComponent` is re-implemented over the real Formio `input`
 * component, mirroring the upstream factory (@valtimo/components fesm2022:2790-2814) — the two
 * lines that matter are that `defaultSchema` and `builderInfo.schema` come from the *same*
 * `schema()` expression, which is exactly what makes the carrier compare equal and vanish.
 */
jest.mock('@valtimo/components', () => {
  const { Components } = jest.requireActual('formiojs');
  return {
    registerCustomFormioComponent: jest.fn(),
    createCustomFormioComponent: (options: any) => {
      const BaseInput = Components.components.input;
      class CustomComponent extends BaseInput {
        static schema() {
          return BaseInput.schema({ ...options.schema, type: options.type });
        }
        static get builderInfo() {
          return {
            title: options.title,
            group: options.group,
            icon: options.icon,
            schema: CustomComponent.schema(),
          };
        }
        get defaultSchema() {
          return CustomComponent.schema();
        }
      }
      return CustomComponent;
    },
  };
});

jest.mock('./epistola-document-preview/epistola-document-preview.component', () => ({
  EpistolaDocumentPreviewComponent: class {},
}));
jest.mock('./epistola-document/epistola-document.component', () => ({
  EpistolaDocumentComponent: class {},
}));
jest.mock('./epistola-retry-form/epistola-retry-form.component', () => ({
  EpistolaRetryFormComponent: class {},
}));

import { Components } from 'formiojs';
import { registerEpistolaDocumentPreviewComponent } from './epistola-document-preview/epistola-document-preview.formio';
import { registerEpistolaDocumentComponent } from './epistola-document/epistola-document.formio';
import { registerEpistolaRetryFormComponent } from './epistola-retry-form/epistola-retry-form.formio';
import {
  PREFILLED_TASK_ID_DATA_KEY,
  PREFILLED_TASK_ID_SOURCE_KEY,
} from '../services/prefilled-task-id';

const TASK_BOUND_TYPES = [
  'epistola-document-preview',
  'epistola-document',
  'epistola-retry-form',
] as const;

function carriersOf(schema: any): any[] {
  const components = Array.isArray(schema?.components) ? schema.components : [];
  return components.filter(
    (child: any) => child?.properties?.sourceKey === PREFILLED_TASK_ID_SOURCE_KEY,
  );
}

/** Serializes a component the way Webform.schema / @formio/angular do before Valtimo persists it. */
function persistedSchemaOf(type: string, stored: any): any {
  const ComponentClass: any = (Components as any).components[type];
  const instance = new ComponentClass(JSON.parse(JSON.stringify(stored)), {}, {});
  return instance.schema;
}

function paletteDropPayloadFor(type: string): any {
  return (Components as any).components[type].builderInfo.schema;
}

describe('prefilled task-id carrier survives Formio schema serialization', () => {
  beforeAll(() => {
    const injector: any = {};
    registerEpistolaDocumentPreviewComponent(injector);
    registerEpistolaDocumentComponent(injector);
    registerEpistolaRetryFormComponent(injector);
  });

  describe.each(TASK_BOUND_TYPES)('%s', (type) => {
    it('persists the carrier when serialized straight from the palette drop payload', () => {
      // This is the exact scenario that produced a carrier-less form on the demo environment.
      const schema = persistedSchemaOf(type, paletteDropPayloadFor(type));

      const carriers = carriersOf(schema);
      expect(carriers).toHaveLength(1);
      expect(carriers[0]).toEqual(
        expect.objectContaining({
          key: PREFILLED_TASK_ID_DATA_KEY,
          type: 'hidden',
          persistent: false,
        }),
      );
    });

    it('does not duplicate a carrier the stored form already has', () => {
      // Hand-authored forms (e.g. the classpath retry form) already carry it.
      const schema = persistedSchemaOf(type, {
        type,
        key: 'alreadyAuthored',
        components: [
          {
            type: 'hidden',
            key: PREFILLED_TASK_ID_DATA_KEY,
            input: true,
            persistent: false,
            label: 'Epistola Task Id',
            properties: { sourceKey: PREFILLED_TASK_ID_SOURCE_KEY },
          },
        ],
      });

      expect(carriersOf(schema)).toHaveLength(1);
    });

    it('recognises a carrier whose key the builder uniquified', () => {
      // Formio renames colliding keys across a form, so the carrier of the SECOND Epistola
      // component on a form arrives as epistolaTaskId2. Matching on the key would miss it and
      // append a duplicate.
      const schema = persistedSchemaOf(type, {
        type,
        key: 'secondOnTheForm',
        components: [
          {
            type: 'hidden',
            key: `${PREFILLED_TASK_ID_DATA_KEY}2`,
            input: true,
            persistent: false,
            label: 'Epistola Task Id',
            properties: { sourceKey: PREFILLED_TASK_ID_SOURCE_KEY },
          },
        ],
      });

      const carriers = carriersOf(schema);
      expect(carriers).toHaveLength(1);
      expect(carriers[0].key).toBe(`${PREFILLED_TASK_ID_DATA_KEY}2`);
    });

    it('collapses carriers that a previous save duplicated', () => {
      const schema = persistedSchemaOf(type, {
        type,
        key: 'previouslyDuplicated',
        components: [
          {
            type: 'hidden',
            key: `${PREFILLED_TASK_ID_DATA_KEY}2`,
            input: true,
            persistent: false,
            properties: { sourceKey: PREFILLED_TASK_ID_SOURCE_KEY },
          },
          {
            type: 'hidden',
            key: PREFILLED_TASK_ID_DATA_KEY,
            input: true,
            persistent: false,
            properties: { sourceKey: PREFILLED_TASK_ID_SOURCE_KEY },
          },
        ],
      });

      expect(carriersOf(schema)).toHaveLength(1);
    });

    it('preserves sibling children while adding the carrier', () => {
      const schema = persistedSchemaOf(type, {
        type,
        key: 'withSiblings',
        components: [{ type: 'textfield', key: 'someOtherChild', input: true }],
      });

      const keys = schema.components.map((child: any) => child.key);
      expect(keys).toContain('someOtherChild');
      expect(keys).toContain(PREFILLED_TASK_ID_DATA_KEY);
    });
  });

  it('still adds the carrier if a future Formio drops getModifiedSchema', () => {
    // Forward guard: degrade to an unfiltered (verbose but loadable) schema rather than throwing
    // a TypeError from super.getModifiedSchema and taking the form builder down.
    const { withPrefilledTaskIdCarrier } = jest.requireActual('./valtimo-formio-adapter');
    class BaseWithoutTheFilter {}
    const Enhanced: any = withPrefilledTaskIdCarrier(BaseWithoutTheFilter as any);

    const modified = new Enhanced().getModifiedSchema(
      { type: 'epistola-document-preview', key: 'probe' },
      {},
      false,
    );

    expect(carriersOf(modified)).toHaveLength(1);
    expect(modified.key).toBe('probe');
  });

  it('pins the upstream behaviour the enhancer works around', () => {
    // A stock Formio component whose default schema declares the carrier loses it on
    // serialization — the deep-equal-to-default array is classified "unmodified" and dropped.
    // If this ever fails, Formio changed getModifiedSchema and the enhancer can be revisited.
    const { createCustomFormioComponent } = jest.requireMock('@valtimo/components') as any;
    const Unenhanced: any = createCustomFormioComponent({
      type: 'epistola-unenhanced-probe',
      schema: {
        components: [
          { type: 'hidden', key: 'probe', properties: { sourceKey: PREFILLED_TASK_ID_SOURCE_KEY } },
        ],
      },
    });

    const instance = new Unenhanced(
      JSON.parse(JSON.stringify(Unenhanced.builderInfo.schema)),
      {},
      {},
    );

    expect(carriersOf(instance.schema)).toHaveLength(0);
  });
});
