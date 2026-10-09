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
import {
  COMPOSER_COMPONENT_NAMESPACE,
  COMPOSER_COMPONENT_SCHEMA_FIELD,
  COMPOSER_SCHEMA_VERSION,
} from '../composer-schema';

export const EPISTOLA_LETTER_COMPOSER_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-letter-composer',
  selector: 'epistola-letter-composer-element',
  // Alpha: the composer works, but its stored shapes may still change between releases. Saying so
  // where an author picks it is the honest place — a changelog entry is not read at that moment.
  title: 'Epistola Letter Composer (alpha)',
  group: 'basic',
  icon: 'envelope',
  emptyValue: null,
  // The letters reach the Angular component so it can render the picker — as `letterSet` from the
  // settings widget, or as a bare `templates` array in a hand-written form. Nothing else about the
  // configuration is forwarded: the mappings and the catalog stay server-side, where the backend
  // reads them from this form definition itself (ADR 0006).
  fieldOptions: ['label', 'placeholder', 'templates', 'epistola', 'processDefinitionKey'],
  // Embed the hidden carriers so dropping the component is enough. Valtimo prefills them
  // server-side through the epistola: value resolvers, and the component reads them back: the task
  // id on a task form, the case id on a start form opened against an existing dossier.
  //
  // `prefill: false` excludes the composer's own value from Valtimo's prefill. Its key is a `pv:`
  // one, so that the chosen letter becomes a process variable on submit — but reading it back is
  // another matter: Valtimo resolves a `pv:` key against the case's process instances, and once a
  // dossier has more than one instance holding that variable it cannot pick, and fails the whole
  // form with a 500. Nothing should be prefilled here anyway: the letter is what the employee is
  // about to choose.
  schema: {
    prefill: false,
    // Stamped so a later plugin can tell what this component was authored against, and an earlier
    // one refuses it rather than misreading it. Kept in the saved form by withComposerDefaults.
    [COMPOSER_COMPONENT_NAMESPACE]: { [COMPOSER_COMPONENT_SCHEMA_FIELD]: COMPOSER_SCHEMA_VERSION },
    // The drop payload's label. Formio honours a schema `label`, but never a schema `key`: it
    // always recomputes the key from the palette title (`camelCase(builderInfo.title)`), which is
    // why the property name below is validated rather than defaulted.
    label: 'Choose a letter',
    components: [PREFILLED_TASK_ID_CARRIER, PREFILLED_DOCUMENT_ID_CARRIER],
  },
  editForm: () => ({
    components: [
      {
        // An author choosing this component deserves to know before they build a case around it.
        type: 'content',
        key: 'epistolaComposerAlphaNotice',
        html:
          '<div style="border-left:4px solid #f1c21b;background:#fcf4d6;padding:.5rem .75rem;' +
          'margin-bottom:1rem">' +
          '<strong>Alpha.</strong> This component works and is safe to use, but the way it ' +
          'saves its settings may still change. If that happens, a form you build now keeps ' +
          'working — you may just need to reopen these settings once after an update and check ' +
          'them. Your letters and your case data are never at risk.' +
          '</div>',
        weight: -10,
        input: false,
      },
      {
        type: 'textfield',
        key: 'key',
        label: 'Property name',
        tooltip:
          'Where the chosen letter is stored. Use a pv: key (for example pv:epistolaLetter) so the generate task can read it with $pv.',
        weight: 0,
        // A composer dropped from the palette arrives keyed `epistolaLetterComposer`, because
        // Formio derives the key from the palette title and ignores both a schema key and an
        // editForm defaultValue. Without a `pv:` prefix Valtimo stores the chosen letter in the
        // submission data instead of as a process variable, and the generate task then fails with
        // "no letter was composed" — a runtime failure, one step later, for a mistake made here.
        // So the author is made to type it.
        validate: {
          required: true,
          pattern: '^pv:[A-Za-z_][A-Za-z0-9_]*$',
          customMessage:
            'The property name must be a pv: key, for example pv:epistolaLetter — that is what makes the chosen letter a process variable the generate task can read.',
        },
      },
      {
        type: 'textfield',
        key: 'label',
        label: 'Label',
        defaultValue: 'Choose a letter',
        weight: 5,
      },
      {
        type: 'epistola-letter-set-builder',
        key: 'epistola.letterSet',
        label: 'Which letters, from where',
        tooltip:
          'Pick the Epistola connection and catalog, then tick the letters this form offers. Adding a letter later is one more tick.',
        weight: 10,
        validate: { required: true },
      },
      {
        // Only the property name and the letters are decisions every author makes. The rest have
        // working defaults, and at the same visual weight the panel read as six decisions instead
        // of two. Collapsed rather than removed: an author who needs the mapping finds it in the
        // obvious place, one click away.
        //
        // Formio reopens a collapsed panel by itself when something inside it fails validation
        // (Panel.js), so nothing can be refused behind a closed lid.
        type: 'panel',
        key: 'epistolaComposerAdvanced',
        title: 'Mapping and advanced settings',
        label: 'Mapping and advanced settings',
        collapsible: true,
        collapsed: true,
        input: false,
        weight: 40,
        components: [
          {
            type: 'epistola-jsonata-mapping',
            key: 'epistola.dataMapping',
            label: 'Baseline mapping',
            tooltip:
              'One JSONata mapping for every offered letter, over $doc and $pv. Whatever it does not fill is asked of the employee.',
            rows: 8,
            weight: 10,
          },
          {
            type: 'epistola-write-back-builder',
            key: 'epistola.writeBack',
            label: 'Also save these values on the case',
            tooltip:
              "Optional. A letter's values stay with the letter unless you say otherwise. Add a rule per value that also belongs in the case: a doc: or pv: destination, and a JSONata expression over the letter ($data, $inputs). Applied when the letter is generated, so nothing is saved for a letter Epistola refused.",
            weight: 20,
          },
          {
            type: 'checkbox',
            key: 'epistola.askOptionalFields',
            label: 'Also ask for optional fields the mapping left empty',
            tooltip:
              'Off by default: only fields the template marks required are asked for. Turn on to offer every empty field.',
            defaultValue: false,
            weight: 30,
          },
          {
            type: 'textfield',
            key: 'processDefinitionKey',
            label: 'Process to start',
            tooltip:
              'Normally leave this empty — the composer works out which process it belongs to on its own. ' +
              'Fill it in only if you are told the choice is ambiguous, which happens when two processes ' +
              'offer the same letter through a component with the same property name. Use the process key, ' +
              'such as correspondentie-ad-hoc-letter.',
            weight: 40,
          },
        ],
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
        withComposerDefaults(
          withPrefilledCarriers(base, [PREFILLED_TASK_ID_CARRIER, PREFILLED_DOCUMENT_ID_CARRIER]),
        ),
      ),
  );
}

/**
 * Keep `prefill: false` and the schema version in the saved form.
 *
 * Form.io drops schema that equals the registered default, exactly as it does with the hidden
 * carriers. Without this a form saved from the builder loses `prefill: false` and starts failing
 * with a 500 on the second process instance, and loses the version — leaving a component that
 * looks like it predates the field to every plugin that reads it afterwards. Both are values the
 * component must carry rather than inherit, so both are written back on serialization.
 *
 * The version is deliberately *not* forced onto a component that carries an older one: a form
 * authored against an earlier schema stays authored against it until someone changes it.
 */
function withComposerDefaults(
  BaseComponent: ValtimoFormioComponentConstructor,
): ValtimoFormioComponentConstructor {
  class WithComposerDefaults extends BaseComponent {
    getModifiedSchema(schema: any, defaultSchema: any, recursion: boolean): any {
      const modified = super.getModifiedSchema(schema, defaultSchema, recursion);
      if (!recursion) {
        modified.prefill = false;
        // The namespace is re-added whole: Form.io drops schema equal to the registered
        // default, and a version that went missing would make every saved component look as
        // though it predates the field.
        const authored = (schema ?? {})[COMPOSER_COMPONENT_NAMESPACE] ?? {};
        modified[COMPOSER_COMPONENT_NAMESPACE] = {
          ...(modified[COMPOSER_COMPONENT_NAMESPACE] ?? {}),
          [COMPOSER_COMPONENT_SCHEMA_FIELD]:
            authored[COMPOSER_COMPONENT_SCHEMA_FIELD] ?? COMPOSER_SCHEMA_VERSION,
        };
      }
      return modified;
    }
  }
  return WithComposerDefaults as unknown as ValtimoFormioComponentConstructor;
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
        // The component's own key, so the backend can find this composer's settings rather than
        // the first composer that happens to offer the chosen template.
        this._customAngularElement['componentKey'] = this.component?.key;
      }
      return result;
    }
  }
  return EpistolaLetterComposerWithTaskContext as unknown as ValtimoFormioComponentConstructor;
}
