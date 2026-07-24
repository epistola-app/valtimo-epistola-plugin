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
import { FormioCustomComponentInfo } from '@valtimo/components';
import { EpistolaRetryFormComponent } from './epistola-retry-form.component';
import { readPrefilledTaskId, PREFILLED_TASK_ID_CARRIER } from '../../services/prefilled-task-id';
import {
  registerEpistolaFormioComponent,
  ValtimoFormioComponentConstructor,
} from '../valtimo-formio-adapter';

export const EPISTOLA_RETRY_FORM_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-retry-form',
  selector: 'epistola-retry-form-element',
  title: 'Epistola Retry Form',
  group: 'none',
  icon: 'refresh',
  emptyValue: null,
  fieldOptions: ['sourceActivityId', 'label'], // sourceActivityId is optional (set via BPMN input parameter)
  // Embed the hidden task-id carrier so dropping this component is enough — no separate
  // field for the author to add. Valtimo prefills it server-side via the epistola: resolver.
  schema: { components: [PREFILLED_TASK_ID_CARRIER] },
};

export function registerEpistolaRetryFormComponent(injector: Injector): void {
  registerEpistolaFormioComponent(
    EPISTOLA_RETRY_FORM_OPTIONS,
    EpistolaRetryFormComponent,
    injector,
    withTaskContext,
  );
}

function withTaskContext(
  BaseComponent: ValtimoFormioComponentConstructor,
): ValtimoFormioComponentConstructor {
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

  return EpistolaRetryFormWithTaskContext;
}
