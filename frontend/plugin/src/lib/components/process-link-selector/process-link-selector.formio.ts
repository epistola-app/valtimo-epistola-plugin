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
import { EpistolaProcessLinkSelectorComponent } from './process-link-selector.component';
import { hideFormioComponentFromBuilder } from '../formio-builder-utils';

export const EPISTOLA_PROCESS_LINK_SELECTOR_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-process-link-selector',
  selector: 'epistola-process-link-selector-element',
  title: 'Epistola Process Link Selector',
  group: 'basic',
  icon: 'link',
  emptyValue: null,
  fieldOptions: ['label'],
};

export function registerEpistolaProcessLinkSelectorComponent(injector: Injector): void {
  registerCustomFormioComponent(
    EPISTOLA_PROCESS_LINK_SELECTOR_OPTIONS,
    EpistolaProcessLinkSelectorComponent,
    injector,
  );
  // Internal editForm widget — not a standalone form field. Hide it from the builder palette.
  hideFormioComponentFromBuilder(EPISTOLA_PROCESS_LINK_SELECTOR_OPTIONS.type);
}
