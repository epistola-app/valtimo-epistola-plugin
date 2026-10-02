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

import { EpistolaWriteBackBuilderComponent } from './write-back-builder.component';
import { registerEpistolaFormioComponent } from '../../components/valtimo-formio-adapter';

export const EPISTOLA_WRITE_BACK_BUILDER_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-write-back-builder',
  selector: 'epistola-write-back-builder-element',
  title: 'Epistola Write-back',
  // editForm-only: it configures a letter composer, it is not something to drop on a form.
  group: 'none',
  icon: 'share',
  emptyValue: null,
};

export function registerEpistolaWriteBackBuilderComponent(injector: Injector): void {
  registerEpistolaFormioComponent(
    EPISTOLA_WRITE_BACK_BUILDER_OPTIONS,
    EpistolaWriteBackBuilderComponent,
    injector,
  );
}
