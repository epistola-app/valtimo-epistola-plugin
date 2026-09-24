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
jest.mock('@angular/core', () => ({
  ChangeDetectionStrategy: { OnPush: 'OnPush' },
  Component: () => (target: unknown) => target,
  Input: () => () => undefined,
  Output: () => () => undefined,
  EventEmitter: class {
    emit = jest.fn();
  },
}));

jest.mock('@angular/common', () => ({
  CommonModule: class {},
}));

jest.mock('@angular/platform-browser', () => ({
  DomSanitizer: class {},
}));

jest.mock('@formio/angular', () => ({
  FormioModule: class {},
}));

jest.mock('@valtimo/components', () => ({}));

jest.mock('../../services', () => ({
  EpistolaPluginService: class {},
}));

import { of, throwError } from 'rxjs';
import { EpistolaLetterComposerComponent } from './epistola-letter-composer.component';

describe('EpistolaLetterComposerComponent', () => {
  let originalCreateObjectUrl: typeof URL.createObjectURL | undefined;
  let originalRevokeObjectUrl: typeof URL.revokeObjectURL | undefined;

  beforeEach(() => {
    jest.useFakeTimers();
    originalCreateObjectUrl = URL.createObjectURL;
    originalRevokeObjectUrl = URL.revokeObjectURL;
    URL.createObjectURL = jest.fn(() => 'blob:preview');
    URL.revokeObjectURL = jest.fn();
  });

  afterEach(() => {
    jest.useRealTimers();
    URL.createObjectURL = originalCreateObjectUrl!;
    URL.revokeObjectURL = originalRevokeObjectUrl!;
  });

  function createComponent(prepared: Record<string, unknown> = {}) {
    const service = {
      composerPrepare: jest.fn(() =>
        of({
          templateId: 'besluit',
          label: 'Besluit',
          catalogId: 'gemeente',
          data: { naam: 'Jansen', motivatie: '' },
          form: { display: 'form', components: [{ type: 'textfield', key: 'motivatie' }] },
          complete: false,
          ...prepared,
        }),
      ),
      composerPreviewToBlob: jest.fn(() => of(new Blob(['pdf'], { type: 'application/pdf' }))),
    };
    const sanitizer = { bypassSecurityTrustResourceUrl: jest.fn((url: string) => `safe:${url}`) };
    const cdr = { markForCheck: jest.fn() };

    const component = new EpistolaLetterComposerComponent(
      service as any,
      cdr as any,
      sanitizer as any,
    );
    component.templates = [
      { templateId: 'besluit', label: 'Besluit' },
      { templateId: 'herinnering', label: 'Herinnering' },
    ];
    component.taskInstanceId = 'task-1';
    return { component, service };
  }

  it('asks the backend for the chosen letter, naming only the task and the template', () => {
    const { component, service } = createComponent();

    component.onTemplateSelected('besluit');

    expect(service.composerPrepare).toHaveBeenCalledWith({
      taskId: 'task-1',
      templateId: 'besluit',
    });
    expect(component.formDefinition).toEqual({
      display: 'form',
      components: [{ type: 'textfield', key: 'motivatie' }],
    });
  });

  it('previews the letter as the case alone produces it, before anything is typed', () => {
    const { component, service } = createComponent();

    component.onTemplateSelected('besluit');
    jest.advanceTimersByTime(1000);

    expect(service.composerPreviewToBlob).toHaveBeenCalledWith({
      taskId: 'task-1',
      templateId: 'besluit',
      data: { naam: 'Jansen', motivatie: '' },
    });
  });

  it('lays typed input over the mapped data and stores both halves', () => {
    const { component } = createComponent();
    component.onTemplateSelected('besluit');

    component.onInputsChanged({ data: { motivatie: 'omdat', naam: '' } });

    expect(component.value).toEqual({
      templateId: 'besluit',
      catalogId: 'gemeente',
      // The empty `naam` is pruned, so an untouched field never blanks what the mapping produced.
      data: { naam: 'Jansen', motivatie: 'omdat' },
      inputs: { motivatie: 'omdat' },
    });
  });

  it('previews what was typed, debounced', () => {
    const { component, service } = createComponent();
    component.onTemplateSelected('besluit');
    service.composerPreviewToBlob.mockClear();

    component.onInputsChanged({ data: { motivatie: 'eerst' } });
    component.onInputsChanged({ data: { motivatie: 'daarna' } });
    expect(service.composerPreviewToBlob).not.toHaveBeenCalled();

    jest.advanceTimersByTime(1000);
    expect(service.composerPreviewToBlob).toHaveBeenCalledTimes(1);
    expect(service.composerPreviewToBlob).toHaveBeenCalledWith({
      taskId: 'task-1',
      templateId: 'besluit',
      data: { naam: 'Jansen', motivatie: 'daarna' },
    });
  });

  it('says so when the mapping already filled everything', () => {
    const { component } = createComponent({ complete: true, form: { components: [] } });

    component.onTemplateSelected('besluit');

    expect(component.complete).toBe(true);
  });

  it('clears the letter when the selection is emptied', () => {
    const { component } = createComponent();
    component.onTemplateSelected('besluit');

    component.onTemplateSelected('');

    expect(component.selectedTemplateId).toBeNull();
    expect(component.value).toBeNull();
    expect(component.formDefinition).toBeNull();
  });

  it('does nothing without a task, since every call authorizes against one', () => {
    const { component, service } = createComponent();
    component.taskInstanceId = undefined;

    component.onTemplateSelected('besluit');

    expect(service.composerPrepare).not.toHaveBeenCalled();
  });

  it('prepares the restored letter once the task id arrives after the first render', () => {
    const { component, service } = createComponent();
    component.taskInstanceId = undefined;
    component.value = { templateId: 'besluit', catalogId: 'gemeente', data: {}, inputs: {} };

    component.ngOnChanges({ value: {} } as any);
    expect(service.composerPrepare).not.toHaveBeenCalled();

    component.taskInstanceId = 'task-1';
    component.ngOnChanges({ taskInstanceId: {} } as any);

    expect(service.composerPrepare).toHaveBeenCalledWith({
      taskId: 'task-1',
      templateId: 'besluit',
    });
  });

  it('surfaces why a letter could not be prepared', () => {
    const { component, service } = createComponent();
    service.composerPrepare.mockReturnValueOnce(
      throwError(() => ({ error: { error: 'Template is not offered by this form' } })),
    );

    component.onTemplateSelected('besluit');

    expect(component.error).toBe('Template is not offered by this form');
    expect(component.formDefinition).toBeNull();
  });
});
