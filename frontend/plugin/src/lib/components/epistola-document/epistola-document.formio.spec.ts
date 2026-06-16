/**
 * Lifecycle tests for the wrapper registered by {@link registerEpistolaDocumentComponent}: it forwards
 * the server-prefilled task id (from the hidden carrier field) onto the Angular element on attach. The
 * Angular component and @valtimo/components are mocked so the wrapper runs in the plain node/ts-jest
 * environment, with a fake Formio.Components registry capturing the subclass that setComponent registers.
 */
jest.mock('./epistola-document.component', () => ({ EpistolaDocumentComponent: class {} }));
jest.mock('@valtimo/components', () => ({ registerCustomFormioComponent: jest.fn() }));

import { registerEpistolaDocumentComponent } from './epistola-document.formio';

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
    registerEpistolaDocumentComponent({} as any);
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
