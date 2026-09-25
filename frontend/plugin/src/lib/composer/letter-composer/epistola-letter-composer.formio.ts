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
import {
  readPrefilledTaskId,
  readPrefilledDocumentId,
  PREFILLED_TASK_ID_CARRIER,
  PREFILLED_DOCUMENT_ID_CARRIER,
} from '../../services/prefilled-task-id';
import {
  registerEpistolaFormioComponent,
  ValtimoFormioComponentConstructor,
  withPrefilledCarriers,
} from '../../components/valtimo-formio-adapter';

export const EPISTOLA_LETTER_COMPOSER_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-letter-composer',
  selector: 'epistola-letter-composer-element',
  title: 'Epistola Letter Composer',
  group: 'basic',
  icon: 'envelope',
  emptyValue: null,
  // The letters reach the Angular component so it can render the picker — as `letterSet` from the
  // settings widget, or as a bare `templates` array in a hand-written form. Nothing else about the
  // configuration is forwarded: the mappings and the catalog stay server-side, where the backend
  // reads them from this form definition itself (ADR 0006).
  fieldOptions: [
    'label',
    'placeholder',
    'templates',
    'letterSet',
    'composerContext',
    'processDefinitionKey',
  ],
  // Embed the hidden carriers so dropping the component is enough. Valtimo prefills them
  // server-side through the epistola: value resolvers, and the component reads them back: the task
  // id on a task form, the case id on a start form opened against an existing dossier.
  schema: { components: [PREFILLED_TASK_ID_CARRIER, PREFILLED_DOCUMENT_ID_CARRIER] },
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
        type: 'radio',
        key: 'composerContext',
        label: 'Where is this form shown?',
        tooltip:
          'A start-form composer is authorized on your permission to start that process, not on a user task. Choose it for an ad-hoc letter on an open dossier.',
        defaultValue: 'task',
        inline: true,
        weight: 6,
        values: [
          { label: 'In a user task (default)', value: 'task' },
          { label: 'On a start form', value: 'start' },
        ],
      },
      {
        type: 'textfield',
        key: 'processDefinitionKey',
        label: 'Process to start',
        tooltip:
          'The key of the process this start form starts. Stored as a key, not a version-pinned id, so a redeployment does not break the form.',
        weight: 7,
        conditional: { show: true, when: 'composerContext', eq: 'start' },
      },
      {
        type: 'epistola-letter-set-builder',
        key: 'letterSet',
        label: 'Which letters, from where',
        tooltip:
          'Pick the Epistola connection and catalog, then tick the letters this form offers. Adding a letter later is one more tick.',
        weight: 10,
        validate: { required: true },
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
    (base) =>
      withTaskContext(
        withPrefilledCarriers(base, [PREFILLED_TASK_ID_CARRIER, PREFILLED_DOCUMENT_ID_CARRIER]),
      ),
  );
}

/**
 * Forward the server-prefilled ids to the Angular element: the task the composer authorizes
 * against, and — on a start form — the dossier the ad-hoc letter is composed for. Without them the
 * component stays inert, which is what should happen in the builder and in design mode.
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
        this._customAngularElement['startDocumentId'] = readPrefilledDocumentId(this.root);
      }
      return result;
    }
  }
  return EpistolaLetterComposerWithTaskContext as unknown as ValtimoFormioComponentConstructor;
}
