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
import { EpistolaOverrideBuilderComponent } from './override-builder.component';
import { collectFormFields } from './collect-form-fields';
import {
  registerEpistolaFormioComponent,
  ValtimoFormioComponentConstructor,
} from '../valtimo-formio-adapter';

export const EPISTOLA_OVERRIDE_BUILDER_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-override-builder',
  selector: 'epistola-override-builder-element',
  title: 'Epistola Override Builder',
  group: 'none',
  icon: 'list',
  emptyValue: null,
  fieldOptions: ['label', 'availableFields', 'processDefinitionKey', 'sourceActivityId'],
};

export function registerEpistolaOverrideBuilderComponent(injector: Injector): void {
  registerEpistolaFormioComponent(
    EPISTOLA_OVERRIDE_BUILDER_OPTIONS,
    EpistolaOverrideBuilderComponent,
    injector,
    withAvailableFields,
  );
}

function withAvailableFields(
  BaseComponent: ValtimoFormioComponentConstructor,
): ValtimoFormioComponentConstructor {
  class OverrideBuilderWithFields extends BaseComponent {
    private _selectionChangeHandler: (() => void) | null = null;

    attach(element: HTMLElement) {
      // Set inputs on the component BEFORE super.attach() reads fieldOptions.
      this.component.availableFields = this._extractFormFields();
      this._applyProcessLinkSelection();

      const result = super.attach(element);

      // The override builder lives in the preview component's editForm alongside the
      // process-link selector (key `processLinkSelection`). When the author changes the
      // selected link, push the new identity straight to the Angular element so it can
      // refetch the link's data mapping — Formio doesn't always redraw this widget on a
      // sibling change. Mirrors the listener lifecycle in epistola-document-preview.formio.ts.
      if (this.root?.on && !this._selectionChangeHandler) {
        this._selectionChangeHandler = () => {
          const selection = this.root?.data?.processLinkSelection;
          if (this._customAngularElement) {
            this._customAngularElement['processDefinitionKey'] =
              selection?.processDefinitionKey || '';
            this._customAngularElement['sourceActivityId'] = selection?.sourceActivityId || '';
          }
        };
        this.root.on('change', this._selectionChangeHandler);
      }

      return result;
    }

    detach() {
      if (this._selectionChangeHandler && this.root?.off) {
        this.root.off('change', this._selectionChangeHandler);
        this._selectionChangeHandler = null;
      }
      return super.detach();
    }

    private _applyProcessLinkSelection(): void {
      // The editForm dialog stores the selected link under `processLinkSelection`.
      const selection = this.root?.data?.processLinkSelection;
      this.component.processDefinitionKey = selection?.processDefinitionKey || '';
      this.component.sourceActivityId = selection?.sourceActivityId || '';
    }

    private _extractFormFields(): { key: string; label: string }[] {
      // The Formio builder passes the main form schema as options.editForm
      // when opening the edit dialog (editFormOptions.editForm = this.form).
      const components = this.options?.editForm?.components;
      if (Array.isArray(components)) {
        return collectFormFields(components);
      }
      return [];
    }
  }

  return OverrideBuilderWithFields;
}
