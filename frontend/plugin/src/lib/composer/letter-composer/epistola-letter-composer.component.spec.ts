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
jest.mock('@valtimo/plugin', () => ({
  PluginTranslatePipeModule: class {},
  PluginTranslationService: class {},
}));

jest.mock('../composer-api.service', () => ({
  EpistolaComposerApiService: class {},
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
      composerPrepareStart: jest.fn(() =>
        of({
          templateId: 'besluit',
          label: 'Besluit',
          catalogId: 'gemeente',
          data: { naam: 'Jansen' },
          form: { display: 'form', components: [] },
          complete: true,
          ...prepared,
        }),
      ),
      composerPreviewStartToBlob: jest.fn(() => of(new Blob(['pdf'], { type: 'application/pdf' }))),
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
      { instant: jest.fn((key: string) => key) } as any,
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
      componentKey: undefined,
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
      componentKey: undefined,
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
      componentKey: undefined,
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

  it('offers the letters the settings widget stored', () => {
    const { component } = createComponent();
    component.templates = [];
    component.letterSet = { templates: [{ templateId: 'besluit', label: 'Besluit' }] };

    expect(component.offeredTemplates).toEqual([{ templateId: 'besluit', label: 'Besluit' }]);
  });

  it('still offers the letters of a hand-written form', () => {
    const { component } = createComponent();
    component.letterSet = undefined;

    expect(component.offeredTemplates.map((option) => option.templateId)).toEqual([
      'besluit',
      'herinnering',
    ]);
  });

  describe('a letter that cannot render yet', () => {
    const REQUIRED_FORM = {
      display: 'form',
      components: [
        { type: 'textfield', key: 'motivatie', validate: { required: true } },
        { type: 'textfield', key: 'toelichting' },
      ],
    };

    it('does not preview while a required field is empty', () => {
      // Epistola would answer with a validation error for the very fields the employee was just
      // asked to fill, which reads as a failure rather than as "not yet".
      const { component, service } = createComponent({ form: REQUIRED_FORM, complete: false });

      component.onTemplateSelected('besluit');
      jest.advanceTimersByTime(2000);

      expect(service.composerPreviewToBlob).not.toHaveBeenCalled();
      expect(component.awaitingRequired).toBe(true);
    });

    it('previews as soon as the required field has a value', () => {
      const { component, service } = createComponent({ form: REQUIRED_FORM, complete: false });
      component.onTemplateSelected('besluit');

      component.onInputsChanged({ data: { motivatie: 'omdat' } });
      jest.advanceTimersByTime(2000);

      expect(component.awaitingRequired).toBe(false);
      expect(service.composerPreviewToBlob).toHaveBeenCalledWith({
        taskId: 'task-1',
        templateId: 'besluit',
        componentKey: undefined,
        data: { naam: 'Jansen', motivatie: 'omdat' },
      });
    });
  });

  describe('on a start form (an ad-hoc letter, no task)', () => {
    function startComponent() {
      const made = createComponent();
      made.component.composerContext = 'start';
      made.component.taskInstanceId = undefined;
      made.component.processDefinitionKey = 'correspondentie-ad-hoc';
      made.component.startDocumentId = 'doc-1';
      return made;
    }

    it('composes against the open dossier, naming the process it would start', () => {
      const { component, service } = startComponent();

      component.onTemplateSelected('besluit');

      expect(service.composerPrepareStart).toHaveBeenCalledWith({
        processDefinitionKey: 'correspondentie-ad-hoc',
        documentId: 'doc-1',
        templateId: 'besluit',
        componentKey: undefined,
      });
      expect(service.composerPrepare).not.toHaveBeenCalled();
    });

    it('previews through the start endpoint', () => {
      const { component, service } = startComponent();
      component.onTemplateSelected('besluit');
      jest.advanceTimersByTime(1000);

      expect(service.composerPreviewStartToBlob).toHaveBeenCalledWith({
        processDefinitionKey: 'correspondentie-ad-hoc',
        documentId: 'doc-1',
        templateId: 'besluit',
        componentKey: undefined,
        data: { naam: 'Jansen' },
      });
      expect(service.composerPreviewToBlob).not.toHaveBeenCalled();
    });

    it('stays inert without a process to start, as in the builder', () => {
      const { component, service } = startComponent();
      component.processDefinitionKey = undefined;

      component.onTemplateSelected('besluit');

      expect(service.composerPrepareStart).not.toHaveBeenCalled();
      expect(component.canCompose).toBe(false);
    });

    it('needs no task id, unlike task mode', () => {
      const { component } = startComponent();

      expect(component.canCompose).toBe(true);
    });
  });

  it('names itself, so the backend finds this composer rather than another on the same form', () => {
    const { component, service } = createComponent();
    component.componentKey = 'pv:epistolaLetter';

    component.onTemplateSelected('besluit');

    expect(service.composerPrepare).toHaveBeenCalledWith({
      taskId: 'task-1',
      templateId: 'besluit',
      componentKey: 'pv:epistolaLetter',
    });
  });
});
