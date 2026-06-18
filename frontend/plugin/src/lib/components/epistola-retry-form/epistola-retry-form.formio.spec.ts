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
 * Lifecycle tests for the wrapper registered by {@link registerEpistolaRetryFormComponent}: it forwards
 * the server-prefilled task id (from the hidden carrier field) onto the Angular element on attach, and —
 * since the retry form is part of the plugin's auto-deployed form, not a drop-anywhere component — hides
 * itself from the builder palette. The Angular component and @valtimo/components are mocked so the wrapper
 * runs in the plain node/ts-jest environment, with a fake Formio.Components registry.
 */
jest.mock('./epistola-retry-form.component', () => ({ EpistolaRetryFormComponent: class {} }));
jest.mock('@valtimo/components', () => ({ registerCustomFormioComponent: jest.fn() }));

import { registerEpistolaRetryFormComponent } from './epistola-retry-form.formio';

const TYPE = 'epistola-retry-form';

class FakeBaseComponent {
  attach(element: unknown) {
    return element;
  }
}

describe('epistola-retry-form Formio wrapper', () => {
  let registeredClass: any;
  let components: Record<string, any>;

  beforeEach(() => {
    components = { [TYPE]: FakeBaseComponent };
    registeredClass = undefined;
    (globalThis as any).customElements = { get: () => undefined, define: jest.fn() };
    (globalThis as any).window = {
      Formio: {
        Components: {
          components,
          setComponent: (type: string, cls: any) => {
            components[type] = cls;
            registeredClass = cls;
          },
        },
      },
    };
    registerEpistolaRetryFormComponent({} as any);
  });

  afterEach(() => {
    delete (globalThis as any).window;
    delete (globalThis as any).customElements;
  });

  function createInstance(root: any) {
    const inst: any = new registeredClass();
    inst.root = root;
    inst._customAngularElement = {};
    return inst;
  }

  it('registers a wrapper subclass', () => {
    expect(registeredClass).toBeDefined();
    expect(components[TYPE]).toBe(registeredClass);
  });

  it('forwards the prefilled task id onto the Angular element on attach', () => {
    const inst = createInstance({
      form: {
        components: [{ properties: { sourceKey: 'epistola:taskId' }, defaultValue: 'task-42' }],
      },
    });
    inst.attach({});
    expect(inst._customAngularElement.taskInstanceId).toBe('task-42');
  });

  it('leaves taskInstanceId unset when the form carries no prefilled task id', () => {
    const inst = createInstance({ form: { components: [] } });
    inst.attach({});
    expect(inst._customAngularElement.taskInstanceId).toBeUndefined();
  });

  it('hides itself from the builder palette', () => {
    expect((components[TYPE] as any).builderInfo).toBe(false);
  });
});
