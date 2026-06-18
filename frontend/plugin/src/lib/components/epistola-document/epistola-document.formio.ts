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

import { Injector } from '@angular/core';
import { FormioCustomComponentInfo, registerCustomFormioComponent } from '@valtimo/components';
import { EpistolaDocumentComponent } from './epistola-document.component';
import { readPrefilledTaskId, PREFILLED_TASK_ID_CARRIER } from '../../services/prefilled-task-id';

export const EPISTOLA_DOCUMENT_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-document',
  selector: 'epistola-document-element',
  title: 'Epistola Document',
  group: 'basic',
  icon: 'file-pdf-o',
  emptyValue: null,
  fieldOptions: ['label', 'display', 'documentVariable', 'tenantIdVariable', 'filename'],
  // Embed the hidden task-id carrier so dropping this component is enough — no separate
  // field for the author to add. Valtimo prefills it server-side via the epistola: resolver.
  schema: { components: [PREFILLED_TASK_ID_CARRIER] },
};

export function registerEpistolaDocumentComponent(injector: Injector): void {
  if (customElements.get(EPISTOLA_DOCUMENT_OPTIONS.selector)) {
    return;
  }
  registerCustomFormioComponent(EPISTOLA_DOCUMENT_OPTIONS, EpistolaDocumentComponent, injector);

  // Extend the base class to forward the server-prefilled task id (epistola: value
  // resolver) to the Angular element, so the download authorizes against the exact task in
  // every Valtimo task-open flow.
  const Formio = (window as any).Formio;
  const BaseComponent = Formio?.Components?.components?.[EPISTOLA_DOCUMENT_OPTIONS.type];
  if (!BaseComponent) {
    return;
  }

  class EpistolaDocumentWithTaskContext extends BaseComponent {
    attach(element: HTMLElement) {
      const result = super.attach(element);
      if (this._customAngularElement) {
        const prefilledTaskId = readPrefilledTaskId(this.root);
        if (prefilledTaskId) {
          this._customAngularElement['taskInstanceId'] = prefilledTaskId;
        }
      }
      return result;
    }
  }

  Formio.Components.setComponent(EPISTOLA_DOCUMENT_OPTIONS.type, EpistolaDocumentWithTaskContext);
}
