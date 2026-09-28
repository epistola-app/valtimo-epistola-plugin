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
      // Nothing is authored: no task id is what a start form looks like, and the dossier on screen
      // is what the letter is composed for.
      made.component.taskInstanceId = undefined;
      made.component.startDocumentId = 'doc-1';
      return made;
    }

    it('composes against the open dossier, leaving the process to the backend', () => {
      const { component, service } = startComponent();

      component.onTemplateSelected('besluit');

      expect(service.composerPrepareStart).toHaveBeenCalledWith({
        processDefinitionKey: undefined,
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
        processDefinitionKey: undefined,
        documentId: 'doc-1',
        templateId: 'besluit',
        componentKey: undefined,
        data: { naam: 'Jansen' },
      });
      expect(service.composerPreviewToBlob).not.toHaveBeenCalled();
    });

    it('passes an authored process key on, as the tie-breaker it is', () => {
      const { component, service } = startComponent();
      component.processDefinitionKey = 'correspondentie-ad-hoc';

      component.onTemplateSelected('besluit');

      expect(service.composerPrepareStart).toHaveBeenCalledWith(
        expect.objectContaining({ processDefinitionKey: 'correspondentie-ad-hoc' }),
      );
    });

    it('needs no process key, unlike before', () => {
      const { component } = startComponent();

      expect(component.canCompose).toBe(true);
    });

    it('stays inert with neither a task nor a case, as in the builder', () => {
      const { component, service } = startComponent();
      component.startDocumentId = undefined;

      component.onTemplateSelected('besluit');

      expect(service.composerPrepareStart).not.toHaveBeenCalled();
      expect(component.canCompose).toBe(false);
    });
  });

  describe('which context it is in', () => {
    /**
     * The whole point of dropping the authored mode: one configuration, wherever it is dropped.
     * The task id arrives through a prefill carrier only a task form fills, so its presence is the
     * answer — and the same component, unchanged, composes ad hoc on a dossier without one.
     */
    it('is read from the context, so one configuration serves a task form and a start form', () => {
      const { component, service } = createComponent();
      expect(component.onUserTask).toBe(true);

      component.onTemplateSelected('besluit');
      expect(service.composerPrepare).toHaveBeenCalled();
      expect(service.composerPrepareStart).not.toHaveBeenCalled();

      // The very same component, opened where no task exists.
      component.taskInstanceId = undefined;
      component.startDocumentId = 'doc-1';
      expect(component.onUserTask).toBe(false);

      component.onTemplateSelected('besluit');
      expect(service.composerPrepareStart).toHaveBeenCalled();
    });

    /**
     * A task id wins over a case id. A task form's composer must never fall through to the
     * start-form endpoint just because the dossier is also identifiable from the route: that
     * endpoint reads another form's configuration and has no process instance for `$pv`.
     */
    it('prefers the task even when a dossier is identifiable too', () => {
      const { component, service } = createComponent();
      component.startDocumentId = 'doc-1';

      component.onTemplateSelected('besluit');

      expect(service.composerPrepare).toHaveBeenCalled();
      expect(service.composerPrepareStart).not.toHaveBeenCalled();
    });
  });

  describe('a letter with a lot to fill in', () => {
    const manyFields = (count: number) => ({
      display: 'form',
      components: Array.from({ length: count }, (_, index) => ({
        type: 'textfield',
        key: `veld${index + 1}`,
        input: true,
      })),
    });

    it('is stepped through, with the navigation a wizard needs', () => {
      const { component } = createComponent({ form: manyFields(14), complete: false });

      component.onTemplateSelected('besluit');

      expect(component.formDefinition.display).toBe('wizard');
      expect(component.formOptions.buttonSettings.showNext).toBe(true);
      // Freely navigable: paging back through every step to fix one field is not filling a form in.
      expect(component.formOptions.breadcrumbSettings.clickable).toBe(true);
    });

    it('leaves a short letter as one form, with no navigation', () => {
      const { component } = createComponent({ form: manyFields(3), complete: false });

      component.onTemplateSelected('besluit');

      expect(component.formDefinition.display).toBe('form');
      expect(component.formOptions.buttonSettings.showNext).toBe(false);
    });

    it('still knows which fields are required once they are spread over steps', () => {
      // requiredKeys has to reach into the steps, or the preview would fire before the letter can
      // render and show Epistola's validation error instead of waiting.
      const form = manyFields(14);
      form.components[9] = {
        ...form.components[9],
        ...{ validate: { required: true } },
      } as any;
      const { component, service } = createComponent({ form, complete: false });

      component.onTemplateSelected('besluit');
      jest.advanceTimersByTime(2000);

      expect(component.awaitingRequired).toBe(true);
      expect(service.composerPreviewToBlob).not.toHaveBeenCalled();
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
