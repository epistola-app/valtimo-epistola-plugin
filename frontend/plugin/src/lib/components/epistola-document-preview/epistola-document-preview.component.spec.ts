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

jest.mock('@valtimo/components', () => ({
  FormIoStateService: class {},
}));

jest.mock('../../services', () => ({
  EpistolaPluginService: class {},
}));

import type { SimpleChanges } from '@angular/core';
import { of } from 'rxjs';
import { EpistolaDocumentPreviewComponent } from './epistola-document-preview.component';

describe('EpistolaDocumentPreviewComponent', () => {
  let originalCreateObjectUrl: typeof URL.createObjectURL | undefined;
  let originalRevokeObjectUrl: typeof URL.revokeObjectURL | undefined;

  beforeEach(() => {
    originalCreateObjectUrl = URL.createObjectURL;
    originalRevokeObjectUrl = URL.revokeObjectURL;
    URL.createObjectURL = jest.fn(() => 'blob:preview');
    URL.revokeObjectURL = jest.fn();
  });

  afterEach(() => {
    URL.createObjectURL = originalCreateObjectUrl!;
    URL.revokeObjectURL = originalRevokeObjectUrl!;
  });

  function createComponent() {
    const epistolaPluginService = {
      previewToBlob: jest.fn(() => of(new Blob(['pdf'], { type: 'application/pdf' }))),
      previewStartToBlob: jest.fn(() => of(new Blob(['pdf'], { type: 'application/pdf' }))),
    };
    const sanitizer = {
      bypassSecurityTrustResourceUrl: jest.fn((url: string) => `safe:${url}`),
    };
    const cdr = { markForCheck: jest.fn() };

    const component = new EpistolaDocumentPreviewComponent(
      epistolaPluginService as any,
      sanitizer as any,
      cdr as any,
    );

    component.processDefinitionKey = 'objection-handling';
    component.sourceActivityId = 'generate-decision';
    component.overrideMapping = '{"pv": {"decision": $form.decision}}';
    component.inputOverrides = { pv: { decision: 'approved' } };

    return { component, epistolaPluginService, sanitizer, cdr };
  }

  function changes(...keys: string[]): SimpleChanges {
    return Object.fromEntries(keys.map((key) => [key, {}])) as SimpleChanges;
  }

  describe('task mode (the default)', () => {
    it('treats an unset previewContext as task mode, so existing forms are unchanged', () => {
      const { component, epistolaPluginService } = createComponent();
      component.taskInstanceId = 'task-1';

      component.ngOnChanges(changes('taskInstanceId', 'sourceActivityId', 'inputOverrides'));

      expect(component.previewContext).toBe('task');
      expect(epistolaPluginService.previewToBlob).toHaveBeenCalledWith({
        taskId: 'task-1',
        sourceActivityId: 'generate-decision',
        inputOverrides: { pv: { decision: 'approved' } },
        overrides: null,
      });
      expect(epistolaPluginService.previewStartToBlob).not.toHaveBeenCalled();
    });

    /**
     * The regression guard for the whole design: a missing task id must stay a loud, correct
     * failure. Falling back to the start endpoint here would swap a per-task gate for a
     * process-level one and silently render with empty $doc/$pv.
     */
    it('fails closed without a task id and never falls back to the start endpoint', () => {
      const { component, epistolaPluginService } = createComponent();

      component.ngOnChanges(changes('sourceActivityId', 'inputOverrides'));

      expect(component.error).toBe('Preview is only available from within a user task.');
      expect(epistolaPluginService.previewToBlob).not.toHaveBeenCalled();
      expect(epistolaPluginService.previewStartToBlob).not.toHaveBeenCalled();
    });

    it('loads when the task id arrives after the first render', () => {
      const { component, epistolaPluginService } = createComponent();

      component.ngOnChanges(changes('sourceActivityId', 'inputOverrides'));
      component.taskInstanceId = 'task-1';
      component.ngOnChanges(changes('taskInstanceId'));

      expect(epistolaPluginService.previewToBlob).toHaveBeenCalledTimes(1);
    });
  });

  describe('start mode', () => {
    it('calls the start endpoint with the process definition key, never the task endpoint', () => {
      const { component, epistolaPluginService } = createComponent();
      component.previewContext = 'start';

      component.ngOnChanges(changes('sourceActivityId', 'inputOverrides'));

      expect(epistolaPluginService.previewStartToBlob).toHaveBeenCalledWith({
        processDefinitionKey: 'objection-handling',
        sourceActivityId: 'generate-decision',
        documentId: null,
        inputOverrides: { pv: { decision: 'approved' } },
      });
      expect(epistolaPluginService.previewToBlob).not.toHaveBeenCalled();
    });

    it('forwards the prefilled document id for the start-on-existing-case flavour', () => {
      const { component, epistolaPluginService } = createComponent();
      component.previewContext = 'start';
      component.startDocumentId = '3f2504e0-4f89-11d3-9a0c-0305e82c3301';

      component.ngOnChanges(changes('sourceActivityId', 'inputOverrides'));

      expect(epistolaPluginService.previewStartToBlob).toHaveBeenCalledWith(
        expect.objectContaining({ documentId: '3f2504e0-4f89-11d3-9a0c-0305e82c3301' }),
      );
    });

    /**
     * The misconfiguration guard. A start-mode preview dropped on a user-task form would be
     * authorized on permission to start the process rather than on that task, weakening the
     * form's gate — so it must render nothing rather than pick a mode.
     */
    it('calls neither endpoint when placed on a user-task form', () => {
      const { component, epistolaPluginService } = createComponent();
      component.previewContext = 'start';
      component.taskInstanceId = 'task-1';

      component.ngOnChanges(changes('taskInstanceId', 'sourceActivityId', 'inputOverrides'));

      expect(epistolaPluginService.previewStartToBlob).not.toHaveBeenCalled();
      expect(epistolaPluginService.previewToBlob).not.toHaveBeenCalled();
      expect(component.error).toContain('configured for a start form');
    });

    it('reports a missing process link rather than calling the backend', () => {
      const { component, epistolaPluginService } = createComponent();
      component.previewContext = 'start';
      component.processDefinitionKey = '';

      component.ngOnChanges(changes('sourceActivityId', 'inputOverrides'));

      expect(epistolaPluginService.previewStartToBlob).not.toHaveBeenCalled();
      expect(component.error).toContain('not configured');
    });
  });

  describe('design mode', () => {
    it('renders the summary and issues no request when the wrapper reports design time', () => {
      const { component, epistolaPluginService } = createComponent();
      component.designMode = true;

      component.ngOnChanges(changes('sourceActivityId', 'inputOverrides'));

      expect(epistolaPluginService.previewToBlob).not.toHaveBeenCalled();
      expect(epistolaPluginService.previewStartToBlob).not.toHaveBeenCalled();
    });

    it('stays in design mode even once a task id turns up', () => {
      const { component, epistolaPluginService } = createComponent();
      component.designMode = true;

      component.ngOnChanges(changes('sourceActivityId'));
      component.taskInstanceId = 'task-1';
      component.ngOnChanges(changes('taskInstanceId'));

      expect(component.designMode).toBe(true);
      expect(epistolaPluginService.previewToBlob).not.toHaveBeenCalled();
    });

    it('defaults to runtime, so a real form is never mistaken for the builder', () => {
      const { component } = createComponent();

      expect(component.designMode).toBe(false);
    });
  });
});
