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

  function createComponent(formIoDocumentId: string | null = null) {
    const epistolaPluginService = {
      previewToBlob: jest.fn(() => of(new Blob(['pdf'], { type: 'application/pdf' }))),
    };
    const sanitizer = {
      bypassSecurityTrustResourceUrl: jest.fn((url: string) => `safe:${url}`),
    };
    const formIoStateService = { documentId: formIoDocumentId };
    const cdr = { markForCheck: jest.fn() };

    const component = new EpistolaDocumentPreviewComponent(
      epistolaPluginService as any,
      sanitizer as any,
      formIoStateService as any,
      cdr as any,
    );

    component.sourceActivityId = 'generate-decision';
    component.overrideMapping = '{"pv": {"decision": $form.decision}}';
    component.inputOverrides = { pv: { decision: 'approved' } };

    return { component, epistolaPluginService, sanitizer, formIoStateService, cdr };
  }

  function changes(...keys: string[]): SimpleChanges {
    return Object.fromEntries(keys.map((key) => [key, {}])) as SimpleChanges;
  }

  it('loads the runtime preview when a task id is available without a FormIo document id', () => {
    const { component, epistolaPluginService } = createComponent(null);
    component.taskInstanceId = 'task-1';

    component.ngOnChanges(changes('taskInstanceId', 'sourceActivityId', 'inputOverrides'));

    expect(component.designMode).toBe(false);
    expect(epistolaPluginService.previewToBlob).toHaveBeenCalledWith({
      taskId: 'task-1',
      sourceActivityId: 'generate-decision',
      inputOverrides: { pv: { decision: 'approved' } },
      overrides: null,
    });
  });

  it('stays in design mode when no runtime context is available', () => {
    const { component, epistolaPluginService } = createComponent(null);

    component.ngOnChanges(changes('sourceActivityId', 'inputOverrides'));

    expect(component.designMode).toBe(true);
    expect(epistolaPluginService.previewToBlob).not.toHaveBeenCalled();
  });

  it('leaves design mode and loads when the task id arrives later', () => {
    const { component, epistolaPluginService } = createComponent(null);

    component.ngOnChanges(changes('sourceActivityId', 'inputOverrides'));
    component.taskInstanceId = 'task-1';
    component.ngOnChanges(changes('taskInstanceId'));

    expect(component.designMode).toBe(false);
    expect(epistolaPluginService.previewToBlob).toHaveBeenCalledTimes(1);
  });
});
