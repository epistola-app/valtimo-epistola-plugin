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
import { EpistolaLetterComposerComponent } from './epistola-letter-composer.component';
import { readPrefilledTaskId, PREFILLED_TASK_ID_CARRIER } from '../../services/prefilled-task-id';
import {
  registerEpistolaFormioComponent,
  ValtimoFormioComponentConstructor,
  withPrefilledTaskIdCarrier,
} from '../valtimo-formio-adapter';

export const EPISTOLA_LETTER_COMPOSER_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-letter-composer',
  selector: 'epistola-letter-composer-element',
  title: 'Epistola Letter Composer',
  group: 'basic',
  icon: 'envelope',
  emptyValue: null,
  // `templates` reaches the Angular component so it can render the picker. The rest of the
  // configuration (plugin configuration, catalog, mappings) is deliberately NOT forwarded: the
  // backend reads it from this form definition itself, so the browser never carries it — see
  // ADR 0006.
  fieldOptions: ['label', 'placeholder', 'templates'],
  // Embed the hidden task-id carrier so dropping the component is enough. Valtimo prefills it
  // server-side through the epistola: value resolver, and the component reads it back.
  schema: { components: [PREFILLED_TASK_ID_CARRIER] },
  editForm: () => ({
    components: [
      {
        type: 'textfield',
        key: 'key',
        label: 'Property name',
        tooltip:
          'Where the chosen letter is stored. Use a pv: key (for example pv:epistolaLetter) so the generate task can read it with $pv.',
        defaultValue: 'pv:epistolaLetter',
        weight: 0,
        validate: { required: true },
      },
      {
        type: 'textfield',
        key: 'label',
        label: 'Label',
        defaultValue: 'Choose a letter',
        weight: 5,
      },
      {
        type: 'textfield',
        key: 'pluginConfigurationId',
        label: 'Epistola plugin configuration id',
        tooltip: 'Which configured Epistola connection (tenant and credentials) to render with.',
        weight: 10,
        validate: { required: true },
      },
      {
        type: 'textfield',
        key: 'catalogId',
        label: 'Catalog',
        tooltip: 'The catalog every offered template lives in.',
        weight: 20,
        validate: { required: true },
      },
      {
        type: 'datagrid',
        key: 'templates',
        label: 'Letters on offer',
        tooltip:
          'Each row is one letter an employee can choose. Adding a letter here is the only change a new letter needs on this form.',
        weight: 30,
        validate: { required: true },
        components: [
          { type: 'textfield', key: 'templateId', label: 'Template', input: true },
          { type: 'textfield', key: 'label', label: 'Label', input: true },
          {
            type: 'textarea',
            key: 'dataMapping',
            label: 'Extra mapping (optional)',
            tooltip:
              'A JSONata fragment merged over the baseline for this letter only — for what makes this letter different.',
            input: true,
            rows: 2,
          },
        ],
      },
      {
        type: 'textarea',
        key: 'dataMapping',
        label: 'Baseline mapping',
        tooltip:
          'One JSONata mapping for every offered letter, over $doc and $pv. Whatever it does not fill is asked of the employee.',
        rows: 6,
        weight: 40,
      },
      {
        type: 'checkbox',
        key: 'askOptionalFields',
        label: 'Also ask for optional fields the mapping left empty',
        tooltip:
          'Off by default: only fields the template marks required are asked for. Turn on to offer every empty field.',
        defaultValue: false,
        weight: 50,
      },
    ],
  }),
};

export function registerEpistolaLetterComposerComponent(injector: Injector): void {
  registerEpistolaFormioComponent(
    EPISTOLA_LETTER_COMPOSER_OPTIONS,
    EpistolaLetterComposerComponent,
    injector,
    (base) => withTaskContext(withPrefilledTaskIdCarrier(base)),
  );
}

/**
 * Forward the server-prefilled task id to the Angular element. The composer authorizes every call
 * against that task, so without it the component stays inert — which is what should happen in the
 * builder and in design mode.
 */
function withTaskContext(
  BaseComponent: ValtimoFormioComponentConstructor,
): ValtimoFormioComponentConstructor {
  class EpistolaLetterComposerWithTaskContext extends BaseComponent {
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
  return EpistolaLetterComposerWithTaskContext as unknown as ValtimoFormioComponentConstructor;
}
