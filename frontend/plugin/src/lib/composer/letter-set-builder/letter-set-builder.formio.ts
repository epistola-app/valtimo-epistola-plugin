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
import { EpistolaLetterSetBuilderComponent } from './letter-set-builder.component';
import {
  registerEpistolaFormioComponent,
  ValtimoFormioComponentConstructor,
} from '../../components/valtimo-formio-adapter';

export const EPISTOLA_LETTER_SET_BUILDER_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-letter-set-builder',
  selector: 'epistola-letter-set-builder-element',
  title: 'Epistola Letter Set Builder',
  // editForm-only: it configures a letter composer, it is not something to drop on a form.
  group: 'none',
  icon: 'list',
  emptyValue: null,
  fieldOptions: ['label', 'composerKey'],
};

export function registerEpistolaLetterSetBuilderComponent(injector: Injector): void {
  registerEpistolaFormioComponent(
    EPISTOLA_LETTER_SET_BUILDER_OPTIONS,
    EpistolaLetterSetBuilderComponent,
    injector,
    withComposerKey,
  );
}

/**
 * Hands the builder the key of the component being edited, so it can say what reads this letter.
 *
 * The key lives in the edit dialog's own data — `root.data.key` is the composer's key, the same
 * field the author types into — and it changes while the dialog is open, which is exactly when the
 * hint earns its keep: rename the key and the line of generate tasks goes empty in front of you.
 *
 * Mirrors `withAvailableFields` in the override builder: set `this.component[...]` before
 * `super.attach()` reads `fieldOptions`, then push later changes straight onto the Angular element,
 * because Formio does not redraw this widget when a sibling field changes.
 */
function withComposerKey(
  BaseComponent: ValtimoFormioComponentConstructor,
): ValtimoFormioComponentConstructor {
  class LetterSetBuilderWithComposerKey extends BaseComponent {
    private _keyChangeHandler: (() => void) | null = null;

    attach(element: HTMLElement) {
      this.component.composerKey = this._composerKey();

      const result = super.attach(element);

      if (this.root?.on && !this._keyChangeHandler) {
        this._keyChangeHandler = () => {
          if (this._customAngularElement) {
            this._customAngularElement['composerKey'] = this._composerKey();
          }
        };
        this.root.on('change', this._keyChangeHandler);
      }

      return result;
    }

    detach() {
      if (this._keyChangeHandler && this.root?.off) {
        this.root.off('change', this._keyChangeHandler);
        this._keyChangeHandler = null;
      }
      return super.detach();
    }

    private _composerKey(): string {
      const key = this.root?.data?.key;
      return typeof key === 'string' ? key : '';
    }
  }

  return LetterSetBuilderWithComposerKey;
}
