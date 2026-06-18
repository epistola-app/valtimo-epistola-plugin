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
 * Lifecycle tests for the {@code PreviewWithOverrides} Formio wrapper registered by
 * {@link registerEpistolaDocumentPreviewComponent}. These guard the regression where a
 * debounced preview kept firing during/after task submission (POST /preview with reset
 * data → Epistola 400). The Angular component and @valtimo/components are mocked so the
 * wrapper class can be exercised in the plain node/ts-jest environment.
 */

// Cut the Angular import chain — we only need the Formio wrapper, not the element.
jest.mock('./epistola-document-preview.component', () => ({
  EpistolaDocumentPreviewComponent: class {},
}));

// registerCustomFormioComponent is a no-op here; the base class is pre-seeded into the
// fake Formio registry below so the wrapper extends it.
jest.mock('@valtimo/components', () => ({
  registerCustomFormioComponent: jest.fn(),
}));

import { registerEpistolaDocumentPreviewComponent } from './epistola-document-preview.formio';

const TYPE = 'epistola-document-preview';

class FakeBaseComponent {
  attach(element: unknown) {
    return element;
  }
  detach() {
    return undefined;
  }
  setValue(_value: unknown) {
    // overridden per-instance with a jest.fn()
  }
}

interface FakeRoot {
  data: Record<string, unknown>;
  submitting: boolean;
  submitted: boolean;
  on: jest.Mock;
  off: jest.Mock;
}

describe('PreviewWithOverrides (epistola-document-preview Formio wrapper)', () => {
  let registeredClass: any;
  let components: Record<string, any>;

  beforeEach(() => {
    jest.useFakeTimers();

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

    registerEpistolaDocumentPreviewComponent({} as any);
  });

  afterEach(() => {
    jest.useRealTimers();
    delete (globalThis as any).window;
    delete (globalThis as any).customElements;
  });

  function createInstance(opts: {
    overrideMapping?: Record<string, any>;
    data?: Record<string, unknown>;
  }) {
    const handlers: Record<string, () => void> = {};
    const root: FakeRoot = {
      data: opts.data ?? {},
      submitting: false,
      submitted: false,
      on: jest.fn((event: string, handler: () => void) => {
        handlers[event] = handler;
      }),
      off: jest.fn(),
    };

    const inst: any = new registeredClass();
    inst.component = { overrideMapping: opts.overrideMapping };
    inst.root = root;
    inst.setValue = jest.fn();

    return { inst, root, handlers };
  }

  it('registers the wrapper as the component implementation', () => {
    expect(registeredClass).toBeDefined();
    expect(components[TYPE]).toBe(registeredClass);
  });

  it('forwards the server-prefilled task id to the Angular element on attach', () => {
    const { inst, root } = createInstance({});
    // Carrier hidden field prefilled by the epistola: value resolver.
    (root as any).form = {
      components: [
        { properties: { sourceKey: 'epistola:taskId' }, defaultValue: 'task-from-prefill' },
      ],
    };
    inst._customAngularElement = {};

    inst.attach({});

    expect(inst._customAngularElement.taskInstanceId).toBe('task-from-prefill');
  });

  it('leaves taskInstanceId unset when the form carries no prefilled task id', () => {
    const { inst } = createInstance({});
    inst._customAngularElement = {};

    inst.attach({});

    expect(inst._customAngularElement.taskInstanceId).toBeUndefined();
  });

  it('schedules a debounced preview on change and pushes computed overrides', async () => {
    const { inst, root, handlers } = createInstance({
      overrideMapping: { doc: { name: 'form:nameField' } },
      data: { nameField: 'Alice' },
    });

    inst.attach({});

    // The change listener is wired up...
    expect(root.on).toHaveBeenCalledWith('change', expect.any(Function));

    // ...and the initial compute (scheduled by attach) fires after the debounce.
    await jest.advanceTimersByTimeAsync(1500);
    expect(inst.setValue).toHaveBeenCalledWith({ doc: { name: 'Alice' } });

    // A subsequent change re-computes from the latest form data.
    inst.setValue.mockClear();
    root.data = { nameField: 'Bob' };
    handlers['change']();
    await jest.advanceTimersByTimeAsync(1500);
    expect(inst.setValue).toHaveBeenCalledWith({ doc: { name: 'Bob' } });
  });

  it('fires the initial override compute immediately (no 1.5s debounce)', async () => {
    const { inst } = createInstance({
      overrideMapping: { doc: { name: 'form:nameField' } },
      data: { nameField: 'Alice' },
    });

    inst.attach({});

    // The initial compute is scheduled at 0ms, so it runs without waiting 1.5s.
    await jest.advanceTimersByTimeAsync(0);
    expect(inst.setValue).toHaveBeenCalledWith({ doc: { name: 'Alice' } });
  });

  it('pushes null when the form has no usable mapped data (reverts to placeholder)', async () => {
    const { inst } = createInstance({
      overrideMapping: { doc: { name: 'form:nameField' } },
      data: {}, // nameField not filled in
    });

    inst.attach({});

    await jest.advanceTimersByTimeAsync(1500);
    expect(inst.setValue).toHaveBeenCalledWith(null);
  });

  it('cancels a pending debounce and removes the change listener on detach', async () => {
    const { inst, root } = createInstance({
      overrideMapping: { doc: { name: 'form:nameField' } },
      data: { nameField: 'Alice' },
    });

    inst.attach({}); // schedules the initial compute

    inst.detach();

    await jest.advanceTimersByTimeAsync(1500);
    expect(inst.setValue).not.toHaveBeenCalled();
    expect(root.off).toHaveBeenCalledWith('change', expect.any(Function));
  });

  it('resumes firing previews after a Formio redraw (detach then re-attach)', async () => {
    const { inst, root } = createInstance({
      overrideMapping: { doc: { name: 'form:nameField' } },
      data: { nameField: 'Alice' },
    });

    // Simulate a redraw: attach, detach, attach again. The destroyed flag set by
    // detach() must be cleared on re-attach, otherwise overrides never compute.
    inst.attach({});
    inst.detach();
    inst.attach({});

    await jest.advanceTimersByTimeAsync(1500);
    expect(inst.setValue).toHaveBeenCalledWith({ doc: { name: 'Alice' } });
    expect(root.on).toHaveBeenCalledTimes(2); // listener re-registered on re-attach
  });

  it('does not fire a preview while the form is submitting', async () => {
    const { inst, root } = createInstance({
      overrideMapping: { doc: { name: 'form:nameField' } },
      data: { nameField: 'Alice' },
    });

    inst.attach({}); // schedules the initial compute
    root.submitting = true;

    await jest.advanceTimersByTimeAsync(1500);
    expect(inst.setValue).not.toHaveBeenCalled();
  });

  it('does not fire a preview once the form has been submitted', async () => {
    const { inst, root } = createInstance({
      overrideMapping: { doc: { name: 'form:nameField' } },
      data: { nameField: 'Alice' },
    });

    inst.attach({}); // schedules the initial compute
    root.submitted = true;

    await jest.advanceTimersByTimeAsync(1500);
    expect(inst.setValue).not.toHaveBeenCalled();
  });
});
