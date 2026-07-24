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
 * Lifecycle tests for the wrapper registered by {@link registerEpistolaDocumentComponent}: it forwards
 * the server-prefilled task id (from the hidden carrier field) onto the Angular element on attach. The
 * Angular component and @valtimo/components are mocked so the wrapper runs in the plain node/ts-jest
 * environment, with a fake Formio.Components registry capturing the subclass that setComponent registers.
 */
jest.mock('./epistola-document.component', () => ({ EpistolaDocumentComponent: class {} }));
jest.mock('@valtimo/components', () => ({
  createCustomFormioComponent: jest.fn(),
  registerCustomFormioComponent: jest.fn(),
}));

jest.mock('formiojs', () => {
  const components = {
    components: {} as Record<string, any>,
    setComponent: jest.fn((type: string, cls: any) => {
      components.components[type] = cls;
    }),
  };
  return { Components: components };
});

import { Components } from 'formiojs';
import { createCustomFormioComponent } from '@valtimo/components';
import { registerEpistolaDocumentComponent } from './epistola-document.formio';

const mockComponents = Components as any;
const TYPE = 'epistola-document';

class FakeBaseComponent {
  attach(element: unknown) {
    return element;
  }
}

describe('epistola-document Formio wrapper', () => {
  let registeredClass: any;
  let components: Record<string, any>;

  beforeEach(() => {
    components = { [TYPE]: FakeBaseComponent };
    mockComponents.components = components;
    mockComponents.setComponent.mockClear();
    (createCustomFormioComponent as jest.Mock).mockReturnValue(FakeBaseComponent);
    registeredClass = undefined;
    (globalThis as any).customElements = { get: () => undefined, define: jest.fn() };
    registerEpistolaDocumentComponent({} as any);
    registeredClass = components[TYPE];
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

  it('does not depend on window.Formio to register the wrapper subclass', () => {
    expect((globalThis as any).window?.Formio).toBeUndefined();
    expect(mockComponents.setComponent).toHaveBeenCalledWith(TYPE, registeredClass);
  });

  it('forwards the prefilled task id onto the Angular element on attach', () => {
    const inst = createInstance({
      form: {
        components: [{ properties: { sourceKey: 'epistola:taskId' }, defaultValue: 'task-9' }],
      },
    });
    inst.attach({});
    expect(inst._customAngularElement.taskInstanceId).toBe('task-9');
  });

  it('leaves taskInstanceId unset when the form carries no prefilled task id', () => {
    const inst = createInstance({ form: { components: [] } });
    inst.attach({});
    expect(inst._customAngularElement.taskInstanceId).toBeUndefined();
  });
});
