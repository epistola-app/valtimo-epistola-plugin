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
import { EpistolaRetryFormComponent } from './epistola-retry-form.component';
import { readPrefilledTaskId, PREFILLED_TASK_ID_CARRIER } from '../../services/prefilled-task-id';
import { hideFormioComponentFromBuilder } from '../formio-builder-utils';

export const EPISTOLA_RETRY_FORM_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-retry-form',
  selector: 'epistola-retry-form-element',
  title: 'Epistola Retry Form',
  group: 'basic',
  icon: 'refresh',
  emptyValue: null,
  fieldOptions: ['sourceActivityId', 'label'], // sourceActivityId is optional (set via BPMN input parameter)
  // Embed the hidden task-id carrier so dropping this component is enough — no separate
  // field for the author to add. Valtimo prefills it server-side via the epistola: resolver.
  schema: { components: [PREFILLED_TASK_ID_CARRIER] },
};

export function registerEpistolaRetryFormComponent(injector: Injector): void {
  registerCustomFormioComponent(EPISTOLA_RETRY_FORM_OPTIONS, EpistolaRetryFormComponent, injector);

  // Extend the base class to forward the server-prefilled task id (epistola: value
  // resolver) to the Angular element, so the retry form authorizes against the exact task in
  // every Valtimo task-open flow.
  const Formio = (window as any).Formio;
  const BaseComponent = Formio?.Components?.components?.[EPISTOLA_RETRY_FORM_OPTIONS.type];
  if (!BaseComponent) {
    return;
  }

  class EpistolaRetryFormWithTaskContext extends BaseComponent {
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

  Formio.Components.setComponent(
    EPISTOLA_RETRY_FORM_OPTIONS.type,
    EpistolaRetryFormWithTaskContext,
  );

  // Part of the plugin's auto-deployed retry form, not a drop-anywhere component — hide it
  // from the builder palette. It still renders wherever it's already present in a form.
  hideFormioComponentFromBuilder(EPISTOLA_RETRY_FORM_OPTIONS.type);
}
